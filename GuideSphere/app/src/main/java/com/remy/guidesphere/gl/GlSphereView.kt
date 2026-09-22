package com.remy.guidesphere.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import java.io.File

/**
 * 承载引导球渲染的 TextureView。
 *
 * 选择 TextureView 而不是 GLSurfaceView 的原因：
 *   - GLSurfaceView 是独立 Surface，与相机预览的层级/透明混合很难做到既正确又简单；
 *   - TextureView 是普通 View，可以自然地叠在相机预览之上、HUD 之下，
 *     以预乘 alpha 直接混合，不需要 ZOrderOnTop 之类的 hack。
 *
 * 渲染线程使用 HandlerThread + Choreographer 跟随 vsync 驱动，配合
 * eglPresentationTimeANDROID 提交时间戳，保证稳定的 60fps 节奏。
 */
class GlSphereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    val renderer = GuideSphereRenderer()

    private var renderThread: RenderThread? = null

    init {
        // 允许与下层相机预览做 alpha 混合
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        resetDiag()
        renderThread?.shutdownAndWait()
        val thread = RenderThread(renderer, width, height, ::dumpDiag)
        renderThread = thread
        thread.start()
        thread.glHandler.post { thread.initAndLoop(surface) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.postResize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.shutdownAndWait()
        renderThread = null
        // 返回 true：由系统释放 SurfaceTexture，下次可用时我们会重建 EGL surface
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    /**
     * 把 GL 初始化的关键节点与失败原因**落盘**。
     *
     * 部分 ROM（一加/OPPO 的 LOG_FLOWCTRL）会对每个进程的 logcat 做行数配额，
     * 超配额之后直接把日志丢掉 —— 真出问题时 logcat 里干干净净，什么都看不到。
     * 渲染线程的异常只在日志里出现一次，靠 logcat 排查等于碰运气，
     * 所以这里额外写一份到 `Android/data/<包名>/files/gl_diag.txt`。
     */
    private fun dumpDiag(stage: String, t: Throwable? = null) {
        try {
            val dir: File = context.getExternalFilesDir(null) ?: return
            File(dir, "gl_diag.txt").appendText(
                "[${System.currentTimeMillis()}] $stage\n" +
                    (t?.stackTraceToString() ?: "") + "\n"
            )
        } catch (_: Throwable) {
            // 诊断本身失败不能影响渲染主流程
        }
    }

    /** 每次重建 surface 时清空诊断文件，只保留本次会话的记录，避免文件无限增长 */
    private fun resetDiag() {
        try {
            val dir: File = context.getExternalFilesDir(null) ?: return
            File(dir, "gl_diag.txt").writeText("")
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 渲染线程

    private class RenderThread(
        private val renderer: GuideSphereRenderer,
        initialW: Int,
        initialH: Int,
        private val diag: (String, Throwable?) -> Unit
    ) : HandlerThread("GuideSphere-GL", Process.THREAD_PRIORITY_DISPLAY) {

        /** getLooper() 会阻塞到 looper 就绪，因此这个属性在任何线程访问都安全 */
        val glHandler: Handler by lazy { Handler(looper) }

        @Volatile
        private var pendingW = initialW

        @Volatile
        private var pendingH = initialH

        @Volatile
        private var running = false

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var lastFrameNanos = 0L
        private var frameCount = 0L
        private var choreographer: Choreographer? = null

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val dt = if (lastFrameNanos == 0L) {
                    1f / 60f
                } else {
                    ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
                }
                lastFrameNanos = frameTimeNanos

                renderer.onSurfaceChanged(pendingW, pendingH)
                renderer.drawFrame(dt)

                frameCount++
                if (frameCount == 1L) diag("首帧渲染完成 ${pendingW}x$pendingH", null)

                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    Log.w(TAG, "eglSwapBuffers 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
                    diag("eglSwapBuffers 失败", null)
                } else {
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameTimeNanos)
                }
                choreographer?.postFrameCallback(this)
            }
        }

        fun postResize(width: Int, height: Int) {
            pendingW = width
            pendingH = height
        }

        /** 在渲染线程上创建 EGL 环境并启动 vsync 循环 */
        fun initAndLoop(surfaceTexture: SurfaceTexture) {
            diag("surface 可用 ${pendingW}x$pendingH，开始初始化", null)
            try {
                initEgl(surfaceTexture)
                diag("EGL 就绪", null)
            } catch (t: Throwable) {
                Log.e(TAG, "初始化 EGL 失败", t)
                diag("初始化 EGL 失败", t)
                return
            }
            try {
                renderer.onSurfaceCreated()
                renderer.onSurfaceChanged(pendingW, pendingH)
                diag("GL 资源创建完成，点尺寸上限=${renderer.maxPointSizeForDiagnostics}", null)
            } catch (t: Throwable) {
                Log.e(TAG, "创建 GL 资源失败", t)
                diag("创建 GL 资源失败", t)
                return
            }
            running = true
            lastFrameNanos = 0L
            val ch = Choreographer.getInstance()
            choreographer = ch
            ch.postFrameCallback(frameCallback)
        }

        /** 停止渲染并等待线程退出，确保 EGL 资源一定被释放 */
        fun shutdownAndWait() {
            if (!isAlive) return
            running = false
            glHandler.post {
                choreographer?.removeFrameCallback(frameCallback)
                choreographer = null
                try {
                    renderer.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "释放 GL 资源异常", t)
                }
                destroyEgl()
                quitSafely()
            }
            try {
                join(800)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun initEgl(surfaceTexture: SurfaceTexture) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay 失败")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize 失败")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0
                ) || numConfigs[0] <= 0
            ) {
                throw RuntimeException("找不到支持 alpha 通道的 ES3 EGLConfig")
            }
            val config = configs[0] ?: throw RuntimeException("EGLConfig 为空")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("eglCreateContext 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, surfaceTexture, surfaceAttribs, 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("eglCreateWindowSurface 失败: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent 失败")
            }
            // 跟随 vsync，避免撕裂与无谓功耗
            EGL14.eglSwapInterval(eglDisplay, 1)
        }

        private fun destroyEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }

        private companion object {
            const val TAG = "GlSphereView"

            /**
             * EGL_OPENGL_ES3_BIT_KHR。
             * android.opengl.EGL14 没有导出这个常量（它只到 ES2），
             * 这里直接按 EGL_KHR_create_context 扩展的定义写死。
             */
            const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        }
    }
}

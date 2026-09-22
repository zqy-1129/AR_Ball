package com.remy.guidesphere.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import com.remy.guidesphere.scan.ScanSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.tan

/**
 * 引导球渲染器。
 *
 * 渲染管线（全部在 GL 线程）：
 *   1. 清屏为全透明 —— 相机画面由下层 PreviewView 提供，本层只负责"叠加"；
 *   2. （可选）相机不可用时铺一层渐变底；
 *   3. 半透明灰色球壳，菲涅尔边缘光，预乘 alpha over 混合；
 *   4. 经纬网格线，over 混合，给三维朝向参照；
 *   5. 引导光点，点精灵 + 径向辉光，**加法混合**，点亮的做脉冲放大。
 *
 * 球体不再铺满整个画面，而是缩到屏幕短边的约三分之一并停靠在画面下方
 * （见 [SPHERE_SIZE] / [SPHERE_ANCHOR_Y]），把中央空间让给真实相机画面。
 *
 * 「已覆盖」的表达完全交给第 5 步的点阵本身：扫到哪个面，那个面的 21 个点整片点亮
 * （青绿），尚未采集的目标面整片琥珀呼吸。历史版本还有一条「区域光斑」通道
 * （同一批方向上的大尺寸柔光斑叠成连续色块），在点阵成面之后会与面的边界互相打架，
 * 已整体删除。
 *
 * 性能要点：
 *   - 三类几何全部静态 VBO，每帧只有 3 次 draw call；
 *   - 光点状态数组预分配，逐帧更新不使用任何装箱/集合，零 GC；
 *   - 着色器没有 uniform 分支，片元开销很低，稳定 60fps。
 */
class GuideSphereRenderer {

    /** 相机垂直视场角 */
    private val fovYDeg = 46f

    /** 相机到球心的距离（球半径 1 的模型空间） */
    private val camDistance = 2.6f

    val geometry = SphereGeometry(dotCount = DOT_COUNT)
    val session = ScanSession(geometry.dotDirections)

    // ---------------------------------------------------------------- 外部输入

    /**
     * 姿态来源。渲染线程每帧调用一次，把「世界->设备 旋转矩阵」写入 out 并返回。
     * 用回调而不是共享数组，是为了让传感器 / 触摸 / 自动巡航三种来源
     * 可以自由切换而渲染器完全无感。
     */
    fun interface OrientationSource {
        fun worldToDevice(out9: FloatArray): FloatArray
    }

    /** 姿态来源，为空时球体保持静止 */
    @Volatile
    var orientationSource: OrientationSource? = null

    /** 是否自动采样 */
    @Volatile
    var autoCapture: Boolean = true

    /** 请求一次快门（渲染线程消费后清空） */
    @Volatile
    var shutterRequest: Boolean = false

    /** 请求重置覆盖数据 */
    @Volatile
    var resetRequest: Boolean = false

    /** 设备没有相机时画一个渐变底，保证球体可见 */
    @Volatile
    var drawFallbackBackground: Boolean = false

    /** 统计回调，节流上报 */
    var onStats: ((Stats) -> Unit)? = null

    /** 渲染线程内部使用的姿态缓冲 */
    private val orientationTmp = FloatArray(9).also {
        it[0] = 1f; it[4] = 1f; it[8] = 1f
    }

    // ---------------------------------------------------------------- GL 资源

    private var shellProgram: GlProgram? = null
    private var dotProgram: GlProgram? = null
    private var bgProgram: GlProgram? = null

    private var shellVbo = 0
    private var shellIbo = 0
    private var gridVbo = 0
    private var dotVbo = 0
    private var bgVbo = 0

    private var shellIndexCount = 0
    private var gridVertexCount = 0

    private lateinit var dotCpu: FloatBuffer

    private var viewportW = 1
    private var viewportH = 1

    /** 驱动支持的 gl_PointSize 上限，仅用于诊断上报（真机 1023 / 部分模拟器仅 64） */
    private var maxPointSize = 64f

    /** 把驱动实际上报的点尺寸上限暴露给诊断日志（写入 gl_diag.txt） */
    val maxPointSizeForDiagnostics: Float
        get() = maxPointSize

    // 矩阵与临时缓冲（全部预分配）
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)

    private val gradientColors = floatArrayOf(0.05f, 0.07f, 0.11f, 0.02f, 0.03f, 0.05f)

    private var ready = false

    // 时间统计
    private var fpsAccumTime = 0f
    private var fpsFrameCount = 0
    private var statsAccum = 0f
    private var lastFps = 0
    private var lastDrawMs = 0f
    private val stats = Stats()

    // 上一次的球壳覆盖度（平滑值），用于球壳亮度过渡
    private var shellCoverageAnim = 0f

    /** 当前帧球体半径（模型空间），用于推算屏幕半径给 HUD 对齐 */
    private var lastSphereRadius = 0.4f

    // ---------------------------------------------------------------- 生命周期

    fun onSurfaceCreated() {
        release()
        shellProgram = GlProgram(Shaders.SHELL_VS, Shaders.SHELL_FS, "shell")
        dotProgram = GlProgram(Shaders.DOT_VS, Shaders.DOT_FS, "dot")
        bgProgram = GlProgram(Shaders.BG_VS, Shaders.BG_FS, "bg")

        // ---- 球壳 ----
        val shellVboArr = IntArray(2)
        GLES30.glGenBuffers(2, shellVboArr, 0)
        shellVbo = shellVboArr[0]
        shellIbo = shellVboArr[1]

        val shellFb = geometry.shellVertexData.toDirectBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, shellVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            geometry.shellVertexData.size * 4,
            shellFb,
            GLES30.GL_STATIC_DRAW
        )

        val idx = geometry.shellIndices
        val idxFb = ByteBuffer.allocateDirect(idx.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        idxFb.put(idx)
        idxFb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, shellIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, idxFb, GLES30.GL_STATIC_DRAW)
        shellIndexCount = idx.size
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // ---- 网格线 ----
        val gridBuf = IntArray(1)
        GLES30.glGenBuffers(1, gridBuf, 0)
        gridVbo = gridBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            geometry.gridVertexData.size * 4,
            geometry.gridVertexData.toDirectBuffer(),
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        gridVertexCount = geometry.gridVertexData.size / 6

        // ---- 光点（动态更新） ----
        val dotBuf = IntArray(1)
        GLES30.glGenBuffers(1, dotBuf, 0)
        dotVbo = dotBuf[0]
        dotCpu = ByteBuffer.allocateDirect(geometry.dotCount * DOT_STRIDE * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        dotCpu.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dotVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            geometry.dotCount * DOT_STRIDE * 4,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // ---- 全屏渐变底 ----
        val bgData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val bgBuf = IntArray(1)
        GLES30.glGenBuffers(1, bgBuf, 0)
        bgVbo = bgBuf[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bgVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bgData.size * 4, bgData.toDirectBuffer(), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)

        // 点精灵尺寸同样受驱动上限约束（真机 1023 / 部分模拟器仅 64），
        // 查一次留作诊断，避免贴近镜头时被静默截断还不知道原因。
        val pointRange = FloatArray(2)
        GLES30.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, pointRange, 0)
        maxPointSize = pointRange[1].coerceIn(16f, 1024f)
        android.util.Log.i(TAG, "gl_PointSize 上限=${pointRange[1]}")

        ready = true
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
    }

    fun release() {
        ready = false
        val bufs = intArrayOf(shellVbo, shellIbo, gridVbo, dotVbo, bgVbo)
        GLES30.glDeleteBuffers(bufs.size, bufs, 0)
        shellVbo = 0; shellIbo = 0; gridVbo = 0; dotVbo = 0; bgVbo = 0
        shellProgram?.release(); shellProgram = null
        dotProgram?.release(); dotProgram = null
        bgProgram?.release(); bgProgram = null
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 绘制一帧。
     * @param dtSeconds 距上一帧的时间（秒）
     */
    fun drawFrame(dtSeconds: Float) {
        if (!ready) return
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()

        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        val w = viewportW
        val h = viewportH
        val aspect = w.toFloat() / h.toFloat()

        // ---- 状态机推进 ----
        if (resetRequest) {
            session.reset()
            resetRequest = false
        }
        val shutter = shutterRequest
        if (shutter) shutterRequest = false

        val tanHalfY = tan(Math.toRadians(fovYDeg.toDouble() / 2.0)).toFloat()

        val worldToDevice = orientationSource?.worldToDevice(orientationTmp) ?: orientationTmp

        session.update(
            dt = dt,
            worldToDevice = worldToDevice,
            autoCapture = autoCapture,
            shutterPressed = shutter
        )

        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (drawFallbackBackground) {
            drawGradientBackground()
        }

        // ---- 相机与投影 ----
        Matrix.perspectiveM(proj, 0, fovYDeg, aspect, 0.1f, 100f)
        // 非对称视锥：只把取景窗口整体上下平移，让球心落在屏幕的 SPHERE_ANCHOR_Y 处。
        // 用的是投影矩阵第 (1,2) 元（列主序下标 9），相机本身仍正对球心停在
        // (0,0,camDistance)，所以球壳菲涅尔、光点朝向判定（uCamPos）全都不受影响。
        // 如果改成平移相机，uCamPos 必须跟着改，边缘光还会整片偏到一侧。
        proj[9] = 2f * (SPHERE_ANCHOR_Y - 0.5f)

        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -camDistance)

        // 球半径随宽高比自适应：竖屏时以宽度为准，避免球被裁切
        val sphereRadius = SPHERE_SIZE * tanHalfY * camDistance * aspect.coerceAtMost(1f)
        lastSphereRadius = sphereRadius

        QuatMath.toGlMatrix(model, worldToDevice, sphereRadius)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

        // 覆盖度驱动球壳整体亮度：灰球不换色，只在扫满时微微提亮
        val cov = shellCoverageAnim + (session.coverage - shellCoverageAnim) * (dt * 3f).coerceAtMost(1f)
        shellCoverageAnim = cov

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        drawShell(mvp, model, cov)
        drawGrid(mvp, model, cov)
        updateDotBuffer()
        // 球面覆盖完全由「整面点阵」表达（ScanSession 的扇区整面点亮 + 目标面琥珀高亮），
        // 不再铺绿色区域光斑——避免光晕与「点阵成面」的视觉语言互相打架。
        drawDots(mvp, model, sphereRadius)

        GlProgram.logGlError("drawFrame")

        // ---- 统计 ----
        val t1 = android.os.SystemClock.elapsedRealtimeNanos()
        lastDrawMs = (t1 - t0) / 1_000_000f
        fpsAccumTime += dt
        fpsFrameCount++
        if (fpsAccumTime >= 0.5f) {
            lastFps = Math.round(fpsFrameCount / fpsAccumTime)
            fpsAccumTime = 0f
            fpsFrameCount = 0
        }
        statsAccum += dt
        if (statsAccum >= STATS_INTERVAL) {
            statsAccum = 0f
            stats.fps = lastFps
            stats.drawMs = lastDrawMs
            stats.total = session.count
            stats.lit = session.litCount
            stats.coverage = session.coverage
            stats.inFrameUnlit = session.inFrameUnlit
            stats.lastBatch = session.lastBatch
            stats.batchFlash = session.batchFlash
            // 球体在屏幕上的投影半径（像素），让 HUD 的进度环能严丝合缝地套在球外面
            val ratio = (lastSphereRadius / camDistance).coerceIn(0f, 0.999f)
            val angular = kotlin.math.asin(ratio.toDouble())
            stats.sphereRadiusPx =
                (kotlin.math.tan(angular) * (viewportH * 0.5) / tanHalfY).toFloat()
            // 球心在屏幕上的位置：投影做了上下平移，HUD 必须用同一个锚点才不会错位
            stats.sphereCenterXPx = w * 0.5f
            stats.sphereCenterYPx = SPHERE_ANCHOR_Y * h
            // 传给 HUD，用来画"该往哪边转"的方向指示
            stats.nextX = session.nextDirX
            stats.nextY = session.nextDirY
            stats.hasTarget = session.hasTarget
            // 360° 方位扇区引导
            stats.doneCount = session.doneCount
            stats.camSector = session.camSector
            stats.viewportWidth = w
            stats.viewportHeight = h
            onStats?.invoke(stats)
        }
    }

    private fun drawGradientBackground() {
        val p = bgProgram ?: return
        GLES30.glDisable(GLES30.GL_BLEND)
        p.use()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bgVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        p.set3f("uTop", gradientColors[0], gradientColors[1], gradientColors[2])
        p.set3f("uBottom", gradientColors[3], gradientColors[4], gradientColors[5])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun drawShell(mvpM: FloatArray, modelM: FloatArray, coverage: Float) {
        val p = shellProgram ?: return
        p.use()
        p.set4x4("uMvp", mvpM)
        p.set4x4("uModel", modelM)
        p.set3f("uCamPos", 0f, 0f, camDistance)
        // 灰色底色球：中性灰核心 + 略亮边缘，覆盖度提升时核心微微提亮（非彩色），
        // 整体保持"哑光灰球"质感，不抢相机画面。
        val lift = 0.06f * coverage
        p.set3f("uCoreColor", 0.30f + lift, 0.32f + lift, 0.35f + lift)
        p.set3f("uRimColor", 0.62f, 0.65f, 0.70f)
        p.set1f("uAlpha", 0.92f)
        p.set1f("uBias", 0.18f)
        p.set1f("uPower", 1.8f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, shellVbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, shellIbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, shellIndexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawGrid(mvpM: FloatArray, modelM: FloatArray, coverage: Float) {
        val p = shellProgram ?: return
        p.use()
        p.set4x4("uMvp", mvpM)
        p.set4x4("uModel", modelM)
        p.set3f("uCamPos", 0f, 0f, camDistance)
        // 经纬网格线：中性浅灰，仅作三维朝向参照，不与灰球抢色
        p.set3f("uCoreColor", 0.40f, 0.43f, 0.47f)
        p.set3f("uRimColor", 0.60f + 0.18f * coverage, 0.63f + 0.18f * coverage, 0.68f + 0.18f * coverage)
        p.set1f("uAlpha", 0.38f)
        p.set1f("uBias", 0.30f)
        p.set1f("uPower", 1.3f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, gridVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun updateDotBuffer() {
        dotCpu.position(0)
        val dirs = geometry.dotDirections
        val lit = session.litAmount
        val pulse = session.pulse
        val hint = session.hintAmount
        var o = 0
        for (i in 0 until geometry.dotCount) {
            val i3 = i * 3
            dotCpu.put(o, dirs[i3])
            dotCpu.put(o + 1, dirs[i3 + 1])
            dotCpu.put(o + 2, dirs[i3 + 2])
            dotCpu.put(o + 3, lit[i])
            dotCpu.put(o + 4, pulse[i])
            dotCpu.put(o + 5, hint[i])
            o += DOT_STRIDE
        }
        dotCpu.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dotVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, geometry.dotCount * DOT_STRIDE * 4, dotCpu)
    }

    private fun drawDots(mvpM: FloatArray, modelM: FloatArray, radius: Float) {
        val p = dotProgram ?: return
        // 光点与球壳共用同一套 MVP（此处在上面已算好 mvp/model，这里直接复用）
        p.use()
        p.set4x4("uMvp", mvpM)
        p.set4x4("uModel", modelM)
        p.set3f("uCamPos", 0f, 0f, camDistance)
        p.set1f("uPxPerWorld", pxPerWorld)
        p.set1f("uDotWorldSize", radius * DOT_SIZE_RATIO)
        p.set1f("uMaxPointSize", MAX_DOT_POINT_SIZE)
        p.set3f("uLitColor", 0.20f, 0.88f, 1.00f)
        // 未点亮的点：压成比灰球更深的灰，作为"待填充"标记，扫到即变青亮
        p.set3f("uDimColor", 0.34f, 0.37f, 0.42f)
        p.set3f("uHintColor", 1.00f, 0.72f, 0.28f)
        p.set1f("uDimStrength", 0.50f)
        p.set1f("uLitStrength", 1.00f)

        // 加法混合：让光点像"灯光"一样叠在相机画面上
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)

        bindDotAttribs()
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, geometry.dotCount)
        unbindDotAttribs()

        // 还原为 over 混合，供下一帧的球壳/网格使用
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** 光点通道的顶点属性布局：dir(3) + lit(1) + pulse(1) + hint(1) */
    private fun bindDotAttribs() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glEnableVertexAttribArray(3)
        val stride = DOT_STRIDE * 4
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 20)
    }

    private fun unbindDotAttribs() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
    }

    // ---------------------------------------------------------------- 辅助

    /** 一个世界单位在"距离 1"处对应的像素数：(viewportHeight / 2) / tan(fovY / 2) */
    private val pxPerWorld: Float
        get() = (viewportH * 0.5f) / tan(Math.toRadians(fovYDeg.toDouble() / 2.0)).toFloat()

    private fun FloatArray.toDirectBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(this); it.position(0) }

    /** 渲染线程节流上报给 UI 的统计量 */
    class Stats {
        var fps: Int = 0
        var drawMs: Float = 0f
        var total: Int = 0
        var lit: Int = 0
        var coverage: Float = 0f
        var inFrameUnlit: Int = 0
        var lastBatch: Int = 0
        var batchFlash: Float = 0f
        var sphereRadiusPx: Float = 0f

        /** 球心在屏幕上的位置（像素）——HUD 的进度环与停靠面板要对齐到这里 */
        var sphereCenterXPx: Float = 0f
        var sphereCenterYPx: Float = 0f

        /** 下一个建议目标在**设备坐标系**下的水平方向（+x 屏幕右 / +y 屏幕上） */
        var nextX: Float = 0f
        var nextY: Float = 0f

        /** 是否还有未采集的面；false 时 HUD 不画方向箭头 */
        var hasTarget: Boolean = false

        // ---- 360° 方位扇区引导（与球面光点覆盖绑定） ----
        var doneCount: Int = 0
        var camSector: Int = 0

        var viewportWidth: Int = 0
        var viewportHeight: Int = 0

        /** 拷贝一份，避免跨线程共享同一个可变对象 */
        fun copyFrom(other: Stats) {
            fps = other.fps
            drawMs = other.drawMs
            total = other.total
            lit = other.lit
            coverage = other.coverage
            inFrameUnlit = other.inFrameUnlit
            lastBatch = other.lastBatch
            batchFlash = other.batchFlash
            sphereRadiusPx = other.sphereRadiusPx
            sphereCenterXPx = other.sphereCenterXPx
            sphereCenterYPx = other.sphereCenterYPx
            nextX = other.nextX
            nextY = other.nextY
            hasTarget = other.hasTarget
            doneCount = other.doneCount
            camSector = other.camSector
            viewportWidth = other.viewportWidth
            viewportHeight = other.viewportHeight
        }
    }

    companion object {
        /**
         * 球直径占屏幕**短边**的比例。
         *
         * 引导球只是"被摄物体的状态指示器"，不再铺满画面 ——
         * 中间的大片区域要留给真实相机画面，用户才能看清自己正在拍什么。
         */
        const val SPHERE_SIZE = 0.34f

        /**
         * 球心在屏幕上的纵向锚点（0 = 顶部，1 = 底部）。
         *
         * 渲染器与 HUD 共用这一个常量：渲染器用它生成投影偏移，
         * HUD 用它把进度环 / 停靠面板套在球外面，两边永远不会错位。
         */
        const val SPHERE_ANCHOR_Y = 0.68f

        /** 光点数量：8 方位扇区 × 每扇区 3 方位列 × 7 仰角行 = 168，与 SphereGeometry 同步 */
        const val DOT_COUNT = SPHERE_DOT_COUNT

        /** 光点顶点步长（float 个数）：dir(3) + lit(1) + pulse(1) + hint(1) */
        const val DOT_STRIDE = 6

        /** 光点直径相对球半径的比例 */
        const val DOT_SIZE_RATIO = 0.055f

        /** 光点通道的点尺寸上限，防止近距离时糊成一片 */
        const val MAX_DOT_POINT_SIZE = 48f

        const val STATS_INTERVAL = 0.2f

        private const val TAG = "GuideSphereRenderer"
    }
}

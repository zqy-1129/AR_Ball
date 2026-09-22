package com.remy.guidesphere.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * 相机预览控制：把后置相机的画面铺满整个屏幕，作为引导球的背景。
 *
 * 之所以用 CameraX 而不是直接写 Camera2：
 *  - 预览方向、镜像、生命周期、设备兼容性都已经被 CameraX 处理掉，
 *    样板代码量少一个数量级，出错面也更小；
 *  - PreviewView 使用 COMPATIBLE 模式（TextureView 实现）时是普通 View，
 *    可以直接被上面的 GL 引导球层做 alpha 混合，不需要折腾 Surface 层级。
 */
class CameraPreviewController(
    private val context: Context,
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner
) {

    enum class State { IDLE, STARTING, READY, NO_PERMISSION, UNAVAILABLE }

    var onStateChanged: ((State) -> Unit)? = null

    private var provider: ProcessCameraProvider? = null
    private var started = false

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 申请权限前先看设备有没有相机，没有就别申请了 */
    fun deviceHasCamera(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun start() {
        if (started) return
        if (!hasCameraPermission()) {
            onStateChanged?.invoke(State.NO_PERMISSION)
            return
        }
        if (!deviceHasCamera()) {
            onStateChanged?.invoke(State.UNAVAILABLE)
            return
        }
        started = true
        onStateChanged?.invoke(State.STARTING)

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                bind(cameraProvider)
            } catch (t: Throwable) {
                Log.e(TAG, "相机初始化失败", t)
                started = false
                onStateChanged?.invoke(State.UNAVAILABLE)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val selector = pickSelector(cameraProvider)
        if (selector == null) {
            Log.w(TAG, "设备上没有可用相机")
            started = false
            onStateChanged?.invoke(State.UNAVAILABLE)
            return
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
            onStateChanged?.invoke(State.READY)
        } catch (t: Throwable) {
            Log.e(TAG, "绑定相机失败", t)
            started = false
            onStateChanged?.invoke(State.UNAVAILABLE)
        }
    }

    private fun pickSelector(cameraProvider: ProcessCameraProvider): CameraSelector? = when {
        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> null
    }

    /** 权限被授予后重新尝试启动 */
    fun onPermissionResult(granted: Boolean) {
        if (granted) start() else onStateChanged?.invoke(State.NO_PERMISSION)
    }

    fun stop() {
        provider?.unbindAll()
        started = false
    }

    private companion object {
        const val TAG = "CameraPreview"
    }
}

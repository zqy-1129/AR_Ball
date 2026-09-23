package com.remy.guidesphere

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.remy.guidesphere.camera.CameraPreviewController
import com.remy.guidesphere.gl.GlSphereView
import com.remy.guidesphere.gl.GuideSphereRenderer
import com.remy.guidesphere.sensor.OrientationTracker
import com.remy.guidesphere.ui.HudView

/**
 * 3D 扫描引导页。
 *
 * 页面结构（自下而上）：
 *   PreviewView（相机画面） -> GlSphereView（引导球） -> HudView（交互与提示）
 *
 * 数据流：
 *   OrientationTracker  --姿态-->  GuideSphereRenderer（GL 线程，60fps）
 *                                        |
 *                                     统计量
 *                                        v
 *                                   HudView（主线程，5Hz 更新）
 *   HudView --快门/模式/重置--> GuideSphereRenderer 的 volatile 开关
 */
class MainActivity : ComponentActivity(), HudView.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var glView: GlSphereView
    private lateinit var hudView: HudView

    private lateinit var cameraController: CameraPreviewController
    private lateinit var orientationTracker: OrientationTracker

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraReady = false
    private var permissionGranted = false
    private var hasCamera = false

    /** 首次使用引导是否正在显示（期间冻结自动采集，让进度从真正的 0 开始） */
    private var onboardingActive = false

    /** 统计日志节流时间戳 */
    private var lastLogMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        cameraController.onPermissionResult(granted)
        pushHudState()
    }

    /** 最近一次渲染统计（主线程持有，避免与 GL 线程竞争） */
    private var lastStats = GuideSphereRenderer.Stats().also {
        // 首帧渲染统计到达之前，HUD 也要能显示合理的初始文案
        it.total = GuideSphereRenderer.DOT_COUNT
        it.hasTarget = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏：相机画面铺满，HUD 自绘，避免系统栏干扰
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        glView = findViewById(R.id.glSphereView)
        hudView = findViewById(R.id.hudView)
        hudView.listener = this

        orientationTracker = OrientationTracker(this)
        glView.renderer.orientationSource = orientationTracker
        glView.renderer.drawFallbackBackground = true

        glView.renderer.onStats = { stats ->
            // 在 GL 线程里拷贝一份，再投递到主线程，避免跨线程读写同一对象
            val copy = GuideSphereRenderer.Stats().also { it.copyFrom(stats) }
            mainHandler.post { onRenderStats(copy) }
        }

        cameraController = CameraPreviewController(this, previewView, this)
        cameraController.onStateChanged = { state -> mainHandler.post { onCameraState(state) } }

        hasCamera = cameraController.deviceHasCamera()
        permissionGranted = cameraController.hasCameraPermission()

        if (permissionGranted) {
            cameraController.start()
        } else {
            pushHudState()
        }

        maybeStartOnboarding()

        // 兜底：设备确实没有可用的方向传感器时，切到"触摸拖拽 / 自动巡航"，
        // 保证功能始终可用。
        // 这里刻意做成周期检查而不是一次性定时器 —— 传感器冷启动时首个采样
        // 可能晚到好几秒（部分 ROM 的省电策略会拖后腿），一次性判定会把
        // "还没启动好"误判成"没有传感器"，而且进了手动模式就再也回不来。
        mainHandler.post(sensorWatchdog)
    }

    /**
     * 首次启动时展示三页引导。
     *
     * 引导期间把自动采集冻结掉：App 一打开，镜头正对的那个面停留 0.22s 就会被记一面，
     * 用户什么都还没做进度就已经是 12.5% —— 配上引导页会显得很莫名。
     * 无相机硬件时直接跳过引导，否则永远不会有人来关闭它。
     */
    private fun maybeStartOnboarding() {
        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)) return
        if (!hasCamera) {
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
            return
        }
        onboardingActive = true
        glView.renderer.autoCapture = false
        hudView.startOnboarding()
        pushHudState()
    }

    override fun onResume() {
        super.onResume()
        orientationTracker.start()
        if (permissionGranted) cameraController.start()
    }

    override fun onPause() {
        super.onPause()
        orientationTracker.stop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sensorWatchdog)
        super.onDestroy()
    }

    /**
     * 传感器兜底看门狗。
     *
     * 用 [OrientationTracker.hasSensor] 而不是"最近有没有数据"来判断设备有没有
     * 方向传感器：前者表示硬件存在且注册成功，与数据到达的快慢无关，
     * 才不会被冷启动的延迟骗到。传感器恢复上报后会自动退出手动兜底。
     */
    private val sensorWatchdog = object : Runnable {
        private val startedMs = android.os.SystemClock.elapsedRealtime()

        override fun run() {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedMs
            if (elapsed >= 1500L) {
                if (!orientationTracker.hasSensor) {
                    orientationTracker.forceManualMode()
                } else {
                    orientationTracker.releaseFallbackIfSensorRecovered()
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // ------------------------------------------------------------------ 渲染统计

    private fun onRenderStats(stats: GuideSphereRenderer.Stats) {
        lastStats = stats
        logStats(stats)
        pushHudState()
    }

    /** 把最新的渲染统计 + 各子系统的状态一起推给 HUD */
    private fun pushHudState() {
        hudView.updateState(
            stats = lastStats,
            autoCapture = glView.renderer.autoCapture,
            autoOrbit = orientationTracker.isAutoOrbit,
            sourceLabel = currentSourceLabel(),
            sensorFresh = orientationTracker.isSensorFresh(),
            cameraReady = cameraReady,
            permissionGranted = permissionGranted,
            hasCamera = hasCamera
        )
    }

    /**
     * 节流把渲染统计打到 logcat，便于在没有 UI 的情况下量化覆盖率与帧率。
     *
     * 注意真机（一加/OPPO 的 LOG_FLOWCTRL）可能整片丢弃应用日志，
     * 排查时优先看落盘文件 `Android/data/<包名>/files/gl_diag.txt`。
     */
    private fun logStats(stats: GuideSphereRenderer.Stats) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogMs < 500L) return
        lastLogMs = now
        android.util.Log.i(
            "GuideSphereStats",
            "cov=${(stats.coverage * 1000).toInt() / 10f}% lit=${stats.lit}/${stats.total} " +
                "faces=${stats.doneCount}/8 cam=${stats.camSector} target=${stats.targetSector} " +
                "dwell=${(stats.camFaceProgress * 100).toInt()}% " +
                "fps=${stats.fps} drawMs=${stats.drawMs} " +
                "rPx=${stats.sphereRadiusPx.toInt()} cy=${stats.sphereCenterYPx.toInt()} " +
                "next=(${fmt(stats.nextX)},${fmt(stats.nextY)}) src=${currentSourceLabel()}"
        )
    }

    private fun fmt(v: Float): String = (v * 100).toInt().let { "${it / 100f}" }

    private fun onCameraState(state: CameraPreviewController.State) {
        cameraReady = state == CameraPreviewController.State.READY
        // 相机没画面时才铺渐变底，否则会挡住实时预览
        glView.renderer.drawFallbackBackground = !cameraReady
    }

    private fun currentSourceLabel(): String = when {
        orientationTracker.isAutoOrbit -> "自动巡航"
        // 手动接管要和「加速度+磁力」区分开：前者球是冻住的，后者球跟着手机转。
        // 少了这一支，用户拖过一次屏幕之后，芯片会一直谎报成「加速度+磁力」。
        orientationTracker.isManual && !orientationTracker.hasSensor -> "触摸旋转"
        orientationTracker.isManual -> "手动接管"
        !orientationTracker.hasSensor || !orientationTracker.isSensorFresh() -> "等待传感器"
        orientationTracker.mode == OrientationTracker.Mode.SENSOR -> "陀螺仪姿态"
        else -> "加速度+磁力"
    }

    // ------------------------------------------------------------------ HUD 交互

    override fun onShutter() {
        glView.renderer.shutterRequest = true
    }

    override fun onToggleAutoCapture(): Boolean {
        val next = !glView.renderer.autoCapture
        glView.renderer.autoCapture = next
        return next
    }

    override fun onReset() {
        glView.renderer.resetRequest = true
    }

    override fun onToggleAutoOrbit(): Boolean {
        val next = !orientationTracker.isAutoOrbit
        orientationTracker.setAutoOrbit(next)
        return next
    }

    override fun onRequestPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDrag(dx: Float, dy: Float) {
        orientationTracker.onDrag(dx, dy)
    }

    override fun onOnboardingFinished() {
        onboardingActive = false
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        glView.renderer.autoCapture = true
        pushHudState()
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private companion object {
        const val PREFS_NAME = "guidesphere"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}

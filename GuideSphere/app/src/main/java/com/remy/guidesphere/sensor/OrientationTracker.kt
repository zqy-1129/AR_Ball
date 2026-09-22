package com.remy.guidesphere.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.remy.guidesphere.gl.GuideSphereRenderer.OrientationSource
import com.remy.guidesphere.gl.QuatMath

/**
 * 姿态追踪：把「手机怎么动」变成「球怎么转」。
 *
 * 三种姿态来源，按优先级自动降级：
 *  1. [Mode.SENSOR]         — TYPE_ROTATION_VECTOR，带陀螺仪融合，最稳；
 *  2. [Mode.SENSOR_FUSION]  — 设备没有旋转矢量传感器时，用加速度计 + 磁力计解算；
 *  3. [Mode.MANUAL]        — 完全没有传感器（例如部分模拟器）时，允许手指拖拽旋转，
 *                             同时提供"自动巡航"用于演示 360° 环绕。
 *
 * 所有姿态统一表示为一个四元数（设备->世界），并用 slerp 做低通平滑，
 * 这样即使传感器有抖动，球的转动依然顺滑。
 */
class OrientationTracker(context: Context) : SensorEventListener, OrientationSource {

    enum class Mode { SENSOR, SENSOR_FUSION, MANUAL }

    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)

    // ---- 传感器融合状态（sensor 线程写 / 渲染线程读） ----
    private val smoothedQuat = FloatArray(4).also { it[0] = 1f }
    private val rawQuat = FloatArray(4)
    private val accel = FloatArray(3)
    private val mag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private val rotationMatrix = FloatArray(9)
    private val orientationMatrix = FloatArray(3)

    /** 上一帧旋转矢量解出的四元数，用于累计"旋转矢量到底转了多少" */
    private val lastRvQuat = FloatArray(4).also { it[0] = 1f }

    @Volatile
    private var lastRotationVectorNs = 0L

    // ---- 旋转矢量"卡死"检测 ----
    // 判据不能是"多久没变化"，因为用户端稳手机不动本来就是常态。
    // 真正可靠的判据是两个独立信源互相矛盾：
    //   旋转矢量在观测窗口内几乎没转，而加速度计+磁力计解算出的姿态却明显动了。
    /** 当前观测窗口内，旋转矢量的累计转动量（弧度） */
    private var rvIdleRad = 0f

    /** 观测窗口起点（0 表示尚未建立窗口） */
    @Volatile
    private var rvWindowStartNs = 0L

    /** 加速度计+磁力计的最新解算结果 */
    private val accelMagQuat = FloatArray(4).also { it[0] = 1f }

    /** 窗口起点时刻，加速度计+磁力计的姿态，用作对照基准 */
    private val accelMagAtWindowStart = FloatArray(4).also { it[0] = 1f }

    /** 交出主导权之后，旋转矢量累计的"真实转动量"，够了才交还 */
    private var rvRecoverRad = 0f

    /**
     * 是否已经切换到"加速度计 + 磁力计"主导。
     * 一旦切换，旋转矢量分支就只负责"监听它是否恢复"，不再参与姿态计算，
     * 否则两路数据会互相拉扯，姿态会变得又慢又偏。
     */
    @Volatile
    private var useAccelMag = false

    /**
     * 当前的手动模式是不是"没有传感器"兜底进来的（区别于用户主动拖拽）。
     * 兜底是可以自愈的：传感器一旦恢复上报就自动交还。
     */
    @Volatile
    private var manualByFallback = false

    @Volatile
    private var lastAccelNs = 0L

    @Volatile
    private var lastMagNs = 0L

    @Volatile
    var hasSensor: Boolean = false
        private set

    @Volatile
    var mode: Mode = Mode.MANUAL
        private set

    // ---- 手动 / 自动巡航状态 ----
    @Volatile
    private var manualEnabled = false

    @Volatile
    private var azimuthDeg = 0f

    @Volatile
    private var elevationDeg = 10f

    @Volatile
    private var autoOrbit = false

    private var autoOrbitBaseAzimuth = 0f
    private var autoOrbitStartNs = 0L

    // 渲染线程私有缓冲
    private val worldToDevice = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private val deviceToWorld = FloatArray(9)
    private val renderQuat = FloatArray(4).also { it[0] = 1f }

    // ------------------------------------------------------------------ 生命周期

    fun start() {
        val sm = sensorManager ?: run { mode = Mode.MANUAL; return }
        val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        var registered = false
        if (rotationVector != null) {
            registered = sm.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
            if (registered) {
                mode = Mode.SENSOR
                hasSensor = true
            }
        }
        // 无论有没有旋转矢量，都把加速度计 + 磁力计挂在监听器上：
        // 一是作为降级方案，二是旋转矢量失效时可以兜底。
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        if (!registered && accelSensor != null && magSensor != null) {
            mode = Mode.SENSOR_FUSION
            hasSensor = true
        }
        if (!hasSensor) mode = Mode.MANUAL
        Log.i(TAG, "姿态来源: $mode, hasSensor=$hasSensor")
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    // ------------------------------------------------------------------ 手动/巡航

    /** 手指拖拽：按屏幕位移改变环绕方位角与俯仰角 */
    fun onDrag(dxPx: Float, dyPx: Float) {
        captureManualStart()
        // 用户主动接管，不再自愈回传感器
        manualByFallback = false
        autoOrbit = false
        azimuthDeg = normalizeDeg(azimuthDeg - dxPx * DRAG_DEG_PER_PX)
        elevationDeg = (elevationDeg + dyPx * DRAG_DEG_PER_PX).coerceIn(-85f, 85f)
    }

    /** 开启 / 关闭自动巡航（绕着物体自动转一圈，用于演示与自检） */
    fun setAutoOrbit(enabled: Boolean) {
        autoOrbit = enabled
        if (enabled) {
            captureManualStart()
            manualByFallback = false
            autoOrbitBaseAzimuth = azimuthDeg
            autoOrbitStartNs = SystemClock.elapsedRealtimeNanos()
            mode = Mode.MANUAL
        } else if (hasSensor) {
            // 关掉巡航后交还传感器，而不是停在一个冻结的手动姿态上：
            // 否则球会僵在原地不动，用户会以为 App 坏了。
            manualEnabled = false
            manualByFallback = false
            mode = if (useAccelMag) Mode.SENSOR_FUSION else Mode.SENSOR
        }
    }

    val isAutoOrbit: Boolean get() = autoOrbit

    val isManual: Boolean get() = manualEnabled

    /** 手动接管：从当前传感器姿态无缝接过来，避免画面跳变 */
    private fun captureManualStart() {
        if (manualEnabled) return
        manualEnabled = true
        synchronized(smoothedQuat) {
            System.arraycopy(smoothedQuat, 0, renderQuat, 0, 4)
        }
        val q = renderQuat
        // 设备 +Z 轴（即相机背向）在世界系下的方向
        val zx = 2f * (q[1] * q[3] + q[0] * q[2])
        val zy = 2f * (q[2] * q[3] - q[0] * q[1])
        val zz = 1f - 2f * (q[1] * q[1] + q[2] * q[2])
        azimuthDeg = Math.toDegrees(kotlin.math.atan2(zy.toDouble(), zx.toDouble())).toFloat()
        elevationDeg = Math.toDegrees(
            kotlin.math.asin(zz.coerceIn(-1f, 1f).toDouble())
        ).toFloat().coerceIn(-85f, 85f)
    }

    /**
     * 传感器数据是否是"新鲜"的。
     * 部分设备/模拟器会注册成功但永远不上报数据，界面需要据此降级到拖拽模式。
     */
    fun isSensorFresh(): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        val rvFresh = lastRotationVectorNs > 0L && now - lastRotationVectorNs < FRESH_NS
        val amFresh = hasAccel && hasMag &&
            now - lastAccelNs < FRESH_NS && now - lastMagNs < FRESH_NS
        return rvFresh || amFresh
    }

    // ------------------------------------------------------------------ 传感器回调

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getQuaternionFromVector(rawQuat, event.values)
                val now = SystemClock.elapsedRealtimeNanos()
                val moved = angleBetween(lastRvQuat, rawQuat)
                System.arraycopy(rawQuat, 0, lastRvQuat, 0, 4)

                // 维护观测窗口：只要累计转动量超过阈值就重开窗口，
                // 于是"窗口持续了很久"就等价于"这段时间里它基本没转过"。
                if (rvIdleRad + moved > RV_FROZEN_RAD) {
                    rvIdleRad = 0f
                    rvWindowStartNs = now
                    System.arraycopy(accelMagQuat, 0, accelMagAtWindowStart, 0, 4)
                } else {
                    rvIdleRad += moved
                    if (rvWindowStartNs == 0L) rvWindowStartNs = now
                }

                if (useAccelMag) {
                    // 已经改由加速度计+磁力计主导。要交还主导权，旋转矢量必须
                    // 累计转够角度 —— 否则两路信源会在阈值附近反复争夺，姿态抖动。
                    rvRecoverRad += moved
                    if (rvRecoverRad >= RV_RECOVER_RAD) {
                        useAccelMag = false
                        rvRecoverRad = 0f
                        Log.i(TAG, "旋转矢量恢复，交还主导权")
                    }
                } else {
                    rvRecoverRad = 0f
                    smoothTo(rawQuat)
                    if (!manualEnabled) {
                        mode = Mode.SENSOR
                        hasSensor = true
                    }
                }
                lastRotationVectorNs = now
            }

            Sensor.TYPE_ACCELEROMETER -> {
                accel[0] = event.values[0]; accel[1] = event.values[1]; accel[2] = event.values[2]
                hasAccel = true
                lastAccelNs = SystemClock.elapsedRealtimeNanos()
                maybeUpdateFromAccelMag()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                mag[0] = event.values[0]; mag[1] = event.values[1]; mag[2] = event.values[2]
                hasMag = true
                lastMagNs = SystemClock.elapsedRealtimeNanos()
                maybeUpdateFromAccelMag()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 自适应平滑：姿态变化很小时用强滤波抑制抖动，
     * 变化很大时（用户快速转身）立刻跟上，避免"球追不上手"的拖影感。
     */
    private fun smoothTo(target: FloatArray) {
        val dot = smoothedQuat[0] * target[0] + smoothedQuat[1] * target[1] +
            smoothedQuat[2] * target[2] + smoothedQuat[3] * target[3]
        val delta = 1f - kotlin.math.abs(dot)
        val alpha = if (delta > FAST_SMOOTH_TRIGGER) FAST_SMOOTHING else SMOOTHING
        QuatMath.slerp(smoothedQuat, smoothedQuat, target, alpha)
    }

    /**
     * 加速度计 + 磁力计解算姿态。
     *
     * 只有在下面两种情况之一，才让它接管姿态：
     *  1. 旋转矢量根本不新鲜（设备没有该传感器 / 被 ROM 禁用）；
     *  2. 旋转矢量虽然在上报、但确实卡死了 —— 判据是"它自己几乎没转动，
     *     而加速度计+磁力计解算出的姿态却明显动了"这种**两路信源互相矛盾**。
     *
     * 早期版本用的是"旋转矢量 1 秒内没有变化就认为卡死"，但这个条件在真机上
     * 会被无条件触发：相邻采样间的变化量阈值 1e-4 约等于 1.6°，在 90Hz 上报
     * 下相当于要求转速超过 140°/s。结果是用户慢速环绕拍摄时全程都在用噪声
     * 更大、易受磁干扰的加速度计+磁力计解算，只有在猛甩手机时才切回陀螺仪，
     * 两路信源还会来回争夺。现在改为独立信源交叉验证。
     */
    private fun maybeUpdateFromAccelMag() {
        if (!hasAccel || !hasMag) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastAccelNs > STALE_NS || now - lastMagNs > STALE_NS) return

        val ok = SensorManager.getRotationMatrix(rotationMatrix, orientationMatrix, accel, mag)
        if (!ok) return
        QuatMath.fromRotationMatrix(rawQuat, rotationMatrix)
        System.arraycopy(rawQuat, 0, accelMagQuat, 0, 4)

        val rvFresh = lastRotationVectorNs > 0L &&
            now - lastRotationVectorNs < ROTATION_VECTOR_PREFERRED_NS
        // 窗口持续够久（说明旋转矢量一直没转）+ 这段时间里加速度计+磁力计却动了
        val rvFrozen = rvFresh &&
            rvWindowStartNs > 0L &&
            now - rvWindowStartNs > RV_FROZEN_NS &&
            angleBetween(accelMagAtWindowStart, accelMagQuat) > RV_DISAGREE_RAD

        if (rvFresh && !rvFrozen) return // 旋转矢量正常，继续由它主导

        if (rvFrozen && !useAccelMag) {
            Log.i(TAG, "旋转矢量疑似卡死（窗口内累计仅转动 ${rvIdleRad} rad），改用加速度计+磁力计")
        }
        useAccelMag = true
        smoothTo(rawQuat)
        if (!manualEnabled) {
            mode = Mode.SENSOR_FUSION
            hasSensor = true
        }
    }

    /** 两个单位四元数之间的夹角（弧度） */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val dot = kotlin.math.abs(
            a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        )
        return 2f * kotlin.math.acos(dot.coerceIn(-1f, 1f))
    }

    // ------------------------------------------------------------------ 对外输出

    /**
     * 渲染线程每帧调用：返回「世界->设备」旋转矩阵（行主序 3x3）。
     */
    override fun worldToDevice(out9: FloatArray): FloatArray {
        if (manualEnabled) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - autoOrbitStartNs) / 1_000_000_000.0
            val azimuth: Float
            val elevation: Float
            if (autoOrbit) {
                // 捕获粒度是「面」：覆盖只取决于水平方位角（镜头正对哪个面就拍下哪个面），
                // 俯仰不影响覆盖。所以巡航只要绕物体水平转一圈，就能拍满 8 个面 ——
                // 正好对应 HUD 的提示语「绕物体旋转一周」。
                azimuth = autoOrbitBaseAzimuth +
                    (elapsed / AUTO_ORBIT_SECONDS * 360.0).toFloat()
                elevation = AUTO_ORBIT_ELEVATION
            } else {
                azimuth = azimuthDeg
                elevation = elevationDeg
            }
            QuatMath.cameraOrbitToWorldToDevice(
                out9,
                Math.toRadians(azimuth.toDouble()).toFloat(),
                Math.toRadians(elevation.coerceIn(-85f, 85f).toDouble()).toFloat()
            )
            return out9
        }

        // 传感器路径：四元数（设备->世界）转置得到 世界->设备
        synchronized(smoothedQuat) {
            System.arraycopy(smoothedQuat, 0, renderQuat, 0, 4)
        }
        QuatMath.toRotationMatrix(deviceToWorld, renderQuat)
        QuatMath.transpose3(worldToDevice, deviceToWorld)
        System.arraycopy(worldToDevice, 0, out9, 0, 9)
        return out9
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    /** 兜底接管：设备确实没有可用的方向传感器时由界面调用 */
    fun forceManualMode() {
        captureManualStart()
        manualByFallback = true
        mode = Mode.MANUAL
    }

    /**
     * 兜底模式的自愈检查，可以由界面反复调用。
     *
     * 传感器冷启动时首个采样可能晚到好几秒（部分 ROM 的省电策略会拖后腿），
     * 用一次性定时器很容易把"还没启动好"误判成"没有传感器"，而且一旦进入
     * 手动模式就再也回不来。这里做成周期检查：只要传感器真的恢复上报，
     * 就自动交还主导权。用户主动拖拽 / 巡航进来的手动模式不受影响。
     */
    fun releaseFallbackIfSensorRecovered() {
        if (!manualByFallback || autoOrbit) return
        if (!hasSensor || !isSensorFresh()) return
        manualByFallback = false
        manualEnabled = false
        mode = Mode.SENSOR
        Log.i(TAG, "传感器已恢复上报，退出手动兜底")
    }

    companion object {
        private const val TAG = "OrientationTracker"

        /** slerp 平滑系数，越小越顺滑但延迟越大 */
        private const val SMOOTHING = 0.32f

        /** 快速转动时使用的平滑系数，几乎无延迟 */
        private const val FAST_SMOOTHING = 0.72f

        /** 超过这个变化量（1-|dot| ≈ 3.6°）就认为用户在快速转动 */
        private const val FAST_SMOOTH_TRIGGER = 2.0e-3f

        /** 拖拽灵敏度：每像素转动多少度 */
        private const val DRAG_DEG_PER_PX = 0.32f

        /** 自动巡航：绕物体水平转完一圈（360°）所需时间（秒），一圈即可覆盖全部 8 个面 */
        private const val AUTO_ORBIT_SECONDS = 16.0

        /** 自动巡航的固定俯仰角（度）：近水平环绕，略俯视便于看清物体顶部 */
        private const val AUTO_ORBIT_ELEVATION = 8f

        private const val ROTATION_VECTOR_PREFERRED_NS = 400_000_000L
        private const val STALE_NS = 500_000_000L
        private const val FRESH_NS = 1_500_000_000L

        /**
         * 旋转矢量"卡死"观测窗口需要持续这么久。
         * 注意判据不是"多久没变化" —— 用户把手机端稳不动本来就是常态。
         */
        private const val RV_FROZEN_NS = 2_500_000_000L

        /** 窗口内旋转矢量累计转动量低于此值（约 0.5°），才算"它自己没转" */
        private const val RV_FROZEN_RAD = 0.0087f

        /** 同一窗口内加速度计+磁力计的姿态变化超过此值（约 8°），才算"设备其实在动" */
        private const val RV_DISAGREE_RAD = 0.14f

        /** 交还主导权之前，旋转矢量需要累计转动这么多（约 10°），避免两路信源反复争夺 */
        private const val RV_RECOVER_RAD = 0.175f
    }
}

package com.remy.guidesphere.scan

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 扫描会话状态机：维护「哪些面已经拍摄过」。
 *
 * 捕获粒度 = 「面」（不是「点」）
 *  ------------------------------
 * 球面按方位切成 8 个 45° 的竖直面（每面 3 列 × 7 行 = 21 个光点）。**一次拍摄 = 一个面**：
 * 镜头正对某个面并稳定停留（自动采样）/ 按下快门（手动），该面的 21 个点就一起点亮。
 * 于是绕物体转一圈只需 **8 次拍摄**，而不是对着 168 个点逐个拍。
 *
 * 空间约定
 *  --------
 * 渲染时我们把被摄物体固定在原点，相机始终保持在其前方 d 处，因此「世界坐标系」与
 * 「设备坐标系」的朝向是重合的（视图矩阵只含平移）。球被施加 R^T（世界->设备）旋转，
 * 于是光点在设备坐标系下的方向 = R^T * n。
 *
 * 「现在正对着哪个面」由相机朝向直接解出：worldToDevice 第 3 行 (m6, m7, m8) 是相机背向
 * 在世界系的分量，也就是「物体 -> 相机」的方向，其方位角 atan2(m7, m6) 恰好等于正对镜头
 * 那片光点的方位角；除以 45° 即得 [camSector]。
 *
 * 全部计算都在渲染线程完成，零分配（复用预分配数组），保证不产生 GC 抖动。
 * 每帧的工作量是 O(光点数 + 扇区数)，其中光点遍历只走一次（状态更新与动画合并）。
 */
class ScanSession(private val dirs: FloatArray) {

    val count: Int = dirs.size / 3

    // ------------------------------------------------------------ 逐点状态
    // 这三个数组是渲染线程唯一读取的状态，直接按顺序喂给 VBO。

    /** 已点亮程度 0..1，用于颜色/亮度插值 */
    val litAmount = FloatArray(count)

    /** 点亮瞬间的脉冲 0..1，用于放大与高光 */
    val pulse = FloatArray(count)

    /** “下一个建议拍摄角度”的高亮程度 0..1 */
    val hintAmount = FloatArray(count)

    /** 逻辑上的「这个点拍过了吗」。与 [litAmount] 分开：后者是渐显动画，前者是立即翻转的真值。 */
    private val isLit = BooleanArray(count)

    /** 已经拍摄到的光点数量 */
    var litCount: Int = 0
        private set

    /** 覆盖度 0..1 */
    var coverage: Float = 0f
        private set

    /** 当前正对的那个面里还剩几个点没点亮（供 HUD / logcat 观测） */
    var inFrameUnlit: Int = 0
        private set

    /** 最近一次拍摄新增了多少个光点（= 一个面的点数，供「已捕获 +N」提示） */
    var lastBatch: Int = 0
        private set

    /** 批次提示的衰减量 0..1 */
    var batchFlash: Float = 0f
        private set

    // ------------------------------------------------------------ 方位扇区
    // 光点在「世界」坐标系下的方向即 dirs（覆盖判定直接用 worldToDevice 作用其上，没有额外的
    // 物体旋转），世界竖直轴为 Z（见 QuatMath 注释），故水平面 = (x, y)。因此一个光点属于哪个
    // 方位扇区要用 atan2(ny, nx) 求（与世界水平面一致），不能误用几何自身的极轴 (x, z)——
    // 否则扇区会整体错开 90°。
    //
    // dirs 恒定不变，所以「光点 -> 扇区」的映射完全静态：init 里用 CSR（压缩行存储）预计算一次，
    // 运行期只做查表，零运行时开销、零分配。

    /** CSR 行偏移：[sectorStart[s], sectorStart[s+1]) 是扇区 s 的光点下标区间 */
    private val sectorStart = IntArray(SECTOR_COUNT + 1)

    /** CSR 列索引：某扇区包含的光点下标 */
    private val sectorDots = IntArray(count)

    /** 每个扇区包含多少个光点 */
    private val sectorDotCount = IntArray(SECTOR_COUNT)

    /** 每个光点所属的扇区（反向查表，供「目标扇区整面高亮」直接判定） */
    private val sectorOfDot = IntArray(count)

    /** 每个扇区已点亮的光点数（增量维护，避免每帧扫描 168 个点） */
    private val sectorLitCount = IntArray(SECTOR_COUNT)

    /** 每扇区已点亮比例 0..1 */
    val sectorProgress = FloatArray(SECTOR_COUNT)

    /** 扇区是否已完成（达标后锁定，不回退） */
    val sectorComplete = BooleanArray(SECTOR_COUNT)

    /** 每个面「被镜头正对」的累计停留时长（秒），用于「对准即拍」的确认 */
    private val sectorDwell = FloatArray(SECTOR_COUNT)

    /** 扇区全亮后还需连续保持多久才算完成（抵消边界点的闪烁） */
    private val confirmTimer = FloatArray(SECTOR_COUNT)

    /** 相机背向在世界系的方位角（度），做了角度环绕低通 */
    var camAzDeg: Float = 0f
        private set

    /** 相机当前正对着哪个扇区 */
    var camSector: Int = 0
        private set

    /** 当前建议采集的目标扇区（固定顺时针顺序推进，只增不减） */
    var targetSector: Int = 0
        private set

    /** 已完成扇区数量 */
    var doneCount: Int = 0
        private set

    // ------------------------------------------------------------ 方向指示
    // HUD 用它画「该往哪边转」的箭头：目标面中心方向在**设备坐标系**下的水平分量。
    // +x = 屏幕右、+y = 屏幕上，所以箭头直接按 (nextDirX, -nextDirY) 画即可。

    var nextDirX: Float = 0f
        private set
    var nextDirY: Float = 0f
        private set

    /** 是否还有未采集的面（false 时 HUD 不画方向箭头） */
    var hasTarget: Boolean = false
        private set

    init {
        // 一次性建立「光点 -> 扇区」的 CSR 映射
        val counts = IntArray(SECTOR_COUNT)
        val ofDot = IntArray(count)
        for (i in 0 until count) {
            val s = sectorOfAz(
                Math.toDegrees(atan2(dirs[i * 3 + 1].toDouble(), dirs[i * 3].toDouble())).toFloat()
            )
            ofDot[i] = s
            counts[s]++
        }
        var acc = 0
        for (s in 0 until SECTOR_COUNT) {
            sectorStart[s] = acc
            acc += counts[s]
        }
        sectorStart[SECTOR_COUNT] = acc

        val cursor = sectorStart.copyOf()
        for (i in 0 until count) {
            val s = ofDot[i]
            sectorDots[cursor[s]++] = i
            sectorOfDot[i] = s
        }
        counts.copyInto(sectorDotCount)
    }

    fun reset() {
        isLit.fill(false)
        litAmount.fill(0f)
        pulse.fill(0f)
        hintAmount.fill(0f)
        litCount = 0
        coverage = 0f
        inFrameUnlit = 0
        lastBatch = 0
        batchFlash = 0f
        sectorProgress.fill(0f)
        sectorComplete.fill(false)
        sectorLitCount.fill(0)
        sectorDwell.fill(0f)
        confirmTimer.fill(0f)
        camAzDeg = 0f
        camSector = 0
        targetSector = 0
        doneCount = 0
        nextDirX = 0f
        nextDirY = 0f
        hasTarget = false
    }

    /**
     * 推进一帧。
     *
     * 捕获粒度是「面」而不是「点」：镜头正对某个面（[camSector]）并稳定停留、或按下快门，
     * 该面的全部光点就一起点亮。因此这里**不需要**任何视锥 / 取景框参数 ——
     * 覆盖判定只依赖相机朝向，与相机内参无关。
     *
     * @param dt             帧间隔（秒）
     * @param worldToDevice  行主序 3x3，世界->设备旋转矩阵
     * @param autoCapture    自动采样模式：镜头正对一个面并稳定停留即整面记为已拍摄
     * @param shutterPressed 本帧是否按下了快门（按下即把当前正对的面整面记为已拍摄）
     */
    fun update(
        dt: Float,
        worldToDevice: FloatArray,
        autoCapture: Boolean,
        shutterPressed: Boolean
    ) {
        val m0 = worldToDevice[0]; val m1 = worldToDevice[1]
        val m3 = worldToDevice[3]; val m4 = worldToDevice[4]
        val m6 = worldToDevice[6]; val m7 = worldToDevice[7]; val m8 = worldToDevice[8]

        // ---- 先解出「相机此刻正对着哪个面」 ----
        val elDeg = Math.toDegrees(asin(m8.coerceIn(-1f, 1f).toDouble())).toFloat()
        if (abs(elDeg) < AZ_FREEZE_ELEV) {
            // 俯仰接近 ±90° 时水平投影退化、atan2 剧烈抖动 —— 冻结上一帧方位角。
            val rawAz = Math.toDegrees(atan2(m7.toDouble(), m6.toDouble())).toFloat()
            // 角度环绕低通（走短弧），避免 359°→1° 跳变。
            camAzDeg = lerpAngle(camAzDeg, rawAz, (1f - exp(-dt * AZ_LP_ALPHA_SEC)).toFloat())
        }
        camSector = sectorOfAz(camAzDeg)

        // ---- 一次拍摄 = 一个面 ----
        // 不再逐点判定「这个点是否落在取景框内」：那样用户得对着 168 个点一个个拍，拍摄次数太多。
        // 这里把捕获粒度提到「面」——镜头正对该面并稳定停留（自动采样）或按下快门（手动），
        // 该面的全部光点一次性点亮，一个面只算一次拍摄（共 8 次）。
        var newly = 0
        for (s in 0 until SECTOR_COUNT) {
            if (sectorComplete[s]) {
                sectorDwell[s] = 0f
                continue
            }
            if (s != camSector) {
                // 镜头已经转开：未满的停留计时按 3 倍速回落，避免「来回扫一眼」就点亮
                sectorDwell[s] = (sectorDwell[s] - dt * 3f).coerceAtLeast(0f)
                continue
            }
            if (shutterPressed) {
                newly += lightSector(s)
                sectorDwell[s] = 0f
            } else if (autoCapture) {
                sectorDwell[s] += dt
                if (sectorDwell[s] >= FACE_DWELL_SECONDS) {
                    newly += lightSector(s)
                    sectorDwell[s] = 0f
                }
            }
        }

        if (newly > 0) {
            litCount += newly
            lastBatch = newly
            batchFlash = 1f
        }
        coverage = if (count == 0) 0f else litCount.toFloat() / count
        inFrameUnlit = sectorDotCount[camSector] - sectorLitCount[camSector]

        // ---- 完成确认（带延迟，抵消边界点闪烁导致的状态抖动） ----
        var done = 0
        for (s in 0 until SECTOR_COUNT) {
            val n = sectorDotCount[s]
            val p = if (n > 0) sectorLitCount[s].toFloat() / n else 0f
            sectorProgress[s] = p
            if (p >= 1f) {
                confirmTimer[s] += dt
                if (!sectorComplete[s] && confirmTimer[s] >= CONFIRM_SECONDS) sectorComplete[s] = true
            } else if (!sectorComplete[s]) {
                confirmTimer[s] = 0f
            }
            if (sectorComplete[s]) done++
        }
        doneCount = done

        // 目标扇区：固定顺时针顺序推进，只增不减；当前目标已完成则顺时针找下一个未完成的。
        var guard = 0
        while (sectorComplete[targetSector] && guard++ < SECTOR_COUNT) {
            targetSector = (targetSector + 1) % SECTOR_COUNT
        }
        val targetOpen = !sectorComplete[targetSector]

        // ---- 目标方向：把目标面中心的世界方向转到设备坐标系 ----
        // 世界水平面是 (x, y)、竖直轴是 Z，所以目标面中心方向是 (cos az, sin az, 0)；
        // 左乘 worldToDevice 得到的 (x, y) 就是它在屏幕平面上的指向——HUD 据此画箭头。
        if (targetOpen) {
            val azRad = Math.toRadians(((targetSector + 0.5f) * DEG_PER_SECTOR).toDouble())
            val wx = cos(azRad).toFloat()
            val wy = sin(azRad).toFloat()
            nextDirX = m0 * wx + m1 * wy
            nextDirY = m3 * wx + m4 * wy
        } else {
            nextDirX = 0f
            nextDirY = 0f
        }
        hasTarget = targetOpen

        // ---- 状态更新 + 动画推进（合并为一次遍历，168 个点只扫一遍） ----
        val litStep = dt / LIT_ANIM_SECONDS
        val pulseStep = dt / PULSE_SECONDS
        val hintLerp = min(1f, dt * HINT_LERP_RATE)
        for (i in 0 until count) {
            // 目标面内「尚未拍摄」的光点整面高亮（琥珀呼吸），让球面以「面」为单位告诉用户
            // 「接下来该转去扫这一整片」，而不是只标一个孤立的点。
            val hintGoal = if (targetOpen && !isLit[i] && sectorOfDot[i] == targetSector) 1f else 0f
            hintAmount[i] += (hintGoal - hintAmount[i]) * hintLerp

            val goal = if (isLit[i]) 1f else 0f
            val cur = litAmount[i]
            litAmount[i] = if (cur < goal) min(1f, cur + litStep) else goal

            if (pulse[i] > 0f) pulse[i] = (pulse[i] - pulseStep).coerceAtLeast(0f)
        }

        if (batchFlash > 0f) batchFlash = (batchFlash - dt / BATCH_FLASH_SECONDS).coerceAtLeast(0f)
    }

    /**
     * 把一个面的全部光点一次性点亮（= 「一次拍摄拍下这一面」），返回本次新点亮的点数。
     * 整面同时点亮，所以一个面只会产生一次「已捕获」脉冲，而不是 21 个点各闪一次。
     */
    private fun lightSector(s: Int): Int {
        var n = 0
        for (k in sectorStart[s] until sectorStart[s + 1]) {
            val i = sectorDots[k]
            if (!isLit[i]) {
                isLit[i] = true
                pulse[i] = 1f
                n++
            }
        }
        sectorLitCount[s] += n
        return n
    }

    /** 方位角（度，任意实数）-> 扇区下标，自动做 360° 环绕并夹取到合法区间 */
    private fun sectorOfAz(azDeg: Float): Int {
        val a = ((azDeg % 360f) + 360f) % 360f
        return (a / DEG_PER_SECTOR).toInt().coerceIn(0, SECTOR_COUNT - 1)
    }

    companion object {
        /** 方位扇区数量：360° / 8 = 每 45° 一段 */
        const val SECTOR_COUNT = 8

        /** 每段角度（度） */
        const val DEG_PER_SECTOR = 360f / SECTOR_COUNT

        /** 扇区“全部点亮”需连续保持多久才确认完成（抵消边界点闪烁） */
        private const val CONFIRM_SECONDS = 0.4f

        /** 方位角低通系数（越大跟得越紧） */
        private const val AZ_LP_ALPHA_SEC = 8f

        /** 俯仰角超过此值（度）时水平投影退化，冻结方位角 */
        private const val AZ_FREEZE_ELEV = 87f

        /** 自动模式下，镜头正对一个面并稳定停留多久才把这一面记为已拍摄 */
        private const val FACE_DWELL_SECONDS = 0.22f

        /** 已点亮程度的渐显时长（秒） */
        private const val LIT_ANIM_SECONDS = 0.22f

        /** 点亮脉冲的衰减时长（秒） */
        private const val PULSE_SECONDS = 0.45f

        /** 目标面高亮的跟随速率（1/秒） */
        private const val HINT_LERP_RATE = 7f

        /** 「已捕获 +N」提示的保持时长（秒） */
        private const val BATCH_FLASH_SECONDS = 0.9f

        /** 角度环绕线性插值：走短弧，避免 359°→1° 跳变 */
        private fun lerpAngle(a: Float, b: Float, t: Float): Float {
            var diff = (b - a) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            return a + diff * t
        }
    }
}

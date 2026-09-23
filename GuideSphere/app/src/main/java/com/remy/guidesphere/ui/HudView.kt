package com.remy.guidesphere.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.remy.guidesphere.gl.GuideSphereRenderer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 引导界面（HUD）。
 *
 * 全部用 Canvas 直接绘制，不依赖任何 UI 框架：
 *  - 引导球缩小后停靠在画面下方，用一个圆角面板框起来，并标注「物体」——
 *    中间的整片区域留给真实相机画面，用户能看清自己正在拍什么；
 *  - 覆盖度进度环做成 **8 段**，一段就是一个待采集的面，完成一段亮一段。
 *    外圈还有一段更细的弧，表示「镜头正对的那个面已经对准多久」；
 *  - 四角取景框标示拍摄范围，全部完成时转为青绿；
 *  - 环上的琥珀色箭头 + 中央的方向文案（「向右转」/「抬高手机」）告诉用户手机该往哪边动；
 *  - 快门 / 自动采样切换 / 重置 / 自动巡航，都是自绘的可点击控件；
 *  - 首次启动有一段三页的引导，点按推进、可跳过；
 *  - 每次整面采集、以及全部完成时给出触觉反馈。
 *
 * 交互上有两处刻意的「慢一拍」设计：
 *  - 「重置」需要连点两次确认（第一次变成「确认重置」），避免辛苦扫完一半被误触清空；
 *  - 采集完成后的横幅本身可点，点它即重新开始。
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** 按下快门 */
        fun onShutter()

        /** 切换自动采样 / 手动快门，返回切换后的状态 */
        fun onToggleAutoCapture(): Boolean

        /** 重置覆盖数据 */
        fun onReset()

        /** 切换自动巡航，返回切换后的状态 */
        fun onToggleAutoOrbit(): Boolean

        /** 请求相机权限 */
        fun onRequestPermission()

        /** 在非控件区域拖动 */
        fun onDrag(dx: Float, dy: Float)

        /** 首次引导已看完（或跳过），可以正式开始扫描了 */
        fun onOnboardingFinished()
    }

    var listener: Listener? = null

    // ------------------------------------------------------------------ 状态

    private val d = resources.displayMetrics.density

    /** 1sp 对应多少像素（不直接读已废弃的 scaledDensity，改用官方推荐写法） */
    private val scaledDensity =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics
        )

    private val haptics = Haptics(context)

    private var coverageTarget = 0f
    private var coverageAnim = 0f
    private var lit = 0
    private var fps = 0
    private var drawMs = 0f
    private var sphereRadiusPx = 0f

    /** 球心在屏幕上的位置（像素），由渲染线程回传 —— 渲染器做过投影平移，不能硬写画面中心 */
    private var sphereCenterXPx = 0f
    private var sphereCenterYPx = 0f

    /** 下一个建议面在**设备坐标系**下的水平方向，用来画"该往哪边转"的箭头与文案 */
    private var nextX = 0f
    private var nextY = 0f
    private var hasTarget = false

    /** 已完成的面数（来自渲染线程 Stats，驱动中央文字与左上角面板） */
    private var doneCount = 0

    /** 已完成面的位掩码，驱动 8 段进度环 */
    private var sectorDoneMask = 0

    /** 镜头当前正对哪个面 / 建议采集哪个面 */
    private var camSector = 0
    private var targetSector = 0

    /** 镜头正对的那个面「已对准多久」0..1 */
    private var camDwell = 0f

    /** 8 段进度环的填充动画 0..1（目标值就是位掩码里的 0/1，逐帧追赶） */
    private val segFill = FloatArray(SECTOR_COUNT)

    private var autoCapture = true
    private var autoOrbit = false
    private var sourceLabel = "传感器"
    private var sensorFresh = false
    private var cameraReady = false
    private var permissionGranted = true
    private var hasCamera = true

    // 动画辅助
    private var lastFrameMs = 0L
    private var batchValue = 0
    private var batchStartMs = 0L
    private var shutterFlashMs = 0L
    private var bannerStartMs = 0L
    private var bannerShown = false

    /** 上一次看到的 doneCount，用于检测「刚刚全部完成」并给出庆祝反馈 */
    private var lastDoneCount = 0

    /**
     * 启动瞬间的那次自动采集不算用户操作，不震。
     * App 一打开镜头正对的面停留 0.22s 就会自动记一面（固有行为），
     * 此时用户什么都还没做，震一下只会让人困惑。
     */
    private var firstCaptureEventSkipped = false

    // ---- 重置防误触：第一次点只是"上膛" ----
    private var resetArmed = false
    private var resetArmedMs = 0L

    // ---- 首次引导 ----
    /** -1 = 不显示；0..SIZE-1 = 当前页 */
    private var onboardingStep = -1
    private var pressedOnboardSkip = false

    // 触摸状态
    private var pressedShutter = false
    private var pressedMode = false
    private var pressedReset = false
    private var pressedCruise = false
    private var pressedPermission = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false

    /** 点按即可「重新扫描」的区域（采集完成后的横幅），每帧由 drawBanner 写入 */
    private val bannerHitRect = RectF()

    /** 引导页「跳过」按钮的点击区，每帧由 drawOnboarding 写入 */
    private val onboardSkipRect = RectF()

    // 画笔
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val rect = RectF()
    private val ringRect = RectF()
    private val pillRect = RectF()
    private val path = Path()

    // 球体的屏幕几何：渲染器与 HUD 共用同一个锚点常量，永远不会错位
    private val sphereCx: Float
        get() = if (sphereCenterXPx > 0f) sphereCenterXPx else w * 0.5f

    private val sphereCy: Float
        get() = if (sphereCenterYPx > 0f) sphereCenterYPx else h * GuideSphereRenderer.SPHERE_ANCHOR_Y

    private val ringRadius: Float
        get() = (if (sphereRadiusPx > 0f) sphereRadiusPx else h * 0.085f) * RING_SCALE

    // 布局
    private var w = 0
    private var h = 0
    private var pad = 0f
    private var shutterCx = 0f
    private var shutterCy = 0f
    private var shutterR = 0f
    private var modeRect = RectF()
    private var resetRect = RectF()
    private var cruiseRect = RectF()
    private var permissionRect = RectF()

    // 颜色
    private val cPanel = Color.parseColor("#C20A1020")
    private val cPanelStroke = Color.parseColor("#33FFFFFF")
    private val cAccent = Color.parseColor("#4DE1FF")
    private val cAccentDim = Color.parseColor("#664DE1FF")
    private val cLit = Color.parseColor("#3BF0B0")
    private val cWarn = Color.parseColor("#FFC061")
    private val cText = Color.parseColor("#F2F7FF")
    private val cTextDim = Color.parseColor("#A8BBD4")

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.w = w
        this.h = h
        pad = 16f * d

        shutterR = 34f * d
        shutterCx = w * 0.5f
        shutterCy = h - pad - shutterR - 12f * d

        val modeW = 104f * d
        val modeH = 36f * d
        modeRect.set(pad, shutterCy - modeH * 0.5f, pad + modeW, shutterCy + modeH * 0.5f)

        val resetW = 82f * d
        resetRect.set(w - pad - resetW, shutterCy - modeH * 0.5f, w - pad, shutterCy + modeH * 0.5f)

        val cruiseW = 116f * d
        val cruiseH = 32f * d
        // 排在右上角三枚状态芯片的正下方，避免重叠
        val cruiseTop = pad + 3f * (26f * d + 6f * d)
        cruiseRect.set(w - pad - cruiseW, cruiseTop, w - pad, cruiseTop + cruiseH)

        val pW = 190f * d
        val pH = 36f * d
        permissionRect.set(w * 0.5f - pW * 0.5f, h * 0.5f + 30f * d, w * 0.5f + pW * 0.5f, h * 0.5f + 30f * d + pH)
    }

    // ------------------------------------------------------------------ 外部更新

    /**
     * 开启首次使用引导。
     *
     * 引导期间由 [MainActivity] 暂停自动采集，所以看完之后进度是从真正的 0 开始的，
     * 而不是"一打开就已经拍了 1/8"。
     */
    fun startOnboarding() {
        onboardingStep = 0
        postInvalidateOnAnimation()
    }

    val isOnboarding: Boolean get() = onboardingStep >= 0

    fun updateState(
        stats: GuideSphereRenderer.Stats,
        autoCapture: Boolean,
        autoOrbit: Boolean,
        sourceLabel: String,
        sensorFresh: Boolean,
        cameraReady: Boolean,
        permissionGranted: Boolean,
        hasCamera: Boolean
    ) {
        this.coverageTarget = stats.coverage
        this.lit = stats.lit
        this.fps = stats.fps
        this.drawMs = stats.drawMs
        if (stats.sphereRadiusPx > 0f) this.sphereRadiusPx = stats.sphereRadiusPx
        if (stats.sphereCenterYPx > 0f) {
            this.sphereCenterXPx = stats.sphereCenterXPx
            this.sphereCenterYPx = stats.sphereCenterYPx
        }
        this.nextX = stats.nextX
        this.nextY = stats.nextY
        this.hasTarget = stats.hasTarget
        this.doneCount = stats.doneCount
        this.sectorDoneMask = stats.sectorDoneMask
        this.camSector = stats.camSector
        this.targetSector = stats.targetSector
        this.camDwell = stats.camFaceProgress
        this.autoCapture = autoCapture
        this.autoOrbit = autoOrbit
        this.sourceLabel = sourceLabel
        this.sensorFresh = sensorFresh
        this.cameraReady = cameraReady
        this.permissionGranted = permissionGranted
        this.hasCamera = hasCamera

        // 检测「刚刚拍下一个面」：batchFlash 只在批次产生的头 1~2 次采样里高于 0.85，
        // 天然就是一次性事件，不会重复触发。
        if (stats.lastBatch > 0 && stats.batchFlash > 0.85f) {
            batchValue = stats.lastBatch
            batchStartMs = SystemClock.elapsedRealtime()
            if (!firstCaptureEventSkipped) {
                firstCaptureEventSkipped = true          // 启动瞬间那次自动采集不震
            } else if (onboardingStep < 0) {
                haptics.faceCaptured()
            }
        }

        if (stats.doneCount > lastDoneCount && stats.doneCount >= SECTOR_COUNT) {
            if (onboardingStep < 0) haptics.allDone()
        }
        lastDoneCount = stats.doneCount

        if (coverageTarget >= 0.999f) {
            if (!bannerShown) {
                bannerShown = true
                bannerStartMs = SystemClock.elapsedRealtime()
            }
        } else {
            bannerShown = false
        }
        postInvalidateOnAnimation()
    }

    fun notifyShutter() {
        shutterFlashMs = SystemClock.elapsedRealtime()
        haptics.tick()
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (w == 0 || h == 0) return

        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.05f)
        lastFrameMs = now

        // 覆盖度平滑跟随
        val diff = coverageTarget - coverageAnim
        var animating = false
        if (abs(diff) > 0.0005f) {
            coverageAnim += diff * min(1f, dt * 8f)
            animating = true
        } else {
            coverageAnim = coverageTarget
        }

        // 8 段进度环各自的填充动画
        for (s in 0 until SECTOR_COUNT) {
            val goal = if ((sectorDoneMask shr s) and 1 == 1) 1f else 0f
            val cur = segFill[s]
            if (abs(goal - cur) > 0.004f) {
                segFill[s] = cur + (goal - cur) * min(1f, dt * 10f)
                animating = true
            } else {
                segFill[s] = goal
            }
        }

        // 「确认重置」的自动撤防
        if (resetArmed && now - resetArmedMs > RESET_ARM_MS) {
            resetArmed = false
            animating = true
        }

        drawCaptureBrackets(canvas)
        // 中央引导文字（替换原 8 段扇形环）：提示用户绕物体旋转一周
        drawCenterGuideText(canvas)
        // 停靠面板先画，进度环与方向箭头压在它上面
        drawSphereDock(canvas)
        drawCoverageRing(canvas)
        drawNextDirection(canvas)
        drawTopPanel(canvas)
        drawRightChips(canvas)
        drawBottomBar(canvas)
        drawBatchToast(canvas, now)
        drawShutterFlash(canvas, now)
        drawBanner(canvas, now)

        // 引导页盖在所有内容之上，但相机权限遮罩优先级更高
        if (onboardingStep >= 0 && permissionGranted && hasCamera) drawOnboarding(canvas)

        if (!permissionGranted || !hasCamera) drawPermissionOverlay(canvas)

        // 一次性决定本帧结束后要不要继续重绘：覆盖度还在平滑、环形还在追、或横幅/提示还在动。
        // （drawBatchToast / drawShutterFlash / drawBanner 内部不再各自重复调用。）
        if (animating || bannerShown || batchValue > 0 || shutterFlashMs != 0L || resetArmed) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 四角取景框：把被摄物体框在画面里，提示"拍的就是中间这个物体"。
     * 捕获粒度是「面」，判定只看相机朝向、与取景框无关，所以这里纯粹是视觉引导；
     * 全部扫完后转成青绿，作为"框里这个东西已经拍齐了"的收尾暗示。
     */
    private fun drawCaptureBrackets(canvas: Canvas) {
        // 距离屏幕边缘留一条窄边，避免贴边被系统手势区吃掉
        val inset = 0.025f
        val left = w * inset
        val right = w * (1f - inset)
        val top = h * inset
        val bottom = h * (1f - inset)
        val len = 26f * d
        val r = 6f * d

        paint.reset()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = when {
            coverageTarget >= 0.999f -> cLit
            autoCapture -> cAccentDim
            else -> Color.parseColor("#59FFC061")
        }

        path.reset()
        // 左上
        path.moveTo(left, top + len); path.lineTo(left, top + r)
        path.quadTo(left, top, left + r, top); path.lineTo(left + len, top)
        // 右上
        path.moveTo(right - len, top); path.lineTo(right - r, top)
        path.quadTo(right, top, right, top + r); path.lineTo(right, top + len)
        // 右下
        path.moveTo(right, bottom - len); path.lineTo(right, bottom - r)
        path.quadTo(right, bottom, right - r, bottom); path.lineTo(right - len, bottom)
        // 左下
        path.moveTo(left + len, bottom); path.lineTo(left + r, bottom)
        path.quadTo(left, bottom, left, bottom - r); path.lineTo(left, bottom - len)
        canvas.drawPath(path, paint)
    }

    /**
     * 屏幕中央引导文字。
     *
     * 文案随状态变化，而不是从头到尾一句「绕物体旋转一周」：
     *  - 还没开始   → 告诉用户把物体放进框里、缓慢环绕；
     *  - 对准中     → 「已对准 · 保持」，配合进度环外圈那段小弧，让"马上就要拍到了"可见；
     *  - 有明确目标 → 直接给出方向词（向右转 / 向左转 / 抬高手机），比一个箭头更好懂；
     *  - 全部完成   → 「采集完成」，点按横幅即可重扫。
     */
    private fun drawCenterGuideText(canvas: Canvas) {
        val cx = w * 0.5f
        val cy = h * CENTER_GUIDE_Y

        val done = doneCount
        val allDone = done >= SECTOR_COUNT

        // 完成态让位给顶部完成横幅，避免互相压字
        if (bannerShown && allDone) {
            boldPaint.textAlign = Paint.Align.LEFT
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        val main: String
        val sub: String
        val mainColor: Int
        when {
            allDone -> {
                main = "采集完成"
                sub = "8 个面全部点亮"
                mainColor = cLit
            }
            camDwell > 0.02f -> {
                main = "已对准 · 保持不动"
                sub = "正在采集这一面 ${(camDwell * 100f).toInt()}%"
                mainColor = cAccent
            }
            hasTarget -> {
                main = dirWord()
                sub = "还剩 ${(SECTOR_COUNT - done).coerceAtLeast(0)} 个面待采集"
                mainColor = cText
            }
            else -> {
                main = "缓慢环绕物体"
                sub = "镜头对准哪一面，那一面就会亮起"
                mainColor = cText
            }
        }

        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 19f * scaledDensity
        boldPaint.color = mainColor
        val base = cy - (boldPaint.descent() + boldPaint.ascent()) * 0.5f
        canvas.drawText(main, cx, base, boldPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 12f * scaledDensity
        textPaint.color = cTextDim
        canvas.drawText(sub, cx, cy + 18f * scaledDensity, textPaint)

        boldPaint.textAlign = Paint.Align.LEFT
        textPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * 由目标方向（设备坐标系）换算成一句人话。
     *
     * +x = 屏幕右、+y = 屏幕上，所以屏幕上方的目标就是"抬高手机"。
     * 阈值取 0.10：接近光轴时不再催，改成"保持不动"，避免用户在手已经在正确位置时瞎调。
     */
    private fun dirWord(): String {
        val t = 0.10f
        return when {
            nextY >= t && nextY >= abs(nextX) -> "抬高手机"
            nextY <= -t && -nextY >= abs(nextX) -> "压低手机"
            nextX > t -> "向右转"
            nextX < -t -> "向左转"
            else -> "保持不动"
        }
    }

    /**
     * 覆盖度进度环：**8 段**，一段 = 一个待采集的面。
     *
     * 换成 8 段之后，环上的信息量和球面的 8 面点阵一一对应 ——
     * 「还差 3 段」和「球上还剩 3 片没亮」是同一件事，用户不用在两种表达之间换算。
     * 环外那一小段更细的弧是"当前这一面已经对准了多少"。
     */
    private fun drawCoverageRing(canvas: Canvas) {
        val r = ringRadius
        val cx = sphereCx
        val cy = sphereCy
        ringRect.set(cx - r, cy - r, cx + r, cy + r)

        val seg = 360f / SECTOR_COUNT
        val gap = 7f

        paint.reset()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // ---- 轨道：8 段暗底 ----
        paint.strokeWidth = 3f * d
        paint.color = Color.parseColor("#2EFFFFFF")
        for (s in 0 until SECTOR_COUNT) {
            canvas.drawArc(ringRect, -90f + s * seg + gap * 0.5f, seg - gap, false, paint)
        }

        // ---- 已完成 / 正在填充的段 ----
        paint.strokeWidth = 5f * d
        for (s in 0 until SECTOR_COUNT) {
            val f = segFill[s]
            if (f <= 0.004f) continue
            paint.color = blend(cAccent, cLit, f)
            canvas.drawArc(ringRect, -90f + s * seg + gap * 0.5f, (seg - gap) * f, false, paint)
        }

        // ---- 目标段：琥珀呼吸，指出"下一段该往哪边补" ----
        val targetOpen = doneCount < SECTOR_COUNT && (sectorDoneMask shr targetSector) and 1 == 0
        if (targetOpen && segFill[targetSector] < 0.5f) {
            val breath = 0.45f + 0.55f * (0.5f + 0.5f * kotlin.math.sin(lastFrameMs / 380.0).toFloat())
            paint.strokeWidth = 3f * d
            paint.color = withAlpha(cWarn, 150f * breath)
            canvas.drawArc(
                ringRect, -90f + targetSector * seg + gap * 0.5f, seg - gap, false, paint
            )
        }

        // ---- 外圈：当前面的「对准进度」 ----
        if (autoCapture && camDwell > 0.01f && doneCount < SECTOR_COUNT) {
            val outer = r + 7f * d
            ringRect.set(cx - outer, cy - outer, cx + outer, cy + outer)
            paint.strokeWidth = 2.5f * d
            paint.color = withAlpha(cAccent, 235f)
            canvas.drawArc(
                ringRect, -90f + camSector * seg + gap * 0.5f, (seg - gap) * camDwell, false, paint
            )
            paint.strokeWidth = 1.5f * d
            paint.color = Color.parseColor("#40FFFFFF")
            canvas.drawArc(
                ringRect, -90f + camSector * seg + gap * 0.5f, seg - gap, false, paint
            )
            ringRect.set(cx - r, cy - r, cx + r, cy + r)
        } else {
            // 末端光点：只在整环视角上给一个"进度头"，段填充本身已经足够表达
            val filled = (0 until SECTOR_COUNT).sumOf { segFill[it].toDouble() }.toFloat() / SECTOR_COUNT
            if (filled > 0.01f) {
                val rad = Math.toRadians((-90f + 360f * filled).toDouble())
                val px = cx + (r * kotlin.math.cos(rad)).toFloat()
                val py = cy + (r * kotlin.math.sin(rad)).toFloat()
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#66FFFFFF")
                canvas.drawCircle(px, py, 6f * d, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(px, py, 3f * d, paint)
            }
        }
    }

    /**
     * 停靠在画面下方的引导球面板。
     *
     * 球缩小之后需要明确它"代表什么"：圆角边框把它框成一个独立的小窗，
     * 下方的「物体」标签点明它就是被拍摄的那个物体，右侧标出当前该做什么。
     * 中间的大片画面因此完全让给了真实相机预览。
     */
    private fun drawSphereDock(canvas: Canvas) {
        val cx = sphereCx
        val cy = sphereCy
        val half = ringRadius + 14f * d
        val corner = 30f * d

        rect.set(cx - half, cy - half, cx + half, cy + half)
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = DOCK_FILL
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        paint.color = DOCK_STROKE
        canvas.drawRoundRect(rect, corner, corner, paint)

        // ---- 左下角「物体」标签 ----
        textPaint.textSize = 12f * scaledDensity
        val iconR = 7f * d
        val labelText = "物体"
        val pillH = 32f * d
        val pillW = iconR * 2f + 9f * d + textPaint.measureText(labelText) + 20f * d
        val pillLeft = rect.left + 8f * d
        val pillTop = rect.bottom + 8f * d
        pillRect.set(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = cPanel
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = (cAccent and 0x00FFFFFF) or 0x66000000
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, paint)

        val iconCx = pillRect.left + 10f * d + iconR
        val iconCy = pillRect.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        paint.color = cAccent
        canvas.drawCircle(iconCx, iconCy, iconR, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(iconCx, iconCy, 2.2f * d, paint)

        textPaint.color = cText
        val ty = pillRect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(labelText, iconCx + iconR + 9f * d, ty, textPaint)

        // ---- 右下角：状态 ----
        // 文案刻意压到 7 个字以内：这一行要和左边的「物体」胶囊并排塞进
        // 球体面板的宽度里，写长了就会互相压字。
        // 注意「框内 N 个」这类旧文案已经不成立了 —— 捕获粒度是「面」，
        // 取景框早就不参与判定，再按"框内还剩几个点"表达会误导用户。
        val status: String
        val statusColor: Int
        when {
            !permissionGranted -> { status = "待授权"; statusColor = cWarn }
            !hasCamera -> { status = "无相机"; statusColor = cWarn }
            coverageTarget >= 0.999f -> { status = "全部覆盖 ✓"; statusColor = cLit }
            camDwell > 0.02f -> {
                status = "采集中 ${(camDwell * 100f).toInt()}%"
                statusColor = cAccent
            }
            hasTarget -> { status = dirWord(); statusColor = cAccent }
            else -> { status = "还差 ${(SECTOR_COUNT - doneCount).coerceAtLeast(0)} 个面"; statusColor = cTextDim }
        }
        textPaint.textSize = 11.5f * scaledDensity
        textPaint.color = statusColor
        canvas.drawText(status, rect.right - 8f * d - textPaint.measureText(status), ty, textPaint)
    }

    /**
     * 环外侧的琥珀色箭头：指出"手机该往哪边转"。
     *
     * 方向取自目标面中心在**设备坐标系**下的水平分量（[nextX] / [nextY]）：+x 屏幕右、
     * +y 屏幕上。目标在画面右侧就往右转、在上方就抬高手机 —— 转过去它就自动被拍到了。
     * 目标已经接近光轴时淡出，避免在即将点亮时抖动。
     */
    private fun drawNextDirection(canvas: Canvas) {
        if (!hasTarget || coverageTarget >= 0.999f) return
        val len = sqrt(nextX * nextX + nextY * nextY)
        if (len < 1e-4f) return
        val dx = nextX / len
        val dy = -nextY / len            // 屏幕 y 轴向下，取反
        val near = min(1f, len / 0.35f)  // 越接近光轴越淡
        if (near <= 0.01f) return

        val cx = sphereCx
        val cy = sphereCy
        val dist = ringRadius + 22f * d
        val px = cx + dx * dist
        val py = cy + dy * dist

        // 箭头外一层柔光，在明亮的相机画面上也能看清
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(cWarn, near * 60f)
        canvas.drawCircle(px, py, 15f * d, paint)

        paint.color = withAlpha(cWarn, near * 235f)

        // 一个指向 (dx, dy) 的实心三角
        val s = 9f * d
        val tipX = px + dx * s * 1.15f
        val tipY = py + dy * s * 1.15f
        val baseX = px - dx * s * 0.55f
        val baseY = py - dy * s * 0.55f
        val nx = -dy * s * 0.85f
        val ny = dx * s * 0.85f
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX + nx, baseY + ny)
        path.lineTo(baseX - nx, baseY - ny)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** 左上角覆盖度面板 */
    private fun drawTopPanel(canvas: Canvas) {
        val panelW = 196f * d
        val panelH = 80f * d
        rect.set(pad, pad, pad + panelW, pad + panelH)
        drawPanel(canvas, rect, 16f * d)

        val x = rect.left + 14f * d
        var y = rect.top + 20f * d

        textPaint.textSize = 11f * scaledDensity
        textPaint.color = cTextDim
        canvas.drawText("360° 覆盖度", x, y, textPaint)

        boldPaint.textSize = 26f * scaledDensity
        boldPaint.color = cText
        val pct = "${(coverageAnim * 100f).toInt()}%"
        canvas.drawText(pct, x, y + 30f * scaledDensity, boldPaint)

        val pctWidth = boldPaint.measureText(pct)
        textPaint.textSize = 11f * scaledDensity
        textPaint.color = cTextDim
        canvas.drawText("已拍 $doneCount / $SECTOR_COUNT 个面", x + pctWidth + 8f * d, y + 30f * scaledDensity, textPaint)

        // 与环形进度条同构的 8 个小方块：一眼看出还缺哪几面，比一根连续进度条信息量大
        val barTop = rect.bottom - 18f * d
        val barLeft = x
        val barRight = rect.right - 14f * d
        val blockGap = 3f * d
        val blockW = (barRight - barLeft - blockGap * (SECTOR_COUNT - 1)) / SECTOR_COUNT
        for (s in 0 until SECTOR_COUNT) {
            val left = barLeft + s * (blockW + blockGap)
            rect.set(left, barTop, left + blockW, barTop + 5f * d)
            paint.reset()
            paint.style = Paint.Style.FILL
            paint.color = if (segFill[s] > 0.004f) {
                blend(cAccent, cLit, segFill[s])
            } else {
                Color.parseColor("#33FFFFFF")
            }
            canvas.drawRoundRect(rect, 2.5f * d, 2.5f * d, paint)
        }
    }

    /** 右上角状态芯片：帧率 / 姿态来源 / 相机 */
    private fun drawRightChips(canvas: Canvas) {
        val chipH = 26f * d
        var y = pad

        val fpsColor = when {
            fps >= 55 -> cLit
            fps >= 45 -> cAccent
            else -> cWarn
        }
        val fpsW = 74f * d
        rect.set(w - pad - fpsW, y, w - pad, y + chipH)
        drawPanel(canvas, rect, chipH * 0.5f)
        drawChipText(canvas, rect, "${fps} FPS  ${"%.1f".format(drawMs)}ms", fpsColor)
        y += chipH + 6f * d

        val srcW = 118f * d
        rect.set(w - pad - srcW, y, w - pad, y + chipH)
        drawPanel(canvas, rect, chipH * 0.5f)
        val srcColor = if (sensorFresh || sourceLabel.contains("巡航")) cAccent else cWarn
        drawChipText(canvas, rect, sourceLabel, srcColor)
        y += chipH + 6f * d

        val camW = 118f * d
        rect.set(w - pad - camW, y, w - pad, y + chipH)
        drawPanel(canvas, rect, chipH * 0.5f)
        drawChipText(
            canvas, rect,
            when {
                !hasCamera -> "无相机硬件"
                !permissionGranted -> "未授权相机"
                cameraReady -> "相机画面 ✓"
                else -> "相机启动中"
            },
            if (cameraReady) cLit else cWarn
        )
    }

    private fun drawChipText(canvas: Canvas, r: RectF, text: String, color: Int) {
        textPaint.textSize = 11f * scaledDensity
        textPaint.color = color
        val ty = r.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(text, r.left + 10f * d, ty, textPaint)
    }

    /** 底部：快门、模式切换、重置、自动巡航 */
    private fun drawBottomBar(canvas: Canvas) {
        // ---- 快门 ----
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#33FFFFFF")
        canvas.drawCircle(shutterCx, shutterCy, shutterR + 5f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = cText
        canvas.drawCircle(shutterCx, shutterCy, shutterR, paint)

        paint.style = Paint.Style.FILL
        val innerR = shutterR * (if (pressedShutter) 0.72f else 0.84f)
        paint.color = if (autoCapture) blend(cAccent, cLit, coverageAnim) else Color.WHITE
        canvas.drawCircle(shutterCx, shutterCy, innerR, paint)

        // 自动采样时快门仍然可点（等于"现在就拍这一面"），文案点明这一点
        textPaint.textSize = 10f * scaledDensity
        textPaint.color = cText
        val label = if (autoCapture) "采样中" else "拍摄"
        canvas.drawText(
            label,
            shutterCx - textPaint.measureText(label) * 0.5f,
            shutterCy + shutterR + 20f * d,
            textPaint
        )

        // ---- 模式切换 ----
        drawPill(
            canvas, modeRect,
            if (autoCapture) "自动采样" else "手动快门",
            if (autoCapture) cLit else cWarn,
            pressedMode
        )

        // ---- 重置：点两次才生效，防误触清空已扫进度 ----
        drawPill(
            canvas, resetRect,
            if (resetArmed) "确认重置" else "重置",
            if (resetArmed) cWarn else cTextDim,
            pressedReset || resetArmed
        )

        // ---- 自动巡航 ----
        drawPill(
            canvas, cruiseRect,
            if (autoOrbit) "巡航中 ●" else "自动巡航",
            if (autoOrbit) cAccent else cTextDim,
            pressedCruise
        )
    }

    private fun drawPill(canvas: Canvas, r: RectF, text: String, accent: Int, pressed: Boolean) {
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) Color.parseColor("#59203048") else cPanel
        canvas.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = (accent and 0x00FFFFFF) or 0x66000000
        canvas.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, paint)

        textPaint.textSize = 12f * scaledDensity
        textPaint.color = accent
        val ty = r.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(text, r.centerX() - textPaint.measureText(text) * 0.5f, ty, textPaint)
    }

    /** "已捕获 +N" 浮动提示 */
    private fun drawBatchToast(canvas: Canvas, now: Long) {
        if (batchValue <= 0) return
        val t = (now - batchStartMs) / 900f
        if (t >= 1f) {
            batchValue = 0
            return
        }
        val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
        val rise = 40f * d * t
        val y = h * 0.5f - (if (sphereRadiusPx > 0f) sphereRadiusPx else h * 0.18f) - 26f * d - rise

        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = (0xC2000000.toInt() and 0x00FFFFFF) or (alpha shl 24)
        val text = "已捕获 +$batchValue"
        textPaint.textSize = 13f * scaledDensity
        val tw = textPaint.measureText(text) + 24f * d
        rect.set(w * 0.5f - tw * 0.5f, y - 26f * d, w * 0.5f + tw * 0.5f, y)
        canvas.drawRoundRect(rect, 13f * d, 13f * d, paint)

        textPaint.color = (cLit and 0x00FFFFFF) or (alpha shl 24)
        canvas.drawText(text, w * 0.5f - textPaint.measureText(text) * 0.5f, y - 9f * d, textPaint)
    }

    private fun drawShutterFlash(canvas: Canvas, now: Long) {
        if (shutterFlashMs == 0L) return
        val t = (now - shutterFlashMs) / 260f
        if (t >= 1f) {
            shutterFlashMs = 0L
            return
        }
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(((1f - t) * 70).toInt().coerceIn(0, 70), 255, 255, 255)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /**
     * 覆盖完成横幅。整块可点 —— 点它就是「再来一轮」，
     * 不必再回到底部找那个需要二次确认的「重置」。
     */
    private fun drawBanner(canvas: Canvas, now: Long) {
        if (!bannerShown) return
        val elapsed = (now - bannerStartMs) / 1000f
        val enter = min(1f, elapsed / 0.35f)
        val pulse = 0.5f + 0.5f * kotlin.math.sin(elapsed * 3.4).toFloat()

        val bw = 250f * d
        val bh = 78f * d
        val cx = w * 0.5f
        // 横条要同时避开右上角的状态芯片列与球体的停靠面板：
        // 优先贴在停靠面板上方（"已捕获 +N" 浮动提示的地盘下面），空间不够时才退到芯片列正下方。
        val chipsBottom = pad + 3f * (26f * d + 6f * d) + 32f * d
        val ringTop = sphereCy - ringRadius
        val minCy = chipsBottom + bh * 0.5f + 10f * d
        val preferred = ringTop - bh * 0.5f - 56f * d
        val cy = maxOf(minCy, preferred) + (1f - easeOut(enter)) * -20f * d
        rect.set(cx - bw * 0.5f, cy - bh * 0.5f, cx + bw * 0.5f, cy + bh * 0.5f)
        bannerHitRect.set(rect)

        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#D9062418")
        canvas.drawRoundRect(rect, 14f * d, 14f * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        paint.color = withAlpha(cLit, (0.45f + 0.55f * pulse) * enter * 255f)
        canvas.drawRoundRect(rect, 14f * d, 14f * d, paint)

        // 对勾
        val tickX = rect.left + 26f * d
        val tickY = cy - 12f * d
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(cLit, enter * 255f)
        path.reset()
        path.moveTo(tickX - 8f * d, tickY)
        path.lineTo(tickX - 2f * d, tickY + 7f * d)
        path.lineTo(tickX + 9f * d, tickY - 8f * d)
        canvas.drawPath(path, paint)

        boldPaint.textSize = 15f * scaledDensity
        boldPaint.color = withAlpha(Color.WHITE, enter * 255f)
        canvas.drawText("360° 无死角覆盖完成", tickX + 20f * d, tickY - 4f * d, boldPaint)

        textPaint.textSize = 11f * scaledDensity
        textPaint.color = withAlpha(cTextDim, enter * 255f)
        canvas.drawText("可以提交给三维重建算法了", tickX + 20f * d, tickY + 14f * d, textPaint)

        // 明确告诉用户这里可以点，不然"点横幅重扫"是个藏起来的功能
        textPaint.textSize = 11.5f * scaledDensity
        textPaint.color = withAlpha(cAccent, (0.6f + 0.4f * pulse) * enter * 255f)
        canvas.drawText("点按此处重新扫描", tickX + 20f * d, tickY + 32f * d, textPaint)
    }

    // ------------------------------------------------------------------ 首次引导

    /**
     * 三页图文引导。
     *
     * 不做的话，第一次打开看到的就是"一个灰球 + 一堆点点 + 一句话"，
     * 用户得自己猜该干嘛。这里把三件必须知道的事按顺序讲清楚：
     * 物体放哪、手机怎么动、什么时候算完。
     */
    private fun drawOnboarding(canvas: Canvas) {
        val step = onboardingStep.coerceIn(0, ONBOARDING_TITLES.size - 1)

        // 遮罩
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#D9060B14")
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val panelW = min(w - 2f * pad - 8f * d, 320f * d)
        val panelH = 268f * d
        val cx = w * 0.5f
        val top = h * 0.5f - panelH * 0.5f
        // 面板自身的边界单独存一份：下面画按钮时会把 rect 改写成按钮矩形，
        // 「跳过」若再去读 rect 就会跑到按钮旁边去。
        val panelRight = cx + panelW * 0.5f
        val panelTop = top
        rect.set(cx - panelW * 0.5f, top, panelRight, top + panelH)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#F2101826")
        canvas.drawRoundRect(rect, 20f * d, 20f * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * d
        paint.color = Color.parseColor("#3D4DE1FF")
        canvas.drawRoundRect(rect, 20f * d, 20f * d, paint)

        // ---- 序号圆章 ----
        val badgeR = 21f * d
        val badgeCy = rect.top + 42f * d
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#264DE1FF")
        canvas.drawCircle(cx, badgeCy, badgeR, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * d
        paint.color = cAccent
        canvas.drawCircle(cx, badgeCy, badgeR, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 18f * scaledDensity
        boldPaint.color = cAccent
        canvas.drawText(
            "${step + 1}",
            cx,
            badgeCy - (boldPaint.descent() + boldPaint.ascent()) * 0.5f,
            boldPaint
        )

        // ---- 标题 ----
        boldPaint.textSize = 17f * scaledDensity
        boldPaint.color = cText
        val titleY = badgeCy + 42f * d
        canvas.drawText(ONBOARDING_TITLES[step], cx, titleY, boldPaint)

        // ---- 正文（按字符贪心折行，中文不需要按词断） ----
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 12.5f * scaledDensity
        textPaint.color = cTextDim
        val bodyLeft = rect.left + 26f * d
        val bodyMaxW = panelW - 52f * d
        drawWrappedText(
            canvas,
            ONBOARDING_BODIES[step],
            bodyLeft,
            titleY + 26f * d,
            bodyMaxW,
            textPaint,
            19f * scaledDensity
        )

        // ---- 页码圆点 ----
        val dotR = 3.5f * d
        val dotGap = 12f * d
        val dotsY = rect.bottom - 62f * d
        val dotsW = (ONBOARDING_TITLES.size - 1) * dotGap
        for (i in ONBOARDING_TITLES.indices) {
            val dx = cx - dotsW * 0.5f + i * dotGap
            paint.style = Paint.Style.FILL
            paint.color = if (i == step) cAccent else Color.parseColor("#40FFFFFF")
            canvas.drawCircle(dx, dotsY, if (i == step) dotR * 1.25f else dotR, paint)
        }

        // ---- 主按钮：点哪都能翻页，这里只是把"点哪"说清楚 ----
        val btn = if (step == ONBOARDING_TITLES.size - 1) "开始扫描" else "下一步"
        val btnH = 40f * d
        rect.set(cx - 82f * d, rect.bottom - 46f * d, cx + 82f * d, rect.bottom - 46f * d + btnH)
        paint.style = Paint.Style.FILL
        paint.color = if (step == ONBOARDING_TITLES.size - 1) cLit else cAccent
        canvas.drawRoundRect(rect, btnH * 0.5f, btnH * 0.5f, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 14f * scaledDensity
        textPaint.color = Color.parseColor("#FF05131C")
        canvas.drawText(
            btn,
            rect.centerX(),
            rect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f,
            textPaint
        )

        // ---- 跳过（面板右上角，小字） ----
        textPaint.textSize = 12f * scaledDensity
        textPaint.color = if (pressedOnboardSkip) cText else cTextDim
        val skipW = textPaint.measureText("跳过")
        val skipX = panelRight - 18f * d - skipW
        val skipY = panelTop + 24f * d
        canvas.drawText("跳过", skipX, skipY, textPaint)
        onboardSkipRect.set(skipX - 12f * d, skipY - 16f * d, skipX + skipW + 12f * d, skipY + 10f * d)

        textPaint.textAlign = Paint.Align.LEFT
        boldPaint.textAlign = Paint.Align.LEFT
    }

    /** 按字符贪心折行并绘制，返回下一行的基线 y */
    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        maxWidth: Float,
        p: Paint,
        lineHeight: Float
    ): Float {
        var line = StringBuilder()
        var y = top
        for (ch in text) {
            if (line.isNotEmpty() && p.measureText(line.toString() + ch) > maxWidth) {
                canvas.drawText(line.toString(), left, y, p)
                y += lineHeight
                line = StringBuilder()
            }
            line.append(ch)
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), left, y, p)
            y += lineHeight
        }
        return y
    }

    // ------------------------------------------------------------------ 权限遮罩

    private fun drawPermissionOverlay(canvas: Canvas) {
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#B3000000")
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val text = if (!hasCamera) "设备上没有可用相机" else "需要相机权限才能扫描"
        textPaint.textSize = 15f * scaledDensity
        textPaint.color = cText
        canvas.drawText(text, w * 0.5f - textPaint.measureText(text) * 0.5f, h * 0.5f - 10f * d, textPaint)

        val sub = if (!hasCamera) "仍可查看引导球动画" else "照片仅在本地用于重建，不会上传"
        textPaint.textSize = 11.5f * scaledDensity
        textPaint.color = cTextDim
        canvas.drawText(sub, w * 0.5f - textPaint.measureText(sub) * 0.5f, h * 0.5f + 12f * d, textPaint)

        if (permissionGranted && !hasCamera) return

        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = if (pressedPermission) Color.parseColor("#FF3FBFDF") else cAccent
        canvas.drawRoundRect(permissionRect, permissionRect.height() * 0.5f, permissionRect.height() * 0.5f, paint)
        textPaint.textSize = 13f * scaledDensity
        textPaint.color = Color.parseColor("#FF05131C")
        val btn = "授予相机权限"
        canvas.drawText(
            btn,
            permissionRect.centerX() - textPaint.measureText(btn) * 0.5f,
            permissionRect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f,
            textPaint
        )
    }

    // ------------------------------------------------------------------ 交互

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // ---- 首次引导期间接管全部触摸：点哪都翻页，只有「跳过」提前结束 ----
        if (onboardingStep >= 0 && permissionGranted && hasCamera) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedOnboardSkip = onboardSkipRect.contains(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasSkip = pressedOnboardSkip
                    pressedOnboardSkip = false
                    haptics.tick()
                    if (wasSkip || onboardingStep >= ONBOARDING_TITLES.size - 1) {
                        finishOnboarding()
                    } else {
                        onboardingStep++
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedOnboardSkip = false
                    invalidate()
                    return true
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                dragging = false
                if (!permissionGranted && hasCamera && permissionRect.contains(x, y)) {
                    pressedPermission = true
                    invalidate()
                    return true
                }
                if (permissionGranted && hasCamera) {
                    // 完成后的横幅整块可点 = 重新扫描（比底部那个要二次确认的「重置」顺手）
                    if (bannerShown && bannerHitRect.contains(x, y)) {
                        listener?.onReset()
                        haptics.tick()
                        bannerShown = false
                        invalidate()
                        return true
                    }
                    val dx = x - shutterCx
                    val dy = y - shutterCy
                    if (dx * dx + dy * dy <= (shutterR + 12f * d) * (shutterR + 12f * d)) {
                        pressedShutter = true
                        invalidate()
                        return true
                    }
                    if (modeRect.contains(x, y)) {
                        pressedMode = true; invalidate(); return true
                    }
                    if (resetRect.contains(x, y)) {
                        pressedReset = true; invalidate(); return true
                    }
                    if (cruiseRect.contains(x, y)) {
                        pressedCruise = true; invalidate(); return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                if (!pressedShutter && !pressedMode && !pressedReset && !pressedCruise && !pressedPermission) {
                    if (!dragging && (abs(x - lastTouchX) > 3f * d || abs(y - lastTouchY) > 3f * d)) {
                        dragging = true
                    }
                    if (dragging) {
                        listener?.onDrag(dx, dy)
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedShutter) {
                    pressedShutter = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        notifyShutter()
                        listener?.onShutter()
                    }
                    invalidate()
                    return true
                }
                if (pressedMode) {
                    pressedMode = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        autoCapture = listener?.onToggleAutoCapture() ?: autoCapture
                        haptics.tick()
                    }
                    invalidate()
                    return true
                }
                if (pressedReset) {
                    pressedReset = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (resetArmed) {
                            // 第二次点击：真正清空
                            resetArmed = false
                            bannerShown = false
                            haptics.faceCaptured()
                            listener?.onReset()
                        } else {
                            // 第一次点击：上膛，等一次确认
                            resetArmed = true
                            resetArmedMs = SystemClock.elapsedRealtime()
                            haptics.tick()
                        }
                    }
                    invalidate()
                    return true
                }
                if (pressedCruise) {
                    pressedCruise = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        autoOrbit = listener?.onToggleAutoOrbit() ?: autoOrbit
                        haptics.tick()
                    }
                    invalidate()
                    return true
                }
                if (pressedPermission) {
                    pressedPermission = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        listener?.onRequestPermission()
                    }
                    invalidate()
                    return true
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finishOnboarding() {
        onboardingStep = -1
        pressedOnboardSkip = false
        // 引导期间进度是被冻结的，这里把 HUD 侧的状态归零，避免残留旧数据
        for (s in 0 until SECTOR_COUNT) segFill[s] = 0f
        // 引导期间没有产生任何采集事件，所以"启动那次自动采集不震"的豁免名额
        // 已经被这段引导消耗掉了 —— 之后用户的第一次真实采集必须正常给反馈。
        firstCaptureEventSkipped = true
        listener?.onOnboardingFinished()
        invalidate()
    }

    // ------------------------------------------------------------------ 工具

    private fun drawPanel(canvas: Canvas, r: RectF, radius: Float) {
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = cPanel
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = cPanelStroke
        canvas.drawRoundRect(r, radius, radius, paint)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        return Color.rgb(
            (ar + (br - ar) * tt).toInt(),
            (ag + (bg - ag) * tt).toInt(),
            (ab + (bb - ab) * tt).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24)

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    private companion object {
        /** 覆盖度环相对球半径的倍数：比球略大一圈，正好套在外面 */
        const val RING_SCALE = 1.16f

        /** 球体停靠面板：极淡的一层底色 + 细描边，不能挡住相机画面 */
        const val DOCK_FILL = 0x14FFFFFF
        const val DOCK_STROKE = 0x4DFFFFFF

        /** 方位扇区数量（与 ScanSession 保持一致）：8 段进度环 / 「已拍 N/8 个面」 */
        const val SECTOR_COUNT = 8

        /** 中央引导文字纵向锚点（0 = 顶部，1 = 底部），居中偏上、避开底部球面板 */
        const val CENTER_GUIDE_Y = 0.46f

        /** 「重置」上膛后多久自动撤防（毫秒） */
        const val RESET_ARM_MS = 2500L

        /** 首次引导的三页文案 */
        val ONBOARDING_TITLES = arrayOf(
            "把物体放进取景框",
            "绕着物体缓慢转一圈",
            "8 个面全亮就完成"
        )
        val ONBOARDING_BODIES = arrayOf(
            "让被摄物体完整落在四角的框内，镜头与它保持一臂左右的距离。",
            "镜头正对球面上的哪一面，那一面的光点就会整片亮起，同时手机轻震一下。",
            "全部点亮后会弹出完成提示，点它即可开始下一轮。"
        )
    }
}

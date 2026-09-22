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
 *  - 覆盖度进度环严丝合缝地套在球外侧（球心与半径由渲染线程实时回传）；
 *  - 四角取景框标示"哪些角度会被判定为已拍摄"，让判定逻辑对用户可见；
 *  - 环上的琥珀色箭头指出"手机该往哪边转"，对应球面上呼吸的目标光点；
 *  - 快门 / 自动采样切换 / 重置 / 自动巡航，都是自绘的可点击控件；
 *  - 覆盖完成时给出庆祝提示。
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
    }

    var listener: Listener? = null

    // ------------------------------------------------------------------ 状态

    private val d = resources.displayMetrics.density

    /** 1sp 对应多少像素（不直接读已废弃的 scaledDensity，改用官方推荐写法） */
    private val scaledDensity =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics
        )

    private var coverageTarget = 0f
    private var coverageAnim = 0f
    private var lit = 0
    private var inFrameUnlit = 0
    private var fps = 0
    private var drawMs = 0f
    private var sphereRadiusPx = 0f

    /** 球心在屏幕上的位置（像素），由渲染线程回传 —— 渲染器做过投影平移，不能硬写画面中心 */
    private var sphereCenterXPx = 0f
    private var sphereCenterYPx = 0f

    /** 下一个建议面在**设备坐标系**下的水平方向，用来画"该往哪边转"的箭头 */
    private var nextX = 0f
    private var nextY = 0f
    private var hasTarget = false

    /** 已完成的面数（来自渲染线程 Stats，驱动中央文字与左上角面板） */
    private var doneCount = 0

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

    // 触摸状态
    private var pressedShutter = false
    private var pressedMode = false
    private var pressedReset = false
    private var pressedCruise = false
    private var pressedPermission = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false

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
        this.inFrameUnlit = stats.inFrameUnlit
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
        this.autoCapture = autoCapture
        this.autoOrbit = autoOrbit
        this.sourceLabel = sourceLabel
        this.sensorFresh = sensorFresh
        this.cameraReady = cameraReady
        this.permissionGranted = permissionGranted
        this.hasCamera = hasCamera

        if (stats.lastBatch > 0 && stats.batchFlash > 0.85f) {
            batchValue = stats.lastBatch
            batchStartMs = SystemClock.elapsedRealtime()
        }
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

        if (!permissionGranted || !hasCamera) drawPermissionOverlay(canvas)

        // 一次性决定本帧结束后要不要继续重绘：覆盖度还在平滑、或横幅/提示还在动。
        // （drawBatchToast / drawShutterFlash / drawBanner 内部不再各自重复调用。）
        if (animating || bannerShown || batchValue > 0 || shutterFlashMs != 0L) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 四角取景框：把被摄物体框在画面里，提示"拍的就是中间这个物体"。
     * 捕获粒度是「面」，判定只看相机朝向、与取景框无关，所以这里纯粹是视觉引导。
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
        paint.color = if (autoCapture) cAccentDim else Color.parseColor("#59FFC061")

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
     * 屏幕中央引导文字：替换原 8 段扇形环。
     *
     * 用户要求去掉「球体上方的扇形装饰」，改用文字提示「绕物体旋转一周」来引导完成
     * 环绕物体的完整旋转。球面已用 8 面点阵 + 目标面琥珀高亮表达进度，中央只保留一句
     * 简明的文字指令；全部 8 面扫完时切换为「采集完成」作为明确的完成反馈。
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

        val guide = if (allDone) "采集完成" else "绕物体旋转一周"
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 19f * scaledDensity
        boldPaint.color = if (allDone) cLit else cText
        val base = cy - (boldPaint.descent() + boldPaint.ascent()) * 0.5f
        canvas.drawText(guide, cx, base, boldPaint)

        // 小字副提示：已完成面数（仅作轻量进度提示，不喧宾夺主）
        if (!allDone) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 12f * scaledDensity
            textPaint.color = cTextDim
            val subY = cy + 18f * scaledDensity
            canvas.drawText("已标记 $done/$SECTOR_COUNT 个面", cx, subY, textPaint)
        }

        boldPaint.textAlign = Paint.Align.LEFT
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** 套在球外侧的覆盖度进度环 */
    private fun drawCoverageRing(canvas: Canvas) {
        val r = ringRadius
        val cx = sphereCx
        val cy = sphereCy
        ringRect.set(cx - r, cy - r, cx + r, cy + r)

        paint.reset()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // 轨道
        paint.strokeWidth = 3f * d
        paint.color = Color.parseColor("#2EFFFFFF")
        canvas.drawArc(ringRect, 0f, 360f, false, paint)

        // 进度
        paint.strokeWidth = 4.5f * d
        paint.color = blend(cAccent, cLit, coverageAnim)
        val sweep = 360f * coverageAnim.coerceIn(0f, 1f)
        canvas.drawArc(ringRect, -90f, sweep, false, paint)

        // 末端光点
        if (sweep > 1f) {
            val rad = Math.toRadians((-90f + sweep).toDouble())
            val px = cx + (r * kotlin.math.cos(rad)).toFloat()
            val py = cy + (r * kotlin.math.sin(rad)).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#66FFFFFF")
            canvas.drawCircle(px, py, 6f * d, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(px, py, 3f * d, paint)
        }
    }

    /**
     * 停靠在画面下方的引导球面板。
     *
     * 球缩小之后需要明确它"代表什么"：圆角边框把它框成一个独立的小窗，
     * 下方的「物体」标签点明它就是被拍摄的那个物体，右侧标出还差多少个面。
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
        // 文案刻意压到 6 个字以内：这一行要和左边的「物体」胶囊并排塞进
        // 球体面板的宽度里，写长了就会互相压字。
        val status: String
        val statusColor: Int
        when {
            !permissionGranted -> { status = "待授权"; statusColor = cWarn }
            !hasCamera -> { status = "无相机"; statusColor = cWarn }
            coverageTarget >= 0.999f -> { status = "全部覆盖 ✓"; statusColor = cLit }
            lit == 0 -> { status = "缓慢环绕物体"; statusColor = cTextDim }
            inFrameUnlit > 0 -> { status = "框内 $inFrameUnlit 个"; statusColor = cAccent }
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

        paint.reset()
        paint.style = Paint.Style.FILL
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

        // 细进度条
        val barTop = rect.bottom - 18f * d
        val barLeft = x
        val barRight = rect.right - 14f * d
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#33FFFFFF")
        rect.set(barLeft, barTop, barRight, barTop + 5f * d)
        canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
        if (coverageAnim > 0.001f) {
            rect.set(barLeft, barTop, barLeft + (barRight - barLeft) * coverageAnim, barTop + 5f * d)
            paint.color = blend(cAccent, cLit, coverageAnim)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, paint)
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

    /** 底部：快门、模式切换、重置、自动巡航、提示文案 */
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

        // ---- 重置 ----
        drawPill(canvas, resetRect, "重置", cTextDim, pressedReset)

        // ---- 自动巡航 ----
        drawPill(
            canvas, cruiseRect,
            if (autoOrbit) "巡航中 ●" else "自动巡航",
            if (autoOrbit) cAccent else cTextDim,
            pressedCruise
        )

        // 提示文案已经并入球体停靠面板右下角的状态行：
        // 底部这块只剩快门与两个胶囊，再把一整行字塞进快门上方，
        // 会和「物体」胶囊挤在同一高度上互相压字。
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

    /** 覆盖完成横幅 */
    private fun drawBanner(canvas: Canvas, now: Long) {
        if (!bannerShown) return
        val elapsed = (now - bannerStartMs) / 1000f
        val enter = min(1f, elapsed / 0.35f)
        val pulse = 0.5f + 0.5f * kotlin.math.sin(elapsed * 3.4).toFloat()

        val bw = 236f * d
        val bh = 62f * d
        val cx = w * 0.5f
        // 横条要同时避开右上角的状态芯片列与球体的停靠面板：
        // 优先贴在停靠面板上方（"已捕获 +N" 浮动提示的地盘下面），空间不够时才退到芯片列正下方。
        val chipsBottom = pad + 3f * (26f * d + 6f * d) + 32f * d
        val ringTop = sphereCy - ringRadius
        val minCy = chipsBottom + bh * 0.5f + 10f * d
        val preferred = ringTop - bh * 0.5f - 56f * d
        val cy = maxOf(minCy, preferred) + (1f - easeOut(enter)) * -20f * d
        rect.set(cx - bw * 0.5f, cy - bh * 0.5f, cx + bw * 0.5f, cy + bh * 0.5f)

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
        val tickY = cy
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
        canvas.drawText("360° 无死角覆盖完成", tickX + 20f * d, cy - 3f * d, boldPaint)

        textPaint.textSize = 11f * scaledDensity
        textPaint.color = withAlpha(cTextDim, enter * 255f)
        canvas.drawText("可以提交给重建算法了", tickX + 20f * d, cy + 14f * d, textPaint)
    }

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
                    }
                    invalidate()
                    return true
                }
                if (pressedReset) {
                    pressedReset = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        bannerShown = false
                        listener?.onReset()
                    }
                    invalidate()
                    return true
                }
                if (pressedCruise) {
                    pressedCruise = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        autoOrbit = listener?.onToggleAutoOrbit() ?: autoOrbit
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

        /** 方位扇区数量（与 ScanSession 保持一致）：中央文字用「已标记 N/8 个面」 */
        const val SECTOR_COUNT = 8

        /** 中央引导文字纵向锚点（0 = 顶部，1 = 底部），居中偏上、避开底部球面板 */
        const val CENTER_GUIDE_Y = 0.46f
    }
}

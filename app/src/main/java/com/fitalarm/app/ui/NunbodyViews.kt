package com.fitalarm.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.core.RangeSelection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/*
 * 눈바디 화면들이 함께 쓰는 작은 부품
 */

/** 너비에 맞춰 높이를 정하는 틀 (ratio = 높이 / 너비) */
class RatioFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w * ratio).toInt(), MeasureSpec.EXACTLY))
    }
}

/** 둥근 모서리로 자르기 */
fun View.clipRounded(color: Int, radiusPx: Float) {
    background = rounded(color, radiusPx)
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

/** 아이콘만 있는 둥근 버튼 */
fun Context.iconButton(resId: Int, desc: String, color: Int = Palette.TEXT, sizeDp: Int = 22, onClick: () -> Unit): View =
    FrameLayout(this).apply {
        background = rippleOnly(22)
        isClickable = true
        isFocusable = true
        contentDescription = desc
        setOnClickListener { onClick() }
        addView(icon(resId, color, sizeDp), FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        layoutParams = lp(dp(48), dp(48))
    }

/** 눈바디 화면 위쪽: 뒤로 + 제목(+부제) + 오른쪽 버튼들 */
fun Activity.nbHeader(title: String, subtitle: String? = null, closeIcon: Boolean = false, actions: List<View> = emptyList()): Pair<View, TextView?> {
    var sub: TextView? = null
    val bar = hbox {
        setPadding(dp(4), dp(8), dp(8), dp(4))
        addView(iconButton(if (closeIcon) R.drawable.ic_close else R.drawable.ic_chevron_left, if (closeIcon) "닫기" else "뒤로") {
            onBackPressedCompat()
        })
        addView(vbox {
            addView(label(title, 20f, Palette.TEXT, Fonts.BOLD))
            if (subtitle != null) {
                sub = label(subtitle, 12.5f, Palette.TEXT_DIM)
                addView(sub, lp().margins(t = dp(3)))
            }
        }, lp(0, WRAP, 1f).margins(l = dp(4)))
        actions.forEach { addView(it) }
    }
    return bar to sub
}

/** 뒤로 가기 (안드로이드 13 이상의 새 뒤로 가기와 같은 동작) */
fun Activity.onBackPressedCompat() {
    @Suppress("DEPRECATION")
    onBackPressed()
}

/** 숫자 입력칸 */
fun Context.numberField(hint: String, text: String = ""): EditText = EditText(this).apply {
    this.hint = hint
    setText(text)
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    textSize = 17f
    setTextColor(Palette.TEXT)
    setHintTextColor(Palette.TEXT_FAINT)
    setSingleLine()
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = rounded(Palette.SURFACE, dpf(14), Palette.OUTLINE, dp(1.5f))
    setOnFocusChangeListener { v, has ->
        v.background = rounded(Palette.SURFACE, dpf(14), if (has) Palette.ACCENT else Palette.OUTLINE, dp(1.5f))
    }
}

/** 이름 + 입력칸 + 단위 한 줄 */
fun Context.fieldRow(name: String, field: EditText, unit: String): View = hbox {
    addView(label(name, 14.5f, Palette.TEXT_DIM), lp(dp(64), WRAP))
    addView(field, lp(0, WRAP, 1f))
    addView(label(unit, 14.5f, Palette.TEXT_DIM), lp(dp(34), WRAP).margins(l = dp(10)))
}

/** 아래에서 올라오는 시트 */
fun Context.bottomSheet(content: View, dark: Boolean = false): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val wrap = vbox {
        setPadding(dp(20), dp(10), dp(20), dp(24))
        background = rounded(if (dark) CameraColors.PANEL else Palette.SURFACE, dpf(28)).apply {
            cornerRadii = floatArrayOf(dpf(28), dpf(28), dpf(28), dpf(28), 0f, 0f, 0f, 0f)
        }
        addView(View(this@bottomSheet).apply {
            background = rounded(if (dark) CameraColors.LINE else Palette.OUTLINE, dpf(3))
        }, lp(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(content, lp().margins(t = dp(12)))
    }
    d.setContentView(centeredColumn(wrap, 560))
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.45f)
    }
    return d
}

/** 시트 안의 한 줄 (둥근 아이콘 + 제목 + 설명) */
fun Context.sheetRow(iconRes: Int, title: String, subtitle: String, tint: Int, tintBg: Int, titleColor: Int = Palette.TEXT, onClick: () -> Unit): View = hbox {
    setPadding(0, dp(10), 0, dp(10))
    background = rippleOnly(14)
    isClickable = true
    setOnClickListener { onClick() }
    addView(FrameLayout(this@sheetRow).apply {
        background = oval(tintBg)
        addView(icon(iconRes, tint, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }, lp(dp(44), dp(44)).margins(r = dp(14)))
    addView(vbox {
        addView(label(title, 16f, titleColor, Fonts.BOLD))
        addView(label(subtitle, 13f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.2f) }, lp().margins(t = dp(3)))
    }, lp(0, WRAP, 1f))
}

/** 안내 상자 (방패·경고 아이콘 + 글) */
fun Context.noteBox(iconRes: Int, text: String, bg: Int, fg: Int): View = hbox {
    gravity = Gravity.TOP
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = rounded(bg, dpf(16))
    addView(icon(iconRes, fg, 18), lp(dp(18), dp(18)).margins(r = dp(10), t = dp(1)))
    addView(label(text, 13f, fg).apply { setLineSpacing(0f, 1.3f) }, lp(0, WRAP, 1f))
}

/** 카메라 화면 색 (밝은/어두운 테마와 상관없이 항상 어두움) */
object CameraColors {
    const val BG = 0xFF0B1410.toInt()
    const val PANEL = 0xFF0E1A16.toInt()
    const val CHIP = 0xFF16241F.toInt()
    const val LINE = 0xFF34463D.toInt()
    const val TEXT = 0xFFE7F2EC.toInt()
    const val DIM = 0xFFA7BDB2.toInt()
    const val ACCENT = 0xFF4ADE80.toInt()
    const val ON_ACCENT = 0xFF052E16.toInt()
    const val SCRIM = 0x99050C09.toInt()
}

// ============================================================================ 체중 그래프

/** 최근 6개월 체중 꺾은선 (가장 최근 점은 속이 빈 원) */
class WeightChartView(context: Context) : View(context) {
    private var points: List<Pair<LocalDate, Double>> = emptyList()
    private var months: List<YearMonth> = emptyList()

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dpf(1) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(3)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dpf(11)
        textAlign = Paint.Align.CENTER
    }

    fun setData(data: List<Pair<LocalDate, Double>>, today: LocalDate) {
        val first = YearMonth.from(today).minusMonths(5)
        months = (0..5).map { first.plusMonths(it.toLong()) }
        points = data.filter { !YearMonth.from(it.first).isBefore(first) }.sortedBy { it.first }
        contentDescription = if (points.isEmpty()) "체중 기록 없음"
        else "최근 6개월 체중 그래프, ${points.first().second}kg 에서 ${points.last().second}kg"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (months.isEmpty()) return
        val padX = context.dpf(14)
        val labelH = context.dpf(18)
        val top = context.dpf(10)
        val bottom = height - labelH
        val w = width - padX * 2
        grid.color = Palette.SURFACE_HI
        for (i in 0..2) {
            val y = top + (bottom - top - context.dpf(6)) * i / 2f
            canvas.drawLine(padX, y, width - padX, y, grid)
        }
        text.color = Palette.TEXT_DIM
        val start = months.first().atDay(1)
        val end = months.last().atEndOfMonth()
        val span = (end.toEpochDay() - start.toEpochDay()).coerceAtLeast(1).toFloat()
        months.forEachIndexed { i, m ->
            val x = padX + w * (i + 0.5f) / months.size
            canvas.drawText("${m.monthValue}월", x, height - context.dpf(4), text)
        }
        if (points.isEmpty()) return
        val min = points.minOf { it.second }
        val max = points.maxOf { it.second }
        val pad = ((max - min) * 0.2).coerceAtLeast(0.5)
        val lo = min - pad
        val hi = max + pad
        fun x(d: LocalDate) = padX + w * ((d.toEpochDay() - start.toEpochDay()) / span)
        fun y(v: Double) = (top + (bottom - top - context.dpf(6)) * (1 - ((v - lo) / (hi - lo)))).toFloat()
        line.color = Palette.ACCENT
        val path = Path()
        points.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
        if (points.size > 1) canvas.drawPath(path, line)
        points.forEachIndexed { i, (d, v) ->
            if (i == points.lastIndex) {
                dot.color = Palette.SURFACE
                canvas.drawCircle(x(d), y(v), context.dpf(5.5f), dot)
                canvas.drawCircle(x(d), y(v), context.dpf(5.5f), line)
            } else {
                dot.color = Palette.ACCENT
                canvas.drawCircle(x(d), y(v), context.dpf(4), dot)
            }
        }
    }
}

// ============================================================================ 기간 달력

/**
 * 한 달 달력. 날짜를 차례로 눌러 시작·끝을 고르거나, 꾹 누른 채 끌어서 한 번에 정함.
 * 끄는 중에 달력 위(이전 달)·아래(다음 달) 바깥에 잠깐 머물면 달이 넘어감.
 * 사진이 있는 날은 점, 오늘 이후는 흐리게 (고를 수 없음).
 */
@SuppressLint("ViewConstructor")
class RangeCalendarView(
    context: Context,
    private val selection: RangeSelection,
    private val today: LocalDate,
    private val photoDays: Set<LocalDate>,
    private val onChange: () -> Unit,
    private val onMonthChange: (YearMonth) -> Unit,
) : View(context) {
    var month: YearMonth = YearMonth.from(today)
        set(value) {
            field = value
            onMonthChange(value)
            invalidate()
        }

    private val cellH = context.dpf(44)
    private val headerH = context.dpf(22)
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dpf(15)
        textAlign = Paint.Align.CENTER
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dpf(12)
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var downDay: LocalDate? = null
    private var dragging = false
    private var edgeSince = 0L

    private val rows: Int
        get() {
            val lead = month.atDay(1).dayOfWeek.value - 1   // 월요일 시작 (기록 탭 달력과 같음)
            return (lead + month.lengthOfMonth() + 6) / 7
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (headerH + cellH * 6).toInt())
    }

    private fun dayAt(x: Float, y: Float): LocalDate? {
        val col = (x / (width / 7f)).toInt().coerceIn(0, 6)
        val row = ((y - headerH) / cellH).toInt()
        if (y < headerH || row < 0 || row >= rows) return null
        val lead = month.atDay(1).dayOfWeek.value - 1
        val n = row * 7 + col - lead + 1
        return if (n in 1..month.lengthOfMonth()) month.atDay(n) else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cw = width / 7f
        DayOfWeek.entries.forEachIndexed { i, d ->
            headPaint.color = when (d) {
                DayOfWeek.SUNDAY -> Palette.DANGER
                DayOfWeek.SATURDAY -> Palette.ON_SKY
                else -> Palette.TEXT_DIM
            }
            canvas.drawText(d.getDisplayName(TextStyle.NARROW, Locale.KOREAN), cw * (i + 0.5f), headerH - context.dpf(8), headPaint)
        }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val (s, e) = selection.result() ?: (null to null)
        val r = context.dpf(19)
        for (n in 1..month.lengthOfMonth()) {
            val d = month.atDay(n)
            val idx = lead + n - 1
            val col = idx % 7
            val row = idx / 7
            val cx = cw * (col + 0.5f)
            val cy = headerH + cellH * (row + 0.5f)
            val inRange = s != null && e != null && !d.isBefore(s) && !d.isAfter(e)
            val isEdge = d == s || d == e
            if (inRange && s != e) {
                fill.color = Palette.ACCENT_SOFT
                val left = if (d == s) cx else cw * col
                val right = if (d == e) cx else cw * (col + 1)
                rect.set(left, cy - r, right, cy + r)
                canvas.drawRect(rect, fill)
            }
            if (isEdge) {
                fill.color = Palette.ACCENT
                canvas.drawCircle(cx, cy, r, fill)
            } else if (d == today) {
                fill.color = Palette.ACCENT
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = context.dpf(1.5f)
                canvas.drawCircle(cx, cy, r - context.dpf(1), fill)
                fill.style = Paint.Style.FILL
            }
            numPaint.typeface = if (isEdge || d == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            numPaint.color = when {
                isEdge -> Palette.ON_ACCENT
                d.isAfter(today) -> Palette.TEXT_FAINT
                d.dayOfWeek == DayOfWeek.SUNDAY -> Palette.DANGER
                d.dayOfWeek == DayOfWeek.SATURDAY -> Palette.ON_SKY
                else -> Palette.TEXT
            }
            canvas.drawText(n.toString(), cx, cy + numPaint.textSize * 0.35f, numPaint)
            if (d in photoDays) {
                fill.color = if (isEdge) Palette.ON_ACCENT else Palette.ACCENT
                canvas.drawCircle(cx, cy + context.dpf(13), context.dpf(2.5f), fill)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downDay = dayAt(ev.x, ev.y)
                dragging = false
                edgeSince = 0L
                parent?.requestDisallowInterceptTouchEvent(true)
                return downDay != null
            }
            MotionEvent.ACTION_MOVE -> {
                val d = dayAt(ev.x, ev.y.coerceIn(headerH + 1, headerH + cellH * rows - 1))
                if (!dragging && d != null && d != downDay) {
                    // 누른 날에서 다른 날로 손가락이 움직이면 끌어서 고르기
                    dragging = true
                    downDay?.let { selection.dragStart(it) }
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                if (dragging) {
                    // 달력 바깥 위·아래에 잠깐 머물면 달 넘기기
                    val outsideUp = ev.y < headerH
                    val outsideDown = ev.y > headerH + cellH * rows
                    if (outsideUp || outsideDown) {
                        val now = SystemClock.uptimeMillis()
                        if (edgeSince == 0L) edgeSince = now
                        if (now - edgeSince > 600) {
                            val next = if (outsideUp) month.minusMonths(1) else month.plusMonths(1)
                            if (!next.atDay(1).isAfter(today)) month = next
                            edgeSince = now
                        }
                    } else edgeSince = 0L
                    if (d != null) {
                        selection.dragTo(d)
                        invalidate()
                        onChange()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) selection.dragEnd()
                else downDay?.let {
                    if (!it.isAfter(today)) {
                        selection.tap(it)
                        playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    }
                }
                dragging = false
                invalidate()
                onChange()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) selection.dragEnd()
                dragging = false
            }
        }
        return true
    }
}

/** 흐리게 가린 사진 위에 눈 가림 아이콘 */
fun Context.blurBadge(): View = ImageView(this).apply {
    setImageDrawable(getDrawable(R.drawable.ic_eye_off)?.mutate())
    imageTintList = android.content.res.ColorStateList.valueOf(Palette.TEXT_DIM)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** 점선 테두리 (찍기 칸) */
fun dashedBorder(color: Int, widthPx: Float, radiusPx: Float, dashPx: Float): android.graphics.drawable.Drawable =
    object : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            this.color = color
            pathEffect = DashPathEffect(floatArrayOf(dashPx, dashPx * 0.7f), 0f)
        }
        private val r = RectF()
        override fun draw(canvas: Canvas) {
            r.set(bounds)
            r.inset(widthPx / 2, widthPx / 2)
            canvas.drawRoundRect(r, radiusPx, radiusPx, p)
        }
        override fun setAlpha(alpha: Int) {
            p.alpha = alpha
        }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            p.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

/** 사진 칸 하나 (둥근 사진 + 왼쪽 위 날짜 표시) */
fun Context.photoTile(ratio: Float = 1.2f, badge: String? = null): Pair<RatioFrame, ImageView> {
    val frame = RatioFrame(this, ratio)
    frame.clipRounded(Palette.SURFACE_HI, dpf(14))
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    frame.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
    if (badge != null) {
        frame.addView(label(badge, 11f, 0xFF10231A.toInt(), Fonts.MEDIUM).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(0xEBFFFFFF.toInt(), dpf(10))
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8); topMargin = dp(8) })
    }
    return frame to img
}

/** 가로로 나란한 칸들을 3칸 줄로 */
fun Context.gridRows(cells: List<View>, columns: Int = 3, gapDp: Int = 8): View = vbox {
    cells.chunked(columns).forEachIndexed { ri, chunk ->
        addView(hbox {
            gravity = Gravity.TOP
            for (i in 0 until columns) {
                val v = chunk.getOrNull(i) ?: View(this@gridRows)
                addView(v, lp(0, WRAP, 1f).margins(l = if (i == 0) 0 else dp(gapDp)))
            }
        }, lp().margins(t = if (ri == 0) 0 else dp(gapDp)))
    }
}

/** LinearLayout 자식 여백 설정 편의 */
fun LinearLayout.addSpaced(v: View, topDp: Int) {
    addView(v, lp().margins(t = context.dp(topDp)))
}

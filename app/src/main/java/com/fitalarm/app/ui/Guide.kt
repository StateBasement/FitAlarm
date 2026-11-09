package com.fitalarm.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** 안내 한 단계: 밝게 비출 화면 부분과 말풍선 글 */
class GuideStep(
    val tag: String,
    val title: String,
    val body: CharSequence,
    val targets: () -> List<View>,
)

/**
 * 처음 한 번 보여 주는 사용 안내. 화면 전체를 반투명하게 어둡게 하고 설명할 부분만 밝게 비춘 뒤
 * 그 옆 말풍선에 "이 기능은 뭐고"를 보여 줌 (이전·다음·건너뛰기).
 * 안내 중에는 아래 화면을 누를 수 없음 (실수로 설정이 바뀌지 않게).
 */
@SuppressLint("ViewConstructor")
class GuideOverlay private constructor(
    private val activity: Activity,
    private val steps: List<GuideStep>,
    private val footnote: String?,
    private val onDone: () -> Unit,
) : FrameLayout(activity) {

    private var index = 0
    private val hole = RectF()
    private val holePath = Path()
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC408140E.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(3)
        color = 0xFF4ADE80.toInt()
    }
    private val bubble: LinearLayout
    private val stepText: TextView
    private val dots: LinearLayout
    private val titleText: TextView
    private val bodyText: TextView
    private val prevButton: TextView
    private val nextButton: TextView
    private val footText: TextView
    private var backCallback: Any? = null

    private fun dp(v: Number) = context.dp(v)
    private fun dpf(v: Number) = context.dpf(v)

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        bubble = context.vbox {
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = rounded(Palette.SURFACE, dpf(20))
            elevation = dpf(10)
            isClickable = true
        }
        stepText = context.label("", 12.5f, Palette.ACCENT, Fonts.BOLD)
        dots = context.hbox()
        bubble.addView(context.hbox {
            addView(stepText, lp(0, WRAP, 1f))
            addView(dots)
        }, lp())
        titleText = context.label("", 17f, Palette.TEXT, Fonts.BOLD)
        bubble.addView(titleText, lp().margins(t = context.dp(8)))
        bodyText = context.label("", 14f, Palette.TEXT).apply { setLineSpacing(0f, 1.4f) }
        // 단계가 바뀌면 화면 읽기(TalkBack)가 제목·설명을 읽어 줌
        bubble.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        bubble.addView(bodyText, lp().margins(t = context.dp(6)))
        prevButton = context.pillButton("이전", filled = false, heightDp = 44) { go(index - 1) }
        nextButton = context.pillButton("다음", filled = true, heightDp = 44) { if (index < steps.lastIndex) go(index + 1) else finish() }
        val skip = context.label("건너뛰기", 14.5f, Palette.TEXT_DIM).apply {
            setPadding(context.dp(6), context.dp(12), context.dp(12), context.dp(12))
            background = context.rippleOnly(12)
            isClickable = true
            setOnClickListener { finish() }
        }
        bubble.addView(context.hbox {
            addView(skip)
            addView(context.spacer())
            addView(prevButton, lp(WRAP, WRAP).margins(r = context.dp(6)))
            addView(nextButton, lp(WRAP, WRAP))
        }, lp().margins(t = context.dp(10)))
        footText = context.label(footnote ?: "", 12f, Palette.TEXT_DIM)
        bubble.addView(footText, lp().margins(t = context.dp(4)))
        addView(bubble, LayoutParams(WRAP, WRAP))
    }

    private fun show() {
        val decor = activity.window.decorView as ViewGroup
        decor.addView(this, ViewGroup.LayoutParams(MATCH, MATCH))
        requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback { finish() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            backCallback = cb
        }
        go(0)
    }

    private fun go(i: Int) {
        if (i !in steps.indices) return
        index = i
        val s = steps[i]
        stepText.text = "${i + 1} / ${steps.size} · ${s.tag}"
        titleText.text = s.title
        bodyText.text = s.body
        prevButton.visibility = if (i == 0) View.GONE else View.VISIBLE
        nextButton.text = if (i == steps.lastIndex) "완료" else "다음"
        footText.visibility = if (i == 0 && footnote != null) View.VISIBLE else View.GONE
        dots.removeAllViews()
        steps.indices.forEach { d ->
            dots.addView(View(context).apply {
                background = rounded(if (d == i) Palette.ACCENT else Palette.OUTLINE, context.dpf(3))
            }, lp(context.dp(if (d == i) 16 else 6), context.dp(6)).margins(l = context.dp(5)))
        }
        // 설명할 부분이 보이도록 스크롤한 뒤 위치를 잼
        val targets = s.targets().filter { it.visibility == View.VISIBLE }
        targets.minByOrNull { it.top }?.let { t ->
            // 여러 카드를 함께 비출 때는 첫 카드 위부터 마지막 카드 아래까지 보이게
            val span = (targets.maxOf { it.bottom } - t.top).coerceAtLeast(t.height)
            t.requestRectangleOnScreen(Rect(0, 0, t.width, span), true)
        }
        post { post { measureHole(targets) } }
    }

    private fun measureHole(targets: List<View>) {
        val me = IntArray(2)
        getLocationInWindow(me)
        val r = RectF()
        var first = true
        val loc = IntArray(2)
        for (t in targets) {
            if (!t.isShown) continue
            t.getLocationInWindow(loc)
            val tr = RectF((loc[0] - me[0]).toFloat(), (loc[1] - me[1]).toFloat(), (loc[0] - me[0] + t.width).toFloat(), (loc[1] - me[1] + t.height).toFloat())
            if (first) r.set(tr) else r.union(tr)
            first = false
        }
        val pad = context.dpf(6)
        if (first) r.set(width / 2f, height / 3f, width / 2f, height / 3f) else r.inset(-pad, -pad)
        // 화면을 넘는 부분은 잘라서 비춤
        r.intersect(context.dpf(8), context.dpf(8), width - context.dpf(8), height - context.dpf(8))
        hole.set(r)
        holePath.reset()
        val radius = context.dpf(24)
        holePath.addRoundRect(hole, radius, radius, Path.Direction.CW)
        placeBubble()
        invalidate()
    }

    private fun placeBubble() {
        val maxW = minOf(width - context.dp(32), context.dp(440))
        bubble.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val bh = bubble.measuredHeight
        val gap = context.dp(14)
        val topInset = context.dp(32)
        val bottomInset = context.dp(24)
        val below = hole.bottom + gap
        val above = hole.top - gap - bh
        val y = when {
            below + bh <= height - bottomInset -> below
            above >= topInset -> above
            else -> (height - bh - bottomInset).toFloat()
        }
        bubble.layoutParams = LayoutParams(maxW, WRAP).apply {
            leftMargin = (width - maxW) / 2
            topMargin = y.toInt()
            gravity = Gravity.TOP or Gravity.START
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.clipOutPath(holePath)
        canvas.drawPaint(dim)
        canvas.restore()
        if (!hole.isEmpty) {
            val r = context.dpf(24)
            canvas.drawRoundRect(hole, r, r, ring)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true   // 아래 화면으로 터치가 가지 않게

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun finish() {
        if (parent == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        (parent as? ViewGroup)?.removeView(this)
        onDone()
    }

    companion object {
        const val FOOTNOTE = "처음 한 번만 보여요 · 설정 탭 › 사용 방법에서 다시 볼 수 있어요"

        /** 화면이 그려진 뒤에 띄움 */
        fun show(activity: Activity, steps: List<GuideStep>, footnote: String? = FOOTNOTE, onDone: () -> Unit = {}) {
            if (steps.isEmpty() || activity.isFinishing) return
            activity.window.decorView.post {
                if (!activity.isFinishing && !activity.isDestroyed) GuideOverlay(activity, steps, footnote, onDone).show()
            }
        }

        private const val PREFS = MainActivity.UI_PREFS
        const val ALARM_EDIT = "guide_alarm_edit_seen"
        const val NUNBODY_SETTINGS = "guide_nunbody_settings_seen"

        fun seen(ctx: Context, key: String): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

        fun markSeen(ctx: Context, key: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()
        }
    }
}

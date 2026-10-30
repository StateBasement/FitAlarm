package com.fitalarm.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.TextView

/**
 * 길게 누르고 있어야 실행되는 버튼. 누르는 동안 왼쪽부터 색이 차오르고, 끝까지 차면 실행합니다.
 * 잠이 덜 깬 상태에서 스치듯 눌러 알람이 꺼지는 것을 막기 위한 버튼입니다.
 *
 * 진행은 화면 프레임마다 실제 경과 시간으로 계산합니다. (휴대폰의 "애니메이션 삭제" 설정을 켜도
 * 바로 실행되지 않도록 ValueAnimator 를 쓰지 않음)
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class HoldButton(
    context: Context,
    idleText: String,
    private val holdMs: Long,
    private val onConfirmed: () -> Unit,
) : TextView(context) {

    var fillColor: Int = Palette.WARM_SOFT
    var strokeColor: Int = Palette.OUTLINE

    /** 손을 떼고 있을 때 보이는 글자 */
    var idleText: String = idleText
        set(value) {
            field = value
            contentDescription = "$value. ${holdSeconds()}초간 누르고 있으면 실행돼요"
            if (downAt == 0L) text = value
        }

    private var downAt = 0L
    private var progress = 0f
    private var confirmed = false
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    private val tick = object : Runnable {
        override fun run() {
            if (downAt == 0L || confirmed) return
            progress = ((SystemClock.uptimeMillis() - downAt).toFloat() / holdMs).coerceIn(0f, 1f)
            invalidate()
            if (progress >= 1f) confirm() else postOnAnimation(this)
        }
    }

    init {
        this.idleText = idleText
        text = idleText
        textSize = 15f
        typeface = Fonts.MEDIUM
        setTextColor(Palette.TEXT_DIM)
        gravity = Gravity.CENTER
        minHeight = context.dp(52)
        setPadding(context.dp(20), 0, context.dp(20), 0)
        isClickable = true
        isFocusable = true
        // 짧게 누르거나 화면 낭독(TalkBack)으로 누르면 방법을 알려줌
        setOnClickListener { if (!confirmed) context.toast("${holdSeconds()}초간 누르고 있으세요") }
        setOnTouchListener { _, ev ->
            if (confirmed) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    progress = 0f
                    isPressed = true
                    text = "계속 누르고 있으세요…"
                    postOnAnimation(tick)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 손가락이 버튼 밖으로 나가면 취소
                    if (ev.x < 0 || ev.y < 0 || ev.x > width || ev.y > height) reset()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val shortTap = downAt != 0L && SystemClock.uptimeMillis() - downAt < SHORT_TAP_MS
                    reset()
                    if (ev.actionMasked == MotionEvent.ACTION_UP && shortTap) performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun holdSeconds() = (holdMs + 500) / 1000

    private fun reset() {
        removeCallbacks(tick)
        downAt = 0L
        progress = 0f
        isPressed = false
        text = idleText
        invalidate()
    }

    private fun confirm() {
        if (confirmed) return
        confirmed = true
        removeCallbacks(tick)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onConfirmed()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        if (progress > 0f) {
            fillPaint.color = fillColor
            rect.set(0f, 0f, w, h)
            canvas.save()
            canvas.clipRect(0f, 0f, w * progress, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
            canvas.restore()
        }
        val sw = context.dpf(1.5f)
        strokePaint.strokeWidth = sw
        strokePaint.color = strokeColor
        rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        canvas.drawRoundRect(rect, r - sw / 2, r - sw / 2, strokePaint)
        super.onDraw(canvas)
    }

    private companion object {
        const val SHORT_TAP_MS = 400L
    }
}

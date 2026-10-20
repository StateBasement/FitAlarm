package com.fitalarm.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 앱 전체 색상 (어두운 테마 하나) */
object Palette {
    val BG = 0xFF0E1014.toInt()
    val SURFACE = 0xFF171A20.toInt()
    val SURFACE_HI = 0xFF22262E.toInt()
    val OUTLINE = 0xFF2E333C.toInt()
    val TEXT = 0xFFF2F4F7.toInt()
    val TEXT_DIM = 0xFF98A1AE.toInt()
    val TEXT_FAINT = 0xFF5C6470.toInt()
    val ACCENT = 0xFFFF7A45.toInt()
    val ACCENT_SOFT = 0x33FF7A45
    val ON_ACCENT = 0xFF1A0E08.toInt()
    val GOOD = 0xFF3DDC97.toInt()
    val WARN = 0xFFFFC145.toInt()
    val DANGER = 0xFFFF5A5F.toInt()
    val RIPPLE = 0x33FFFFFF
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun oval(color: Int, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/**
 * 터치 물결 효과. 마스크가 없으면 content 의 불투명 영역에만 그려지므로,
 * 투명 배경·테두리만 있는 버튼은 mask 를 함께 넘겨야 합니다.
 */
fun ripple(content: Drawable?, mask: Drawable? = null, rippleColor: Int = Palette.RIPPLE): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)

/** 배경 없이 둥근 영역에만 물결 효과 */
fun Context.rippleOnly(radiusDp: Number): RippleDrawable = ripple(null, rounded(Color.WHITE, dpf(radiusDp)))

object Fonts {
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
}

fun Context.label(
    text: CharSequence,
    sizeSp: Float = 15f,
    color: Int = Palette.TEXT,
    typeface: Typeface? = null,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (typeface != null) this.typeface = typeface
    includeFontPadding = false
}

fun Context.vbox(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

fun Context.hbox(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

fun lp(
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
    apply { setMargins(l, t, r, b) }

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** 가로 공간을 채우는 빈 뷰 */
fun Context.spacer(): View = View(this).apply { layoutParams = lp(0, 0, 1f) }

/** 둥근 버튼. filled=true 면 주황 배경, false 면 테두리만 */
fun Context.pillButton(text: String, filled: Boolean, heightDp: Int = 52, onClick: () -> Unit): TextView =
    label(text, 16f, if (filled) Palette.ON_ACCENT else Palette.TEXT, Fonts.MEDIUM).apply {
        gravity = Gravity.CENTER
        minHeight = dp(heightDp)
        setPadding(dp(20), 0, dp(20), 0)
        val radius = dpf(heightDp / 2f)
        background = ripple(
            content = if (filled) rounded(Palette.ACCENT, radius)
            else rounded(Color.TRANSPARENT, radius, Palette.OUTLINE, dp(1.5f)),
            mask = rounded(Color.WHITE, radius),
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * 모든 Android 버전에서 화면을 상태바/내비게이션바 뒤까지 확장하고 (Android 15 의 기본 동작과 통일),
 * 실제 여백은 applySystemBarPadding 으로 직접 처리.
 */
fun Activity.setupEdgeToEdge() {
    @Suppress("DEPRECATION")
    run {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

/** 상태바·내비게이션바·노치(·키보드) 만큼 안쪽 여백을 더함 */
fun View.applySystemBarPadding(includeIme: Boolean = false) {
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            if (includeIme) types = types or WindowInsets.Type.ime()
            val i = insets.getInsets(types)
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            legacyInsets(insets)
        }
        v.setPadding(baseL + l, baseT + t, baseR + r, baseB + b)
        insets
    }
    requestApplyInsets()
}

@Suppress("DEPRECATION")
private fun legacyInsets(insets: WindowInsets): IntArray = intArrayOf(
    insets.systemWindowInsetLeft,
    insets.systemWindowInsetTop,
    insets.systemWindowInsetRight,
    insets.systemWindowInsetBottom,
)

/** 잠금 화면 위에 표시하고 화면을 켜며, 보이는 동안 꺼지지 않게 함 */
fun Activity.showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

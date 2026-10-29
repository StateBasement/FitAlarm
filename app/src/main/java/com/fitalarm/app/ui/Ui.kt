package com.fitalarm.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 앱 색상. 시스템 라이트/다크 설정을 따르며, 각 화면 onCreate 에서 Palette.apply(this) 로 맞춥니다.
 * 라이트: 흰 바탕 + 옅은 민트, 주색 상쾌한 초록, 포인트 따뜻한 오렌지
 * 다크: 짙은 청록 회색 바탕 + 민트 포인트
 */
object Palette {
    private class Scheme(
        val bg: Int, val surface: Int, val surfaceHi: Int, val outline: Int,
        val text: Int, val textDim: Int, val textFaint: Int,
        val accent: Int, val onAccent: Int, val accentSoft: Int, val onAccentSoft: Int,
        val hero: Int, val onHero: Int, val onHeroDim: Int,
        val warm: Int, val warmSoft: Int, val onWarm: Int,
        val skySoft: Int, val onSky: Int,
        val danger: Int, val warn: Int, val ripple: Int,
        val ringTop: Int, val ringMid: Int, val ringBottom: Int,
    )

    private val LIGHT = Scheme(
        bg = 0xFFF4FAF6.toInt(), surface = 0xFFFFFFFF.toInt(), surfaceHi = 0xFFEEF3F0.toInt(), outline = 0xFFE1EDE6.toInt(),
        text = 0xFF10231A.toInt(), textDim = 0xFF4B5F55.toInt(), textFaint = 0xFF8FA39A.toInt(),
        accent = 0xFF15803D.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentSoft = 0xFFDCF5E5.toInt(), onAccentSoft = 0xFF14532D.toInt(),
        hero = 0xFF15803D.toInt(), onHero = 0xFFFFFFFF.toInt(), onHeroDim = 0xFFE3F7EA.toInt(),
        warm = 0xFFC2410C.toInt(), warmSoft = 0xFFFFEDD5.toInt(), onWarm = 0xFF9A3412.toInt(),
        skySoft = 0xFFE0F2FE.toInt(), onSky = 0xFF075985.toInt(),
        danger = 0xFFB91C1C.toInt(), warn = 0xFFB45309.toInt(), ripple = 0x1F10231A,
        ringTop = 0xFFFFE3CF.toInt(), ringMid = 0xFFFFF4E6.toInt(), ringBottom = 0xFFE2F2FF.toInt(),
    )

    private val DARK = Scheme(
        bg = 0xFF0E1A16.toInt(), surface = 0xFF16241F.toInt(), surfaceHi = 0xFF1E2E28.toInt(), outline = 0xFF24352E.toInt(),
        text = 0xFFE7F2EC.toInt(), textDim = 0xFFA7BDB2.toInt(), textFaint = 0xFF5F786C.toInt(),
        accent = 0xFF4ADE80.toInt(), onAccent = 0xFF052E16.toInt(), accentSoft = 0xFF163A27.toInt(), onAccentSoft = 0xFF86EFAC.toInt(),
        hero = 0xFF14532D.toInt(), onHero = 0xFFECFDF3.toInt(), onHeroDim = 0xFFBBF7D0.toInt(),
        warm = 0xFFFB923C.toInt(), warmSoft = 0xFF3A2410.toInt(), onWarm = 0xFFFDBA74.toInt(),
        skySoft = 0xFF0C2E3F.toInt(), onSky = 0xFF7DD3FC.toInt(),
        danger = 0xFFF87171.toInt(), warn = 0xFFFBBF24.toInt(), ripple = 0x33FFFFFF,
        ringTop = 0xFF2A1630.toInt(), ringMid = 0xFF161A2A.toInt(), ringBottom = 0xFF0E1A16.toInt(),
    )

    @Volatile
    var isDark: Boolean = false
        private set

    private val s: Scheme get() = if (isDark) DARK else LIGHT

    fun apply(ctx: Context) {
        isDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    val BG get() = s.bg
    val SURFACE get() = s.surface
    val SURFACE_HI get() = s.surfaceHi
    val OUTLINE get() = s.outline
    val TEXT get() = s.text
    val TEXT_DIM get() = s.textDim
    val TEXT_FAINT get() = s.textFaint
    val ACCENT get() = s.accent
    val ON_ACCENT get() = s.onAccent
    val ACCENT_SOFT get() = s.accentSoft
    val ON_ACCENT_SOFT get() = s.onAccentSoft
    val HERO get() = s.hero
    val ON_HERO get() = s.onHero
    val ON_HERO_DIM get() = s.onHeroDim
    val WARM get() = s.warm
    val WARM_SOFT get() = s.warmSoft
    val ON_WARM get() = s.onWarm
    val SKY_SOFT get() = s.skySoft
    val ON_SKY get() = s.onSky
    val DANGER get() = s.danger
    val WARN get() = s.warn
    val GOOD get() = s.accent
    val RIPPLE get() = s.ripple
    val RING_TOP get() = s.ringTop
    val RING_MID get() = s.ringMid
    val RING_BOTTOM get() = s.ringBottom
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

/** 색을 입힌 벡터 아이콘 */
fun Context.icon(resId: Int, color: Int, sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageDrawable(getDrawable(resId)?.mutate())
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = lp(dp(sizeDp), dp(sizeDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** 둥근 버튼. filled=true 면 주색 배경, false 면 테두리만 */
fun Context.pillButton(text: String, filled: Boolean, heightDp: Int = 52, onClick: () -> Unit): TextView =
    label(text, 16f, if (filled) Palette.ON_ACCENT else Palette.TEXT, if (filled) Fonts.BOLD else Fonts.MEDIUM).apply {
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

/** 작은 둥근 라벨 */
fun Context.chip(text: String, bg: Int, fg: Int, sizeSp: Float = 12.5f): TextView =
    label(text, sizeSp, fg, Fonts.MEDIUM).apply {
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = rounded(bg, dpf(10))
    }

/** 흰 카드 (제목 선택) */
fun Context.card(title: String? = null, block: LinearLayout.() -> Unit): LinearLayout = vbox {
    setPadding(dp(18), dp(14), dp(18), dp(10))
    background = rounded(Palette.SURFACE, dpf(22), Palette.OUTLINE, dp(1))
    if (title != null) addView(label(title, 13f, Palette.ACCENT, Fonts.BOLD), lp().margins(b = dp(4)))
    block()
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(Palette.OUTLINE)
    layoutParams = lp(MATCH, dp(1)).margins(t = dp(2), b = dp(2))
}

/** 제목(+설명) 왼쪽, 값 오른쪽, 탭하면 onClick */
fun Context.valueRow(title: String, subtitle: String? = null, onClick: () -> Unit): Pair<View, TextView> {
    val value = label("", 15f, Palette.ACCENT, Fonts.MEDIUM)
    val row = hbox {
        setPadding(dp(2), dp(12), dp(2), dp(12))
        background = rippleOnly(12)
        isClickable = true
        setOnClickListener { onClick() }
        addView(vbox {
            addView(label(title, 16f))
            if (subtitle != null) {
                addView(label(subtitle, 12.5f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.2f) }, lp().margins(t = dp(4)))
            }
        }, lp(0, WRAP, 1f))
        addView(value, lp(WRAP, WRAP).margins(l = dp(12)))
    }
    return row to value
}

/** 제목(+설명)과 스위치 한 줄. 줄 어디를 눌러도 토글 */
fun Context.switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View =
    switchRowWithRefs(title, subtitle, checked, onChange).first

fun Context.switchRowWithRefs(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
): Triple<View, Switch, TextView?> {
    val sw = Switch(this).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }
    var sub: TextView? = null
    val row = hbox {
        setPadding(dp(2), dp(10), 0, dp(10))
        background = rippleOnly(12)
        isClickable = true
        setOnClickListener { sw.toggle() }
        addView(vbox {
            addView(label(title, 16f))
            if (subtitle != null) {
                sub = label(subtitle, 12.5f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.2f) }
                addView(sub, lp().margins(t = dp(4)))
            }
        }, lp(0, WRAP, 1f))
        addView(sw, lp(WRAP, WRAP).margins(l = dp(12)))
    }
    return Triple(row, sw, sub)
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * 모든 Android 버전에서 화면을 상태바/내비게이션바 뒤까지 확장하고 (Android 15 의 기본 동작과 통일),
 * 실제 여백은 applySystemBarPadding 으로 직접 처리. 밝은 화면이면 상태바 아이콘을 어둡게.
 */
fun Activity.setupEdgeToEdge(lightBars: Boolean = !Palette.isDark) {
    @Suppress("DEPRECATION")
    run {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
    val decor = window.decorView
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        decor.windowInsetsController?.setSystemBarsAppearance(if (lightBars) mask else 0, mask)
    } else {
        legacyEdgeToEdge(decor, lightBars)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

/** Android 10 이하 전용 (예전 방식 플래그) */
@Suppress("DEPRECATION")
private fun legacyEdgeToEdge(decor: View, lightBars: Boolean) {
    var flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    if (lightBars) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    decor.systemUiVisibility = flags
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

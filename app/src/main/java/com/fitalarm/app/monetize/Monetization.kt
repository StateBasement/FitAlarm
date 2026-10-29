package com.fitalarm.app.monetize

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.fitalarm.app.core.SupportPromptState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 광고·후원(응원) 기능의 스위치. 지금은 둘 다 꺼져 있어 사용자에게 아무것도 보이지 않습니다.
 *
 * 켜는 순서는 docs/monetization.md 참고:
 *  - 광고: AdMob 계정·광고 단위 만들기 → AdMob SDK 추가 → AdProvider 구현 연결 → ADS_ENABLED = true
 *  - 후원: Play Console 에 인앱 상품 만들기 → Play 결제 라이브러리 추가 → SupportStore 구현 연결 → SUPPORT_ENABLED = true
 *
 * 개발자용 미리보기: 설정 > 정보 > "버전"을 7번 누르면 광고 자리(점선 상자)와 응원 카드·팝업이 보입니다.
 */
object MonetizationConfig {
    const val ADS_ENABLED = false
    const val SUPPORT_ENABLED = false
}

/**
 * 광고가 들어갈 수 있는 자리. Google Play 광고 정책상 잠금 화면 위에 뜨는 화면
 * (알람 울림, 운동 미션, 보너스 팝업)에는 광고를 넣지 않고, 누르는 버튼 바로 옆도 피했습니다.
 */
enum class AdPlacement(val title: String) {
    ALARM_LIST_BOTTOM("알람 목록 아래"),
    RECORDS_BOTTOM("기록 화면 아래"),
    SETTINGS_BOTTOM("설정 화면 아래"),
}

/** 광고 SDK 연결 지점. AdMob 등을 붙일 때 이 인터페이스를 구현합니다 */
interface AdProvider {
    /** container 에 배너를 넣었으면 true, 넣을 광고가 없으면 false (자리를 숨김) */
    fun attachBanner(container: FrameLayout, placement: AdPlacement): Boolean
    fun detach(container: FrameLayout) = container.removeAllViews()
}

/** 광고 없음 (현재 기본값) */
object NoAdProvider : AdProvider {
    override fun attachBanner(container: FrameLayout, placement: AdPlacement): Boolean = false
}

/** 미리보기: 실제 광고 대신 점선 상자로 자리만 보여줌 */
object PreviewAdProvider : AdProvider {
    override fun attachBanner(container: FrameLayout, placement: AdPlacement): Boolean {
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        val box = TextView(ctx).apply {
            text = "광고 자리 · ${placement.title} · 배너 320×50"
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            gravity = Gravity.CENTER
            background = ShapeDrawable(RoundRectShape(FloatArray(8) { 12 * d }, null, null)).apply {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * d
                paint.color = 0xFF9AA3AD.toInt()
                paint.pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
            }
        }
        container.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt()))
        container.setBackgroundColor(Color.TRANSPARENT)
        return true
    }
}

/** 응원 상품. price 는 Play 에서 받아온 현지 가격 (연결 전에는 null → 예시 가격 표시) */
data class SupportProduct(val id: String, val title: String, val examplePrice: String, val price: String? = null)

enum class PurchaseResult { SUCCESS, CANCELLED, UNAVAILABLE, ERROR }

/**
 * 후원 결제 연결 지점. Google Play 결제 정책상 앱 안의 후원은 Play 결제(소모성 인앱 상품)로 받아야 하며,
 * 토스·계좌·외부 후원 사이트로 보내는 링크나 버튼은 넣을 수 없습니다.
 */
interface SupportStore {
    val isReady: Boolean
    fun products(): List<SupportProduct>
    fun purchase(activity: Activity, productId: String, onResult: (PurchaseResult) -> Unit)
}

/** 결제 미연결 (현재 기본값) */
object DisabledSupportStore : SupportStore {
    override val isReady: Boolean = false
    override fun products(): List<SupportProduct> = Monetization.DEFAULT_PRODUCTS
    override fun purchase(activity: Activity, productId: String, onResult: (PurchaseResult) -> Unit) =
        onResult(PurchaseResult.UNAVAILABLE)
}

/** 미리보기: 결제 없이 성공한 것처럼 동작 (감사 화면·광고 사라짐 확인용) */
object PreviewSupportStore : SupportStore {
    override val isReady: Boolean = true
    override fun products(): List<SupportProduct> = Monetization.DEFAULT_PRODUCTS
    override fun purchase(activity: Activity, productId: String, onResult: (PurchaseResult) -> Unit) =
        onResult(PurchaseResult.SUCCESS)
}

object Monetization {
    private const val PREFS = "fit_alarm_monetize"
    private const val KEY_PREVIEW = "preview"
    private const val KEY_SUPPORTER = "supporter"
    private const val KEY_NEVER = "prompt_never"
    private const val KEY_LAST = "prompt_last"
    private const val KEY_SHOWN = "prompt_shown"

    /** Play Console 에 같은 ID 로 소모성 인앱 상품을 만들면 됩니다 */
    val DEFAULT_PRODUCTS = listOf(
        SupportProduct("support_coffee", "커피 한 잔", "₩1,100"),
        SupportProduct("support_cheer", "든든한 응원", "₩3,300"),
        SupportProduct("support_big", "큰 응원", "₩5,500"),
    )

    /** 실제 결제를 연결하면 여기에 구현체를 넣음 (예: PlayBillingSupportStore) */
    @Volatile
    var realStore: SupportStore = DisabledSupportStore

    /** 실제 광고 SDK 를 연결하면 여기에 구현체를 넣음 (예: AdMobProvider) */
    @Volatile
    var realAds: AdProvider = NoAdProvider

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPreview(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PREVIEW, false)
    fun setPreview(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_PREVIEW, on).apply()

    fun isSupporter(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SUPPORTER, false)
    fun setSupporter(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_SUPPORTER, on).apply()

    /** 후원자에게는 광고를 보여주지 않음 */
    fun adsActive(ctx: Context): Boolean =
        !isSupporter(ctx) && (isPreview(ctx) || MonetizationConfig.ADS_ENABLED)

    fun adProvider(ctx: Context): AdProvider = when {
        !adsActive(ctx) -> NoAdProvider
        isPreview(ctx) -> PreviewAdProvider
        else -> realAds
    }

    fun supportVisible(ctx: Context): Boolean = isPreview(ctx) || MonetizationConfig.SUPPORT_ENABLED

    fun store(ctx: Context): SupportStore = if (isPreview(ctx)) PreviewSupportStore else realStore

    fun promptState(ctx: Context): SupportPromptState {
        val p = prefs(ctx)
        val last = p.getLong(KEY_LAST, -1L)
        return SupportPromptState(
            neverShow = p.getBoolean(KEY_NEVER, false),
            supporter = isSupporter(ctx),
            lastShown = if (last < 0) null else LocalDate.ofEpochDay(last),
            shownMilestones = p.getStringSet(KEY_SHOWN, emptySet())?.toSet().orEmpty(),
        )
    }

    fun savePromptState(ctx: Context, s: SupportPromptState) {
        prefs(ctx).edit()
            .putBoolean(KEY_NEVER, s.neverShow)
            .putLong(KEY_LAST, s.lastShown?.toEpochDay() ?: -1L)
            .putStringSet(KEY_SHOWN, s.shownMilestones)
            .apply()
    }

    /** 미리보기용: 후원자 상태와 팝업 기록을 지움 */
    fun resetForPreview(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_SUPPORTER).remove(KEY_NEVER).remove(KEY_LAST).remove(KEY_SHOWN)
            .apply()
    }

    fun installDate(ctx: Context): LocalDate = try {
        val t = ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
        Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (e: Exception) {
        LocalDate.now()
    }
}

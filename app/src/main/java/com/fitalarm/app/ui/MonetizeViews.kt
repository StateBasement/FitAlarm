package com.fitalarm.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fitalarm.app.R
import com.fitalarm.app.core.DayResult
import com.fitalarm.app.core.RecordStats
import com.fitalarm.app.core.SupportMilestone
import com.fitalarm.app.core.SupportPromptRule
import com.fitalarm.app.data.RecordRepository
import com.fitalarm.app.monetize.AdPlacement
import com.fitalarm.app.monetize.AdProvider
import com.fitalarm.app.monetize.Monetization
import com.fitalarm.app.monetize.MonetizationConfig
import com.fitalarm.app.monetize.PurchaseResult
import com.fitalarm.app.monetize.SupportProduct
import java.time.LocalDate

/**
 * 광고가 들어갈 자리. 광고가 없거나(꺼짐·후원자·채울 광고 없음) 아직 오지 않았으면 자리 자체가 사라져(GONE) 빈칸이 생기지 않습니다.
 * 탭마다 한 개를 계속 재사용하고, 이미 붙은 광고는 화면을 새로 그려도 다시 요청하지 않습니다
 * (광고 교체 주기는 AdMob 설정을 따름).
 */
class AdSlotView(context: Context, private val placement: AdPlacement) : FrameLayout(context) {
    private var attached: AdProvider? = null

    init {
        visibility = View.GONE
    }

    fun refresh() {
        val provider = Monetization.adProvider(context)
        if (provider === attached) return
        release()
        if (provider.attachBanner(this, placement)) attached = provider
    }

    /** 광고 뷰를 정리 (화면을 닫을 때, 후원자가 됐을 때) */
    fun release() {
        attached?.detach(this)
        attached = null
        visibility = View.GONE
    }
}

/** 응원(후원) 결제 흐름 공통 처리 */
object SupportFlow {

    fun purchase(activity: Activity, product: SupportProduct, onChanged: () -> Unit) {
        val store = Monetization.store(activity)
        if (!store.isReady) {
            activity.toast("결제를 준비하고 있어요. 잠시 후 다시 시도해 주세요")
            return
        }
        store.purchase(activity, product.id) { result ->
            activity.runOnUiThread {
                when (result) {
                    PurchaseResult.SUCCESS -> {
                        Monetization.setSupporter(activity, true)
                        onChanged()
                        thankYou(activity)
                    }
                    PurchaseResult.CANCELLED -> Unit
                    PurchaseResult.UNAVAILABLE -> activity.toast("지금은 결제를 사용할 수 없어요")
                    PurchaseResult.ERROR -> activity.toast("결제 중 문제가 생겼어요. 결제되지 않았다면 다시 시도해 주세요")
                }
            }
        }
    }

    private fun thankYou(activity: Activity) {
        val adsNote = if (MonetizationConfig.ADS_ENABLED || Monetization.isPreview(activity)) "\n이제 광고가 보이지 않아요." else ""
        AlertDialog.Builder(activity)
            .setTitle("응원해 주셔서 감사해요!")
            .setMessage("보내주신 응원으로 더 좋은 피타람을 만들게요.$adsNote" +
                if (Monetization.isPreview(activity)) "\n\n(미리보기라 실제 결제는 되지 않았어요)" else "")
            .setPositiveButton("확인", null)
            .show()
    }

    fun openStoreReview(activity: Activity) {
        val pkg = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (e2: Exception) {
                activity.toast("Play 스토어를 열 수 없어요")
            }
        }
    }

    /** 상품 한 줄: 이름 + 가격 버튼 */
    fun productRow(activity: Activity, p: SupportProduct, onChanged: () -> Unit): View = with(activity) {
        hbox {
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = ripple(rounded(Palette.BG, dpf(18), Palette.OUTLINE, dp(1)))
            isClickable = true
            contentDescription = "${p.title} ${p.price ?: p.examplePrice} 응원하기"
            setOnClickListener { purchase(activity, p, onChanged) }
            addView(icon(R.drawable.ic_flame, Palette.WARM, 22), lp(dp(22), dp(22)).margins(r = dp(12)))
            addView(label(p.title, 16f, Palette.TEXT, Fonts.MEDIUM), lp(0, WRAP, 1f))
            addView(chip(p.price ?: p.examplePrice, Palette.ACCENT, Palette.ON_ACCENT, 14f))
        }
    }
}

/**
 * 응원 팝업 (바텀시트). 바깥을 누르거나 "나중에"로 바로 닫히고, "다시 보지 않기"를 누르면 다시 뜨지 않습니다.
 * 잠금 화면 위의 알람·미션 화면에서는 절대 띄우지 않고, 앱의 기본 화면에서만 띄웁니다.
 */
object SupportSheet {

    fun show(activity: Activity, milestone: SupportMilestone?, onChanged: () -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = with(activity) {
            vbox {
                setPadding(dp(22), dp(12), dp(22), dp(18))
                background = GradientDrawable().apply {
                    setColor(Palette.SURFACE)
                    val r = dpf(30)
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
            }
        }
        with(activity) {
            sheet.addView(View(this).apply { background = rounded(Palette.OUTLINE, dpf(3)) },
                lp(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            sheet.addView(label("개발자 응원하기 · 원할 때만", 13f, Palette.WARM, Fonts.BOLD), lp().margins(t = dp(14)))
            sheet.addView(label(milestone?.headline ?: "피타람을 응원해 주세요", 23f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(6)))
            sheet.addView(label(
                milestone?.message ?: "광고 없이 꾸준히 만들 수 있도록 커피 한 잔으로 응원해 주세요.",
                15f, Palette.TEXT_DIM,
            ).apply { setLineSpacing(0f, 1.3f) }, lp().margins(t = dp(6), b = dp(14)))

            val changedAndClose: () -> Unit = {
                dialog.dismiss()
                onChanged()
            }
            for (p in Monetization.store(this).products()) {
                sheet.addView(SupportFlow.productRow(this, p, changedAndClose), lp().margins(b = dp(8)))
            }
            val adsOn = MonetizationConfig.ADS_ENABLED || Monetization.isPreview(this)
            val note = if (adsOn) "결제는 Google Play 로 안전하게 진행돼요. 응원하면 광고가 사라져요."
            else "결제는 Google Play 로 안전하게 진행돼요. 응원은 기능을 열지 않는 순수 후원이에요."
            sheet.addView(label(note, 12f, Palette.TEXT_DIM).apply {
                setLineSpacing(0f, 1.2f)
            }, lp().margins(t = dp(2), b = dp(12)))

            sheet.addView(hbox {
                addView(pillButton("리뷰 남기기", filled = false, heightDp = 50) {
                    dialog.dismiss()
                    SupportFlow.openStoreReview(this@with)
                }, lp(0, WRAP, 1f).margins(r = dp(5)))
                addView(pillButton("나중에", filled = false, heightDp = 50) { dialog.dismiss() }, lp(0, WRAP, 1f).margins(l = dp(5)))
            }, lp())
            sheet.addView(label("다시 보지 않기", 13f, Palette.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
                background = rippleOnly(12)
                setOnClickListener {
                    Monetization.savePromptState(this@with, Monetization.promptState(this@with).copy(neverShow = true))
                    dialog.dismiss()
                    toast("응원 팝업을 더 이상 띄우지 않을게요. 설정에서 언제든 응원할 수 있어요")
                }
            }, lp())
        }

        // 아래 내비게이션바만큼 여백 (위쪽 여백은 넣지 않음)
        val baseBottom = sheet.paddingBottom
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + bottom)
            insets
        }

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        sheet.translationY = activity.dpf(400)
        sheet.animate().translationY(0f).setDuration(260).start()
    }
}

/** 앱을 열었을 때 의미 있는 순간이면 응원 팝업을 한 번 띄움 */
object SupportPromptRunner {
    fun maybeShow(activity: MainActivity) {
        if (!Monetization.supportVisible(activity)) return
        val all = RecordRepository.all(activity)
        val today = LocalDate.now()
        val streak = RecordStats.currentStreak(all, today)
        val successDays = RecordStats.days(all).values.count { it.result == DayResult.SUCCESS }
        val state = Monetization.promptState(activity)
        val milestone = SupportPromptRule.pick(state, today, Monetization.installDate(activity), streak, successDays) ?: return
        Monetization.savePromptState(activity, SupportPromptRule.afterShown(state, milestone, today, streak))
        SupportSheet.show(activity, milestone) { activity.refreshCurrent() }
    }
}

/** 설정 화면의 "개발자 응원하기" 카드 */
fun MainActivity.supportCard(onChanged: () -> Unit): LinearLayout = card("개발자 응원하기") {
    val supporter = Monetization.isSupporter(this@supportCard)
    val adsOn = MonetizationConfig.ADS_ENABLED || Monetization.isPreview(this@supportCard)
    val text = when {
        supporter -> "응원해 주셔서 감사해요! 보내주신 응원으로 더 좋은 앱을 만들게요." +
            if (adsOn) " 광고는 보이지 않아요." else ""
        adsOn -> "광고 없이 꾸준히 만들 수 있도록 응원해 주세요. 응원하면 광고가 사라져요."
        else -> "피타람이 도움이 됐다면 커피 한 잔으로 응원해 주세요."
    }
    addView(label(text, 13.5f, if (supporter) Palette.ACCENT else Palette.TEXT_DIM, if (supporter) Fonts.MEDIUM else null).apply {
        setLineSpacing(0f, 1.25f)
    }, lp().margins(t = dp(2), b = dp(10)))
    for (p in Monetization.store(this@supportCard).products()) {
        addView(SupportFlow.productRow(this@supportCard, p, onChanged), lp().margins(b = dp(8)))
    }
    val (reviewRow, reviewValue) = valueRow("Play 스토어에 리뷰 남기기") { SupportFlow.openStoreReview(this@supportCard) }
    reviewValue.text = "열기"
    addView(reviewRow)
}

/** 개발자용 미리보기 카드 (설정 > 정보 > 버전 7번 탭으로 켬) */
fun MainActivity.monetizePreviewCard(onChanged: () -> Unit): LinearLayout = card("수익화 미리보기 (개발자용)") {
    addView(label(
        "실제 광고·결제 없이 위치와 흐름만 보여줘요. 점선 상자가 광고 자리이고, 응원 결제는 성공한 것처럼 동작해요. " +
            "사용자에게는 보이지 않는 화면이에요.",
        13f, Palette.TEXT_DIM,
    ).apply { setLineSpacing(0f, 1.25f) }, lp().margins(t = dp(2), b = dp(10)))
    addView(pillButton("응원 팝업 보기", filled = true, heightDp = 46) {
        SupportSheet.show(this@monetizePreviewCard, SupportMilestone("preview", "7일 연속 성공!", "꾸준히 해내고 있어요. 피타람이 아침에 도움이 되고 있다면 응원해 주세요."), onChanged)
    }, lp().margins(b = dp(8)))
    addView(hbox {
        addView(pillButton("후원 상태 초기화", filled = false, heightDp = 46) {
            Monetization.resetForPreview(this@monetizePreviewCard)
            toast("후원자 상태와 팝업 기록을 지웠어요")
            onChanged()
        }, lp(0, WRAP, 1f).margins(r = dp(5)))
        addView(pillButton("미리보기 끄기", filled = false, heightDp = 46) {
            Monetization.setPreview(this@monetizePreviewCard, false)
            toast("수익화 미리보기를 껐어요")
            onChanged()
        }, lp(0, WRAP, 1f).margins(l = dp(5)))
    }, lp().margins(b = dp(8)))
}

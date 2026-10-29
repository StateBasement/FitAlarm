package com.fitalarm.app.monetize

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** 광고 SDK 준비가 끝나면 알려 받을 화면 (메인 스레드에서 호출) */
interface AdsHost {
    fun onAdsReady()
}

/**
 * 광고 시작 순서: 광고 동의 확인(UMP) → 광고 SDK 초기화(백그라운드 스레드) → 배너 요청.
 *
 * - 동의 화면은 유럽·영국 등 법적으로 동의가 필요한 지역의 사용자에게만 뜹니다. 한국 사용자에게는 뜨지 않습니다.
 * - 앱의 기본 화면(MainActivity)에서만 시작합니다. 잠금 화면 위의 알람·미션 화면에서는 광고 SDK 를 쓰지 않습니다.
 * - 후원자이거나 광고가 꺼져 있으면 아무것도 하지 않습니다.
 */
object AdMob {
    private const val TAG = "FitAlarmAds"
    private val main = Handler(Looper.getMainLooper())
    private val initStarted = AtomicBoolean(false)

    @Volatile
    var ready = false
        private set

    // 아래 두 값은 메인 스레드에서만 다룸
    private var consentRequested = false
    private var host: WeakReference<Activity>? = null

    fun start(activity: Activity) {
        if (!MonetizationConfig.ADS_ENABLED || Monetization.isSupporter(activity)) return
        host = WeakReference(activity)
        if (ready) {
            notifyReady()
            return
        }
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        if (!consentRequested) {
            consentRequested = true
            // 동의 정보는 실행할 때마다 새로 확인해야 함 (Google 권장)
            consent.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                {
                    val a = liveHost()
                    if (a == null) {
                        consentRequested = false   // 화면이 사라졌으면 다음에 다시 확인
                    } else {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(a) { error ->
                            if (error != null) Log.w(TAG, "consent form: ${error.message}")
                            if (consent.canRequestAds()) liveHost()?.let { initialize(it) }
                        }
                    }
                },
                { error ->
                    // 오프라인 등: 다음에 앱을 열 때 다시 확인. 이전에 받은 동의가 있으면 그대로 진행
                    Log.w(TAG, "consent info: ${error.message}")
                    consentRequested = false
                    if (consent.canRequestAds()) liveHost()?.let { initialize(it) }
                },
            )
        }
        // 지난 실행에서 이미 동의를 받았으면 새 확인을 기다리지 않고 바로 시작
        if (consent.canRequestAds()) initialize(activity)
    }

    /** 유럽 등에서 사용자가 광고 동의를 바꿀 수 있는 메뉴를 보여줘야 하는지 */
    fun privacyOptionsRequired(activity: Activity): Boolean =
        MonetizationConfig.ADS_ENABLED &&
            UserMessagingPlatform.getConsentInformation(activity).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "privacy options: ${error.message}")
        }
    }

    private fun initialize(activity: Activity) {
        if (!initStarted.compareAndSet(false, true)) return
        // 초기화는 백그라운드 스레드에서 해야 함 (메인 스레드에서 하면 앱이 멈출 수 있음)
        Thread({
            try {
                MobileAds.initialize(activity, InitializationConfig.Builder(AdMobIds.APP_ID).build()) {
                    ready = true
                    main.post { notifyReady() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ads init failed", t)
                initStarted.set(false)
            }
        }, "fitalarm-ads-init").start()
    }

    private fun liveHost(): Activity? =
        host?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun notifyReady() {
        (liveHost() as? AdsHost)?.onAdsReady()
    }
}

/**
 * AdMob 배너. 광고가 실제로 도착했을 때만 자리를 보여주고, 채울 광고가 없으면 자리를 숨깁니다(빈칸 없음).
 * 광고 SDK 는 결과를 백그라운드 스레드에서 알려주므로 화면 변경은 메인 스레드로 넘깁니다.
 */
object AdMobProvider : AdProvider {
    private const val TAG = "FitAlarmAds"

    /** 화면 좌우 여백 합 (각 탭의 내용 여백 20dp × 2) */
    private const val SIDE_PADDING_DP = 40

    override fun attachBanner(container: FrameLayout, placement: AdPlacement): Boolean {
        if (!AdMob.ready) return false   // 준비되면 AdsHost.onAdsReady 에서 다시 요청
        val activity = container.context as? Activity ?: return false

        val adView = AdView(activity)
        container.removeAllViews()
        container.addView(adView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL,
        ))
        container.visibility = View.GONE

        val m = activity.resources.displayMetrics
        val widthDp = ((m.widthPixels / m.density).toInt() - SIDE_PADDING_DP).coerceAtLeast(1)
        val size = AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
        val request = BannerAdRequest.Builder(AdMobIds.banner(placement), size).build()
        adView.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) {
                container.post { if (adView.parent === container) container.visibility = View.VISIBLE }
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.i(TAG, "banner ${placement.name}: ${adError.code} ${adError.message}")
                container.post { if (adView.parent === container) container.visibility = View.GONE }
            }
        })
        return true
    }

    override fun detach(container: FrameLayout) {
        for (i in 0 until container.childCount) (container.getChildAt(i) as? AdView)?.destroy()
        container.removeAllViews()
    }
}

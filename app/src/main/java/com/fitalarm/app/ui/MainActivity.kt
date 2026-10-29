package com.fitalarm.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.alarm.Notifications
import com.fitalarm.app.holiday.HolidaySync
import com.fitalarm.app.monetize.AdMob
import com.fitalarm.app.monetize.AdPlacement
import com.fitalarm.app.monetize.AdsHost

/** 주요 화면: 아래 탭으로 알람 / 기록 / 설정 전환 */
class MainActivity : Activity(), AdsHost {

    private lateinit var pageHost: FrameLayout
    private val tabViews = ArrayList<Pair<ImageView, TextView>>()
    private var currentTab = TAB_ALARMS
    private val handler = Handler(Looper.getMainLooper())

    lateinit var alarmsPage: AlarmsPage
        private set
    lateinit var recordsPage: RecordsPage
        private set
    lateinit var settingsPage: SettingsPage
        private set

    private val ticker = object : Runnable {
        override fun run() {
            if (currentTab == TAB_ALARMS) alarmsPage.updateNextAlarm()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 처음 실행(또는 약관이 바뀐 뒤)에는 안전 안내·이용약관 동의 화면부터
        if (!OnboardingActivity.isAccepted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        Palette.apply(this)
        setupEdgeToEdge()
        Notifications.ensureChannels(this)
        HolidaySync.schedulePeriodic(this)

        alarmsPage = AlarmsPage(this)
        recordsPage = RecordsPage(this)
        settingsPage = SettingsPage(this)
        setContentView(buildUi())
        selectTab(savedInstanceState?.getInt(KEY_TAB, TAB_ALARMS) ?: TAB_ALARMS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        // 권한·공휴일 변경 등을 반영해 항상 최신 상태로 재등록 (알람 수가 적어 비용이 작음)
        AlarmScheduler(this).rescheduleAll()
        HolidaySync.syncIfStale(this)
        refreshCurrent()
        handler.post(ticker)
        maybeAskNotificationPermission()
        // 광고: 동의 확인(유럽 등에서만 동의 화면) → 광고 SDK 준비 → onAdsReady 에서 배너 요청
        AdMob.start(this)
        // 의미 있는 순간(연속 성공 등)에만 응원 팝업 한 번 (기본 꺼짐, 다른 창이 떠 있으면 건너뜀)
        handler.postDelayed({ if (!isFinishing && hasWindowFocus()) SupportPromptRunner.maybeShow(this) }, 900L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        settingsPage.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // 첫 실행 안내로 바로 넘어간 경우에는 탭을 만들지 않았음
        if (::alarmsPage.isInitialized) {
            listOf(alarmsPage, recordsPage, settingsPage).forEach { it.adSlot.release() }
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 광고 SDK 준비 완료: 지금 보고 있는 탭에만 배너 요청 (다른 탭은 열 때 요청) */
    override fun onAdsReady() {
        if (isFinishing || isDestroyed || !::alarmsPage.isInitialized) return
        currentPage().adSlot.refresh()
    }

    private fun currentPage(): Page = when (currentTab) {
        TAB_RECORDS -> recordsPage
        TAB_SETTINGS -> settingsPage
        else -> alarmsPage
    }

    private fun buildUi(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        pageHost = FrameLayout(this)
        root.addView(pageHost, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE)
        }
        val items = listOf(
            Triple(R.drawable.ic_alarm, "알람", TAB_ALARMS),
            Triple(R.drawable.ic_calendar, "기록", TAB_RECORDS),
            Triple(R.drawable.ic_settings, "설정", TAB_SETTINGS),
        )
        for ((iconRes, title, index) in items) {
            val ic = icon(iconRes, Palette.TEXT_DIM)
            val tx = label(title, 12f, Palette.TEXT_DIM, Fonts.MEDIUM)
            val tab = vbox {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = rippleOnly(16)
                isClickable = true
                contentDescription = title
                setOnClickListener { selectTab(index) }
                addView(ic)
                addView(tx, lp(WRAP, WRAP).margins(t = dp(4)))
            }
            tabViews.add(ic to tx)
            bar.addView(tab, lp(0, WRAP, 1f))
        }
        val barWrap = vbox {
            setBackgroundColor(Palette.SURFACE)
            addView(View(this@MainActivity).apply { setBackgroundColor(Palette.OUTLINE) }, lp(MATCH, dp(1)))
            addView(bar, lp())
        }
        // 아래 내비게이션바만큼만 여백 (위쪽 여백은 각 페이지가 처리)
        barWrap.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        root.addView(barWrap, lp())
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetTop
            }
            pageHost.setPadding(0, top, 0, 0)
            insets   // 소비하지 않으므로 아래 탭 바에도 전달됨
        }
        return root
    }

    fun selectTab(index: Int) {
        currentTab = index
        pageHost.removeAllViews()
        val page = currentPage()
        pageHost.addView(page.root, FrameLayout.LayoutParams(MATCH, MATCH))
        tabViews.forEachIndexed { i, (ic, tx) ->
            val color = if (i == index) Palette.ACCENT else Palette.TEXT_DIM
            ic.imageTintList = android.content.res.ColorStateList.valueOf(color)
            tx.setTextColor(color)
            tx.typeface = if (i == index) Fonts.BOLD else Fonts.MEDIUM
        }
        page.refresh()
    }

    fun refreshCurrent() = currentPage().refresh()

    // ------------------------------------------------------------------ 권한·결과 전달

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            HolidaySync.syncAsync(this) { refreshCurrent() }
        }
        refreshCurrent()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        settingsPage.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        const val TAB_ALARMS = 0
        const val TAB_RECORDS = 1
        const val TAB_SETTINGS = 2
        private const val KEY_TAB = "tab"

        const val REQ_NOTIFICATIONS = 101
        const val REQ_CAMERA = 102
        const val REQ_CALENDAR = 103
        const val REQ_BACKUP_EXPORT = 201
        const val REQ_BACKUP_IMPORT = 202

        const val UI_PREFS = "fit_alarm_ui"
        const val KEY_CAMERA_ASKED = "camera_asked"
        const val KEY_CALENDAR_ASKED = "calendar_asked"
        private const val KEY_NOTIF_ASKED = "notif_asked"
    }
}

/** 탭 하나 */
abstract class Page(protected val host: MainActivity, placement: AdPlacement) {
    /** 이 탭 맨 아래의 광고 자리 (탭마다 하나를 계속 재사용) */
    val adSlot = AdSlotView(host, placement)
    abstract val root: View
    abstract fun refresh()
}

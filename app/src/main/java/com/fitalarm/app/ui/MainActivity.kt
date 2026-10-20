package com.fitalarm.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.alarm.Notifications
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.Days
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.RoutineCodec
import com.fitalarm.app.core.RoutineStep
import com.fitalarm.app.core.describe
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.systemService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 알람 목록 화면 */
class MainActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var bannerContainer: LinearLayout
    private lateinit var nextAlarmText: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private var askedNotificationPermission = false

    private val ticker = object : Runnable {
        override fun run() {
            updateNextAlarmText()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        Notifications.ensureChannels(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 권한 변경 등을 반영해 항상 최신 상태로 재등록 (알람 수가 적어 비용이 작음)
        AlarmScheduler(this).rescheduleAll()
        refresh()
        handler.post(ticker)
        maybeAskNotificationPermission()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ 화면 구성

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }

        val content = vbox {
            setPadding(dp(20), dp(12), dp(20), dp(120))
        }
        val header = hbox {
            addView(label("알람", 32f, Palette.TEXT, Fonts.BOLD))
            addView(spacer())
            addView(pillButton("운동 연습", filled = false, heightDp = 40) { showPracticePicker() })
        }
        content.addView(header, lp().margins(t = dp(8)))

        nextAlarmText = label("", 14f, Palette.TEXT_DIM)
        content.addView(nextAlarmText, lp().margins(t = dp(6), b = dp(16)))

        bannerContainer = vbox()
        content.addView(bannerContainer, lp())

        listContainer = vbox()
        content.addView(listContainer, lp())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val fab = label("+", 32f, Palette.ON_ACCENT, Fonts.LIGHT).apply {
            gravity = Gravity.CENTER
            background = ripple(oval(Palette.ACCENT))
            elevation = dpf(6)
            contentDescription = "알람 추가"
            setOnClickListener { openEditor(0) }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(24), dp(24))
        })

        root.applySystemBarPadding()
        return root
    }

    private fun refresh() {
        val alarms = AlarmRepository.getAll(this)
        renderBanners(alarms)
        listContainer.removeAllViews()
        if (alarms.isEmpty()) {
            listContainer.addView(emptyState(), lp().margins(t = dp(48)))
        } else {
            alarms.forEach { listContainer.addView(alarmCard(it), lp().margins(b = dp(12))) }
        }
        updateNextAlarmText()
    }

    private fun emptyState(): View = vbox {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(label("아직 알람이 없어요", 18f, Palette.TEXT, Fonts.MEDIUM))
        addView(label("오른쪽 아래 + 버튼으로 알람을 추가하고\n운동 미션을 설정해 보세요", 14f, Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }, lp(WRAP, WRAP).margins(t = dp(8)))
    }

    private fun updateNextAlarmText() {
        val now = ZonedDateTime.now()
        val next = AlarmRepository.getAll(this)
            .mapNotNull { AlarmMath.nextTriggerMillis(it, now) }
            .minOrNull()
        nextAlarmText.text = if (next == null) "켜진 알람이 없습니다"
        else "다음 알람까지 " + AlarmMath.describeRemaining(System.currentTimeMillis(), next)
    }

    private fun alarmCard(a: Alarm): View {
        val use24 = DateFormat.is24HourFormat(this)
        val on = a.enabled
        val main = if (on) Palette.TEXT else Palette.TEXT_FAINT

        val card = hbox {
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = ripple(rounded(Palette.SURFACE, dpf(22)))
            isClickable = true
            setOnClickListener { openEditor(a.id) }
            setOnLongClickListener {
                confirmDelete(a)
                true
            }
        }

        val left = vbox()
        val (ampm, hm) = AlarmMath.formatTimeParts(a.hour, a.minute, use24)
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            if (ampm != null) addView(label(ampm, 16f, main), lp(WRAP, WRAP).margins(r = dp(6), b = dp(6)))
            addView(label(hm, 42f, main, Fonts.LIGHT))
        }
        left.addView(timeRow)

        // 요일 또는 "반복 안 함 · 내일"
        val daysRow = hbox()
        if (a.isRepeating) {
            Days.SHORT_LABELS.forEachIndexed { i, s ->
                val set = Days.isSet(a.days, i)
                val color = when {
                    !set -> Palette.TEXT_FAINT
                    on -> Palette.ACCENT
                    else -> Palette.TEXT_DIM
                }
                daysRow.addView(label(s, 13f, color, if (set) Fonts.MEDIUM else null), lp(WRAP, WRAP).margins(r = dp(7)))
            }
        } else {
            daysRow.addView(label(oneShotDayText(a), 13f, Palette.TEXT_DIM))
        }
        left.addView(daysRow, lp().margins(t = dp(6)))

        if (a.label.isNotBlank()) {
            left.addView(label(a.label, 14f, if (on) Palette.TEXT_DIM else Palette.TEXT_FAINT), lp().margins(t = dp(6)))
        }
        if (a.hasMission) {
            val chip = label("운동 미션 · " + a.routine.describe(), 12.5f, if (on) Palette.ACCENT else Palette.TEXT_FAINT, Fonts.MEDIUM).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(if (on) Palette.ACCENT_SOFT else Palette.SURFACE_HI, dpf(12))
                maxLines = 2
            }
            left.addView(chip, lp(WRAP, WRAP).margins(t = dp(8)))
        }
        if (on && a.snoozedUntil > System.currentTimeMillis()) {
            val t = Instant.ofEpochMilli(a.snoozedUntil).atZone(ZoneId.systemDefault())
            left.addView(
                label("다시 알림 " + AlarmMath.formatTime(t.hour, t.minute, use24) + "에 울림", 13f, Palette.WARN),
                lp().margins(t = dp(6)),
            )
        }
        card.addView(left, lp(0, WRAP, 1f))

        val toggle = Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, checked -> this@MainActivity.handler.post { setAlarmEnabled(a, checked) } }
        }
        card.addView(toggle, lp(WRAP, WRAP))
        return card
    }

    private fun oneShotDayText(a: Alarm): String {
        if (!a.enabled) return "반복 안 함"
        val next = AlarmMath.nextOccurrence(a.hour, a.minute, Days.NONE, ZonedDateTime.now())
        val day = if (next.toLocalDate() == LocalDate.now()) "오늘" else "내일"
        return "반복 안 함 · $day"
    }

    private fun setAlarmEnabled(a: Alarm, enabled: Boolean) {
        val saved = AlarmRepository.upsert(this, a.copy(enabled = enabled, snoozedUntil = 0L, snoozeCount = 0))
        val at = AlarmScheduler(this).schedule(saved)
        if (enabled && at != null) {
            toast("알람이 " + AlarmMath.describeRemaining(System.currentTimeMillis(), at) + " 후에 울립니다")
        }
        refresh()
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setTitle("알람 삭제")
            .setMessage(AlarmMath.formatTime(a.hour, a.minute, DateFormat.is24HourFormat(this)) + " 알람을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                AlarmScheduler(this).cancel(a.id)
                AlarmRepository.delete(this, a.id)
                refresh()
            }
            .show()
    }

    private fun openEditor(alarmId: Int) {
        startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_ALARM_ID, alarmId))
    }

    private fun showPracticePicker() {
        val types = ExerciseType.entries
        AlertDialog.Builder(this)
            .setTitle("어떤 운동을 연습할까요? (5회)")
            .setItems(types.map { it.title }.toTypedArray()) { _, which ->
                val routine = listOf(RoutineStep(types[which], PRACTICE_REPS))
                startActivity(
                    Intent(this, MissionActivity::class.java)
                        .putExtra(MissionActivity.EXTRA_PRACTICE_ROUTINE, RoutineCodec.encode(routine))
                )
            }
            .show()
    }

    // ------------------------------------------------------------------ 권한 안내

    private data class Banner(val text: String, val action: () -> Unit)

    private fun renderBanners(alarms: List<Alarm>) {
        val banners = ArrayList<Banner>()
        val nm = systemService<NotificationManager>()
        val am = systemService<AlarmManager>()

        if (!nm.areNotificationsEnabled()) {
            banners.add(Banner("알림이 꺼져 있어 알람 화면이 뜨지 않을 수 있어요. 탭해서 알림을 허용하세요.") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotificationPermission) {
                    askedNotificationPermission = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                } else {
                    openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            banners.add(Banner("정확한 시각에 울리려면 '알람 및 리마인더' 권한이 필요해요. 탭해서 허용하세요.") {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()) {
            banners.add(Banner("잠금 화면에 알람 화면을 띄우려면 '전체 화면 알림' 권한이 필요해요. 탭해서 허용하세요.") {
                openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
        }
        if (alarms.any { it.enabled && it.hasMission } &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            banners.add(Banner("운동 미션에는 카메라 권한이 필요해요. 탭해서 허용하세요.") {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !cameraAskedOnce()) {
                    markCameraAsked()
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                } else {
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })
        }

        bannerContainer.removeAllViews()
        for (b in banners) {
            val v = hbox {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = ripple(rounded(0x26FFC145, dpf(16), 0x66FFC145, dp(1)))
                isClickable = true
                setOnClickListener { b.action() }
                addView(label("!", 18f, Palette.WARN, Fonts.BOLD), lp(WRAP, WRAP).margins(r = dp(12)))
                addView(label(b.text, 14f, Palette.TEXT).apply { setLineSpacing(0f, 1.2f) }, lp(0, WRAP, 1f))
            }
            bannerContainer.addView(v, lp().margins(b = dp(12)))
        }
    }

    private fun cameraAskedOnce(): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(KEY_CAMERA_ASKED, false)

    private fun markCameraAsked() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_CAMERA_ASKED, true).apply()
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** 첫 실행 시 알림 권한을 한 번 요청 (Android 13+) */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        askedNotificationPermission = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 101
        private const val REQ_CAMERA = 102
        private const val PRACTICE_REPS = 5
        const val UI_PREFS = "fit_alarm_ui"
        const val KEY_CAMERA_ASKED = "camera_asked"
        private const val KEY_NOTIF_ASKED = "notif_asked"
    }
}

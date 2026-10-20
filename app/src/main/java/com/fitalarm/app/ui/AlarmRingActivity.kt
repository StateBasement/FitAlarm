package com.fitalarm.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.fitalarm.app.alarm.AlarmService
import com.fitalarm.app.alarm.RingState
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.data.AlarmRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 알람이 울릴 때 잠금 화면 위에 뜨는 화면 */
class AlarmRingActivity : Activity() {

    private var alarmId = -1
    private var alarm: Alarm? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seenRinging = false
    private var boundAt = 0L
    private var backCallback: Any? = null   // API 33+ OnBackInvokedCallback 보관
    private var visible = false             // onStart~onStop

    private lateinit var dateText: TextView
    private lateinit var ampmText: TextView
    private lateinit var clockText: TextView
    private lateinit var labelText: TextView
    private lateinit var missionBox: LinearLayout
    private lateinit var primaryButton: TextView
    private lateinit var snoozeButton: TextView

    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            handler.postDelayed(this, 1_000L)
        }
    }
    private val ringListener: () -> Unit = { checkStillRinging() }
    private val graceCheck = Runnable { checkStillRinging() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setupEdgeToEdge()
        setContentView(buildUi())
        blockBackButton()
        // 미션 화면이 위에 떠 있는 동안에도 알람 종료를 감지하도록 onCreate~onDestroy 동안 구독
        RingState.addListener(ringListener)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        handler.post(clockTick)
        checkStillRinging()
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(clockTick)
        super.onStop()
    }

    override fun onDestroy() {
        RingState.removeListener(ringListener)
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    private fun handleIntent(i: Intent) {
        alarmId = i.getIntExtra(EXTRA_ALARM_ID, -1)
        boundAt = SystemClock.elapsedRealtime()
        seenRinging = RingState.ringingAlarmId == alarmId

        if (i.getBooleanExtra(EXTRA_START_SERVICE, false) && RingState.ringingAlarmId != alarmId && alarmId > 0) {
            // 대비 경로: 리시버가 서비스를 못 띄웠을 때 화면(포그라운드)에서 시작
            try {
                startForegroundService(AlarmService.startIntent(this, alarmId))
            } catch (e: IllegalStateException) {
                // 무시: 아래 유예 시간 뒤 화면이 닫힘
            }
        }

        alarm = AlarmRepository.get(this, alarmId)
        bind()
        handler.removeCallbacks(graceCheck)
        handler.postDelayed(graceCheck, GRACE_MS)

        if (i.getBooleanExtra(EXTRA_START_MISSION, false) && alarm?.hasMission == true) {
            i.removeExtra(EXTRA_START_MISSION)
            startMission()
        }
    }

    /** 서비스가 알람을 끄거나 다시 알림으로 넘겼으면 화면을 닫음 */
    private fun checkStillRinging() {
        if (isFinishing) return
        if (RingState.ringingAlarmId == alarmId && alarmId > 0) {
            if (!seenRinging) {
                seenRinging = true
                alarm = AlarmRepository.get(this, alarmId)
                bind()
            }
            return
        }
        if (seenRinging || SystemClock.elapsedRealtime() - boundAt >= GRACE_MS) {
            // 미션 화면이 위에 있으면(완료 화면 표시 중) 이 화면만 닫고, 미션 화면은 스스로 닫히게 둠
            if (visible) finishAndRemoveTask() else finish()
        }
    }

    // ------------------------------------------------------------------ 화면

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF2A1630.toInt(), 0xFF140F1F.toInt(), Palette.BG),
            )
        }
        val content = vbox {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(28))
        }

        content.addView(View(this), lp(MATCH, 0, 1f))
        dateText = label("", 17f, Palette.TEXT_DIM)
        content.addView(dateText, lp(WRAP, WRAP))

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        ampmText = label("", 22f, Palette.TEXT_DIM)
        clockText = label("", 92f, Palette.TEXT, Fonts.LIGHT)
        timeRow.addView(ampmText, lp(WRAP, WRAP).margins(r = dp(8), b = dp(16)))
        timeRow.addView(clockText, lp(WRAP, WRAP))
        content.addView(timeRow, lp(WRAP, WRAP).margins(t = dp(4)))

        labelText = label("", 20f, Palette.TEXT, Fonts.MEDIUM).apply { gravity = Gravity.CENTER }
        content.addView(labelText, lp(WRAP, WRAP).margins(t = dp(6)))

        content.addView(View(this), lp(MATCH, 0, 1f))

        missionBox = vbox {
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(0x14FFFFFF, dpf(24), 0x22FFFFFF, dp(1))
        }
        content.addView(missionBox, lp())

        content.addView(View(this), lp(MATCH, 0, 1f))

        primaryButton = pillButton("", filled = true, heightDp = 64) { onPrimary() }.apply { textSize = 19f }
        content.addView(primaryButton, lp())
        snoozeButton = pillButton("", filled = false, heightDp = 56) { onSnooze() }
        content.addView(snoozeButton, lp().margins(t = dp(12)))

        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        root.applySystemBarPadding()
        return root
    }

    private fun renderClock() {
        val now = LocalDateTime.now()
        val (ampm, hm) = AlarmMath.formatTimeParts(now.hour, now.minute, DateFormat.is24HourFormat(this))
        ampmText.visibility = if (ampm == null) View.GONE else View.VISIBLE
        ampmText.text = ampm ?: ""
        clockText.text = hm
        dateText.text = now.format(DATE_FORMAT)
    }

    private fun bind() {
        renderClock()
        val a = alarm
        labelText.text = a?.label?.ifBlank { null } ?: "일어날 시간이에요"

        missionBox.removeAllViews()
        if (a != null && a.hasMission) {
            missionBox.visibility = View.VISIBLE
            missionBox.addView(label("오늘의 운동 미션", 13f, Palette.ACCENT, Fonts.MEDIUM), lp().margins(b = dp(10)))
            a.routine.forEachIndexed { i, step ->
                missionBox.addView(hbox {
                    addView(label("${i + 1}", 14f, Palette.ON_ACCENT, Fonts.BOLD).apply {
                        gravity = Gravity.CENTER
                        background = oval(Palette.ACCENT)
                    }, lp(dp(26), dp(26)).margins(r = dp(12)))
                    addView(label(step.type.title, 17f), lp(0, WRAP, 1f))
                    addView(label("${step.reps}회", 17f, Palette.TEXT, Fonts.MEDIUM))
                }, lp().margins(b = dp(8)))
            }
            primaryButton.text = "운동 미션 시작"
        } else {
            missionBox.visibility = View.GONE
            primaryButton.text = "알람 끄기"
        }

        if (a != null && a.canSnooze()) {
            snoozeButton.visibility = View.VISIBLE
            val left = a.snoozesLeft()
            snoozeButton.text = "다시 알림 · ${a.snoozeMinutes}분" + if (left == null) "" else "  (${left}회 남음)"
        } else {
            snoozeButton.visibility = View.GONE
        }
    }

    private fun onPrimary() {
        val a = alarm
        if (a != null && a.hasMission) {
            startMission()
        } else {
            startService(AlarmService.actionIntent(this, AlarmService.ACTION_DISMISS, alarmId))
            finishAndRemoveTask()
        }
    }

    private fun onSnooze() {
        startService(AlarmService.actionIntent(this, AlarmService.ACTION_SNOOZE, alarmId))
        finishAndRemoveTask()
    }

    private fun startMission() {
        startActivity(Intent(this, MissionActivity::class.java).putExtra(MissionActivity.EXTRA_ALARM_ID, alarmId))
    }

    // ------------------------------------------------------------------ 뒤로 가기 막기

    private fun blockBackButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback { /* 알람 화면에서는 뒤로 가기 무시 */ }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Android 13 미만용. 13 이상은 OnBackInvokedCallback 사용")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 알람 화면에서는 뒤로 가기 무시
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_START_SERVICE = "start_service"
        const val EXTRA_START_MISSION = "start_mission"
        private const val GRACE_MS = 5_000L
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
    }
}

package com.fitalarm.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.Days
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.RecordStats
import com.fitalarm.app.core.RoutineCodec
import com.fitalarm.app.core.RoutineStep
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.data.HolidayRepository
import com.fitalarm.app.data.RecordRepository
import com.fitalarm.app.systemService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 알람 탭: 다음 알람, 권한 안내, 알람 목록 */
class AlarmsPage(host: MainActivity) : Page(host) {
    private val ctx = host
    private lateinit var streakChip: TextView
    private lateinit var heroDay: TextView
    private lateinit var heroTime: TextView
    private lateinit var heroSub: TextView
    private lateinit var bannerContainer: LinearLayout
    private lateinit var listContainer: LinearLayout

    override val root: View = build()

    private fun build(): View = with(ctx) {
        val frame = FrameLayout(this)
        val content = vbox { setPadding(dp(20), dp(20), dp(20), dp(110)) }

        val header = hbox {
            addView(label("알람", 30f, Palette.TEXT, Fonts.BOLD))
            addView(spacer())
            streakChip = label("", 14f, Palette.ON_WARM, Fonts.BOLD).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(Palette.WARM_SOFT, dpf(999))
                compoundDrawablePadding = dp(6)
                getDrawable(R.drawable.ic_flame)?.mutate()?.let { d ->
                    d.setTint(Palette.WARM)
                    d.setBounds(0, 0, dp(16), dp(16))
                    setCompoundDrawables(d, null, null, null)
                }
            }
            addView(streakChip)
        }
        content.addView(header, lp())

        val hero = vbox {
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = rounded(Palette.HERO, dpf(26))
            addView(label("다음 알람", 14f, Palette.ON_HERO_DIM, Fonts.MEDIUM))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                heroDay = label("", 17f, Palette.ON_HERO, Fonts.MEDIUM)
                heroTime = label("", 44f, Palette.ON_HERO, Fonts.LIGHT)
                addView(heroDay, lp(WRAP, WRAP).margins(r = dp(8), b = dp(6)))
                addView(heroTime, lp(WRAP, WRAP))
            }, lp().margins(t = dp(6)))
            heroSub = label("", 14f, Palette.ON_HERO_DIM).apply { setLineSpacing(0f, 1.2f) }
            addView(heroSub, lp().margins(t = dp(6)))
        }
        content.addView(hero, lp().margins(t = dp(16), b = dp(16)))

        bannerContainer = vbox()
        content.addView(bannerContainer, lp())
        listContainer = vbox()
        content.addView(listContainer, lp())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        frame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val fab = FrameLayout(this).apply {
            background = ripple(oval(Palette.ACCENT), oval(android.graphics.Color.WHITE))
            elevation = dpf(6)
            contentDescription = "알람 추가"
            isClickable = true
            setOnClickListener { openEditor(0) }
            addView(icon(R.drawable.ic_plus, Palette.ON_ACCENT, 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(22), dp(22))
        })
        frame
    }

    override fun refresh() {
        val alarms = AlarmRepository.getAll(ctx)
        val streak = RecordStats.currentStreak(RecordRepository.all(ctx), LocalDate.now())
        streakChip.text = "${streak}일 연속"
        streakChip.visibility = if (streak > 0) View.VISIBLE else View.GONE
        updateNextAlarm()
        renderBanners(alarms)
        listContainer.removeAllViews()
        if (alarms.isEmpty()) {
            listContainer.addView(emptyState(), lp().margins(t = dp(32)))
        } else {
            alarms.forEach { listContainer.addView(alarmCard(it), lp().margins(b = dp(12))) }
        }
    }

    private fun dp(v: Number) = ctx.dp(v)

    fun updateNextAlarm() {
        val now = ZonedDateTime.now()
        val scheduler = AlarmScheduler(ctx)
        val next = AlarmRepository.getAll(ctx)
            .mapNotNull { a -> scheduler.nextTrigger(a)?.let { a to it } }
            .minByOrNull { it.second }
        if (next == null) {
            heroDay.text = ""
            heroTime.text = "없음"
            heroSub.text = "켜진 알람이 없어요. + 버튼으로 알람을 추가해 보세요"
            return
        }
        val (alarm, at) = next
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val (ampm, hm) = AlarmMath.formatTimeParts(t.hour, t.minute, DateFormat.is24HourFormat(ctx))
        heroDay.text = AlarmMath.relativeDay(t.toLocalDate(), now.toLocalDate()) + (ampm?.let { " $it" } ?: "")
        heroTime.text = hm
        val remaining = AlarmMath.describeRemaining(System.currentTimeMillis(), at) + " 후"
        val what = when {
            alarm.hasMission -> alarm.routine.joinToString(" → ") { "${shortTitle(it.type)} ${it.reps}회" }
            alarm.label.isNotBlank() -> alarm.label
            else -> null
        }
        heroSub.text = if (what == null) remaining else "$remaining · $what"
    }

    private fun emptyState(): View = ctx.vbox {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ctx.icon(R.drawable.ic_sun, Palette.ACCENT, 48))
        addView(ctx.label("아직 알람이 없어요", 18f, Palette.TEXT, Fonts.MEDIUM), lp(WRAP, WRAP).margins(t = dp(12)))
        addView(ctx.label("오른쪽 아래 + 버튼으로 알람을 추가하고\n운동 미션을 설정해 보세요", 14f, Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }, lp(WRAP, WRAP).margins(t = dp(8)))
    }

    private fun alarmCard(a: Alarm): View = with(ctx) {
        val use24 = DateFormat.is24HourFormat(this)
        val on = a.enabled
        val main = if (on) Palette.TEXT else Palette.TEXT_DIM

        val card = hbox {
            gravity = Gravity.TOP
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = ripple(rounded(Palette.SURFACE, dpf(24), Palette.OUTLINE, dp(1)))
            isClickable = true
            setOnClickListener { openEditor(a.id) }
            setOnLongClickListener {
                confirmDelete(a)
                true
            }
        }
        val left = vbox()
        val (ampm, hm) = AlarmMath.formatTimeParts(a.hour, a.minute, use24)
        left.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            if (ampm != null) addView(label(ampm, 15f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(r = dp(6), b = dp(5)))
            addView(label(hm, 40f, main, Fonts.LIGHT))
            if (a.label.isNotBlank()) {
                addView(label(a.label, 15f, Palette.TEXT_DIM).apply { maxLines = 1 }, lp(WRAP, WRAP).margins(l = dp(10), b = dp(5)))
            }
        })

        if (a.isRepeating) {
            val daysRow = hbox()
            Days.SHORT_LABELS.forEachIndexed { i, s ->
                val set = Days.isSet(a.days, i)
                val color = when {
                    !set -> Palette.TEXT_FAINT
                    on -> Palette.ACCENT
                    else -> Palette.TEXT_DIM
                }
                daysRow.addView(label(s, 13f, color, if (set) Fonts.BOLD else null), lp(WRAP, WRAP).margins(r = dp(8)))
            }
            left.addView(daysRow, lp().margins(t = dp(8)))
        } else {
            left.addView(label(oneShotDayText(a), 13f, Palette.TEXT_DIM), lp().margins(t = dp(8)))
        }

        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (a.hasMission) {
            chips.addView(
                chip("운동 미션 · " + a.routine.joinToString(" → ") { "${shortTitle(it.type)} ${it.reps}" },
                    if (on) Palette.ACCENT_SOFT else Palette.SURFACE_HI, if (on) Palette.ON_ACCENT_SOFT else Palette.TEXT_DIM),
                lp(WRAP, WRAP).margins(t = dp(8)),
            )
        }
        if (on && a.isRepeating && (a.skipHolidays || a.skipDaysOff)) {
            val next = AlarmMath.nextSkippedDay(a, ZonedDateTime.now(), HolidayRepository.skipRuleFor(this, a), 90)
            val text = if (next != null) "쉬는 날 건너뜀 · 다음 ${AlarmMath.shortDate(next.first)} ${next.second}"
            else if (a.skipHolidays) "공휴일 쉼" else "휴무일 쉼"
            chips.addView(chip(text, Palette.SKY_SOFT, Palette.ON_SKY).apply { maxLines = 1 }, lp(WRAP, WRAP).margins(t = dp(6)))
        }
        if (on && a.snoozedUntil > System.currentTimeMillis()) {
            val t = Instant.ofEpochMilli(a.snoozedUntil).atZone(ZoneId.systemDefault())
            chips.addView(
                chip("다시 알림 " + AlarmMath.formatTime(t.hour, t.minute, use24) + "에 울림", Palette.WARM_SOFT, Palette.ON_WARM),
                lp(WRAP, WRAP).margins(t = dp(6)),
            )
        }
        left.addView(chips)
        card.addView(left, lp(0, WRAP, 1f))

        val toggle = Switch(this).apply {
            isChecked = on
            contentDescription = "알람 켜기"
            setOnCheckedChangeListener { _, checked -> card.post { setAlarmEnabled(a, checked) } }
        }
        card.addView(toggle, lp(WRAP, WRAP).margins(t = dp(4)))
        card
    }

    private fun shortTitle(t: ExerciseType) = t.title.substringBefore(" (")

    private fun oneShotDayText(a: Alarm): String {
        if (!a.enabled) return "반복 안 함"
        val next = AlarmMath.nextOccurrence(a.hour, a.minute, Days.NONE, ZonedDateTime.now())
        return "반복 안 함 · " + AlarmMath.relativeDay(next.toLocalDate(), LocalDate.now())
    }

    private fun setAlarmEnabled(a: Alarm, enabled: Boolean) {
        val saved = AlarmRepository.upsert(ctx, a.copy(enabled = enabled, snoozedUntil = 0L, snoozeCount = 0))
        val at = AlarmScheduler(ctx).schedule(saved)
        if (enabled && at != null) {
            ctx.toast("알람이 " + AlarmMath.describeRemaining(System.currentTimeMillis(), at) + " 후에 울립니다")
        }
        refresh()
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(ctx)
            .setTitle("알람 삭제")
            .setMessage(AlarmMath.formatTime(a.hour, a.minute, DateFormat.is24HourFormat(ctx)) + " 알람을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                AlarmScheduler(ctx).cancel(a.id)
                AlarmRepository.delete(ctx, a.id)
                refresh()
            }
            .show()
    }

    private fun openEditor(alarmId: Int) {
        ctx.startActivity(Intent(ctx, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_ALARM_ID, alarmId))
    }

    /** 운동 연습 (설정 탭에서도 호출) */
    fun showPracticePicker() {
        val types = ExerciseType.entries
        AlertDialog.Builder(ctx)
            .setTitle("어떤 운동을 연습할까요? (5회)")
            .setItems(types.map { it.title }.toTypedArray()) { _, which ->
                val routine = listOf(RoutineStep(types[which], PRACTICE_REPS))
                ctx.startActivity(
                    Intent(ctx, MissionActivity::class.java)
                        .putExtra(MissionActivity.EXTRA_PRACTICE_ROUTINE, RoutineCodec.encode(routine))
                )
            }
            .show()
    }

    // ------------------------------------------------------------------ 권한 안내

    private data class Banner(val text: String, val action: () -> Unit)

    private fun renderBanners(alarms: List<Alarm>) {
        val banners = ArrayList<Banner>()
        val nm = ctx.systemService<NotificationManager>()
        val am = ctx.systemService<AlarmManager>()
        val pkg = ctx.packageName

        if (!nm.areNotificationsEnabled()) {
            banners.add(Banner("알림이 꺼져 있어 알람 화면이 뜨지 않을 수 있어요. 탭해서 알림을 허용하세요.") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ctx.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    ctx.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQ_NOTIFICATIONS)
                } else {
                    openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
                }
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            banners.add(Banner("정확한 시각에 울리려면 '알람 및 리마인더' 권한이 필요해요. 탭해서 허용하세요.") {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
            })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()) {
            banners.add(Banner("잠금 화면에 알람 화면을 띄우려면 '전체 화면 알림' 권한이 필요해요. 탭해서 허용하세요.") {
                openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            })
        }
        if (alarms.any { it.enabled && it.hasMission } &&
            ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            banners.add(Banner("운동 미션에는 카메라 권한이 필요해요. 탭해서 허용하세요.") {
                val prefs = ctx.getSharedPreferences(MainActivity.UI_PREFS, android.content.Context.MODE_PRIVATE)
                if (ctx.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                    !prefs.getBoolean(MainActivity.KEY_CAMERA_ASKED, false)
                ) {
                    prefs.edit().putBoolean(MainActivity.KEY_CAMERA_ASKED, true).apply()
                    ctx.requestPermissions(arrayOf(Manifest.permission.CAMERA), MainActivity.REQ_CAMERA)
                } else {
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                }
            })
        }

        bannerContainer.removeAllViews()
        for (b in banners) {
            val v = ctx.hbox {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = ripple(rounded(Palette.WARM_SOFT, ctx.dpf(16)))
                isClickable = true
                setOnClickListener { b.action() }
                addView(ctx.label("!", 18f, Palette.WARM, Fonts.BOLD), lp(WRAP, WRAP).margins(r = dp(12)))
                addView(ctx.label(b.text, 14f, Palette.ON_WARM).apply { setLineSpacing(0f, 1.2f) }, lp(0, WRAP, 1f))
            }
            bannerContainer.addView(v, lp().margins(b = dp(12)))
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    companion object {
        private const val PRACTICE_REPS = 5
    }
}

package com.fitalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.RecordEntry
import com.fitalarm.app.core.RecordKind
import com.fitalarm.app.core.UpcomingAlarm
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.data.RecordRepository
import com.fitalarm.app.data.SettingsRepository
import com.fitalarm.app.systemService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * '곧 울릴 알람' 알림의 예약·표시·취소.
 *  - SHOW: 울리기 N분 전 (설정 > 알람)
 *  - CANCEL: 알림의 '알람 취소'. 반복 알람은 이번 회차만 건너뛰고, 한 번 알람은 끔.
 *    운동 미션 알람이면 '운동 없이 끄기'처럼 건너뜀으로 기록
 *  - DISMISSED: 사용자가 밀어서 지움 → 같은 회차는 다시 띄우지 않음
 */
class UpcomingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, 0L)
        if (alarmId < 0 || triggerAt <= 0L) return
        when (intent.action) {
            ACTION_SHOW -> show(context, alarmId, triggerAt)
            ACTION_CANCEL -> cancelAlarm(context, alarmId, triggerAt)
            ACTION_DISMISSED -> markDismissed(context, alarmId, triggerAt)
        }
    }

    private fun show(ctx: Context, alarmId: Int, triggerAt: Long) {
        val a = AlarmRepository.get(ctx, alarmId) ?: return
        // 그 사이 시각이 바뀌었거나 꺼졌으면 띄우지 않음
        if (AlarmScheduler(ctx).nextTrigger(a) != triggerAt || a.snoozedUntil > 0L) return
        if (triggerAt <= System.currentTimeMillis()) return
        Notifications.showUpcoming(ctx, a, triggerAt)
    }

    private fun cancelAlarm(ctx: Context, alarmId: Int, triggerAt: Long) {
        Notifications.cancelUpcoming(ctx, alarmId)
        val a = AlarmRepository.get(ctx, alarmId) ?: return
        val scheduler = AlarmScheduler(ctx)
        if (scheduler.nextTrigger(a) != triggerAt) return   // 이미 울렸거나 바뀐 알람
        val updated = AlarmRepository.upsert(ctx, UpcomingAlarm.cancel(a, triggerAt))
        scheduler.schedule(updated)
        val t = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault())
        if (a.hasMission) {
            RecordRepository.append(ctx, RecordEntry(t.toLocalDate(), RecordKind.SKIP, alarmId = a.id, atMillis = System.currentTimeMillis()))
        }
        val use24 = DateFormat.is24HourFormat(ctx)
        val next = scheduler.nextTrigger(updated)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        val msg = StringBuilder("${AlarmMath.formatTime(t.hour, t.minute, use24)} 알람을 취소했어요")
        if (next != null) msg.append(" · 다음 알람 ${AlarmMath.relativeDay(next.toLocalDate(), LocalDate.now())} ${AlarmMath.formatTime(next.hour, next.minute, use24)}")
        Toast.makeText(ctx.applicationContext, msg.toString(), Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_SHOW = "com.fitalarm.app.action.UPCOMING_SHOW"
        const val ACTION_CANCEL = "com.fitalarm.app.action.UPCOMING_CANCEL"
        const val ACTION_DISMISSED = "com.fitalarm.app.action.UPCOMING_DISMISSED"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_TRIGGER_AT = "trigger_at"
        private const val PREFS = "fit_alarm_upcoming"

        private fun requestCode(action: String, alarmId: Int) = when (action) {
            ACTION_SHOW -> 50_000
            ACTION_CANCEL -> 60_000
            else -> 70_000
        } + alarmId

        fun pendingIntent(ctx: Context, action: String, alarmId: Int, triggerAt: Long): PendingIntent {
            val i = Intent(ctx, UpcomingReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("fitalarm://upcoming/$alarmId"))
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt)
            return PendingIntent.getBroadcast(ctx, requestCode(action, alarmId), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * 알람을 예약할 때 함께 호출: 알림 시점이 이미 지났으면 바로 띄우고, 아니면 그때 띄우도록 예약.
         * 다시 알림 대기 중(snooze)이면 따로 '다시 알림' 알림이 있으므로 띄우지 않음.
         */
        fun schedule(ctx: Context, alarmId: Int, triggerAt: Long?, isSnooze: Boolean) {
            val am = ctx.systemService<AlarmManager>()
            val pi = pendingIntent(ctx, ACTION_SHOW, alarmId, triggerAt ?: 0L)
            am.cancel(pi)
            val minutes = SettingsRepository.get(ctx).upcomingMinutes
            val now = System.currentTimeMillis()
            if (triggerAt == null || isSnooze || minutes <= 0 || triggerAt <= now) {
                Notifications.cancelUpcoming(ctx, alarmId)
                return
            }
            if (UpcomingAlarm.shouldShowNow(triggerAt, minutes, now)) {
                AlarmRepository.get(ctx, alarmId)?.let { Notifications.showUpcoming(ctx, it, triggerAt) }
                return
            }
            Notifications.cancelUpcoming(ctx, alarmId)   // 지난 회차 알림이 남아 있으면 정리
            val at = UpcomingAlarm.showAt(triggerAt, minutes) ?: return
            val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        fun cancel(ctx: Context, alarmId: Int) {
            ctx.systemService<AlarmManager>().cancel(pendingIntent(ctx, ACTION_SHOW, alarmId, 0L))
            Notifications.cancelUpcoming(ctx, alarmId)
        }

        private fun markDismissed(ctx: Context, alarmId: Int, triggerAt: Long) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("dismissed_$alarmId", triggerAt).apply()
        }

        fun wasDismissed(ctx: Context, alarmId: Int, triggerAt: Long): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dismissed_$alarmId", 0L) == triggerAt
    }
}

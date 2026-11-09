package com.fitalarm.app.nunbody

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fitalarm.app.R
import com.fitalarm.app.core.NunbodyReminderMath
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.systemService
import com.fitalarm.app.ui.MainActivity
import com.fitalarm.app.ui.NunbodyActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 눈바디 촬영 알림 (기본 꺼짐). 운동 알람과 상관없는 일반 알림이라 정확한 시각이 아니어도 되므로
 * 배터리 부담이 적은 일반 예약을 씀. 알림 문구에는 몸 정보가 없음.
 */
object NunbodyReminder {
    private const val CHANNEL = "nunbody_reminder_v1"
    private const val NOTIF_ID = 4001
    private const val RC_FIRE = 4001
    private const val RC_OPEN = 4002
    const val ACTION = "com.fitalarm.app.action.NUNBODY_REMINDER"

    fun nextAt(ctx: Context, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val s = NunbodyRepository.settings(ctx)
        if (!s.enabled || !s.reminderOn) return null
        return NunbodyReminderMath.next(now, s.reminderPeriod, LocalTime.of(s.reminderHour, s.reminderMinute), s.reminderAnchor ?: LocalDate.now())
    }

    private fun firePi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RC_FIRE,
        Intent(ctx, NunbodyReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 설정이 바뀌거나 재부팅·시간 변경 때 다시 예약 */
    fun schedule(ctx: Context) {
        val am = ctx.systemService<AlarmManager>()
        val pi = firePi(ctx)
        am.cancel(pi)
        val next = nextAt(ctx) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun show(ctx: Context) {
        val nm = ctx.systemService<NotificationManager>()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "눈바디 촬영 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "정한 날에 눈바디 찍을 때라고 알려 드려요"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivities(
            ctx, RC_OPEN,
            arrayOf(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                Intent(ctx, NunbodyActivity::class.java),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("오늘은 눈바디 찍는 날이에요")
            .setContentText("원할 때 기록 탭 › 눈바디에서 찍어 보세요")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            // 알림 권한이 없으면 조용히 건너뜀
        }
    }
}

class NunbodyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NunbodyReminder.ACTION) return
        val s = NunbodyRepository.settings(context)
        if (s.enabled && s.reminderOn) NunbodyReminder.show(context)
        NunbodyReminder.schedule(context)
    }
}

package com.fitalarm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateFormat
import com.fitalarm.app.R
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.describe
import com.fitalarm.app.systemService
import com.fitalarm.app.ui.AlarmRingActivity
import com.fitalarm.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

object Notifications {
    private const val CH_RING = "alarm_ring_v1"
    private const val CH_INFO = "alarm_info_v1"

    const val ID_RINGING = 1001
    private fun snoozeId(alarmId: Int) = 20_000 + alarmId
    private fun missedId(alarmId: Int) = 30_000 + alarmId

    // PendingIntent requestCode 구분용
    private const val RC_SNOOZE = 1
    private const val RC_DISMISS = 2
    private const val RC_MISSION = 3

    fun ensureChannels(ctx: Context) {
        val nm = ctx.systemService<NotificationManager>()
        val ring = NotificationChannel(CH_RING, "울리는 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "알람이 울릴 때 표시되는 알림 (소리·진동은 앱이 직접 재생)"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(CH_INFO, "알람 안내", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "다시 알림 예정, 놓친 알람 안내"
            setSound(null, null)
            setShowBadge(false)
        }
        nm.createNotificationChannels(listOf(ring, info))
    }

    private fun use24h(ctx: Context) = DateFormat.is24HourFormat(ctx)

    private fun ringActivityIntent(ctx: Context, alarmId: Int, extra: Intent.() -> Unit = {}): Intent =
        Intent(ctx, AlarmRingActivity::class.java)
            .putExtra(AlarmRingActivity.EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .apply(extra)

    private fun activityPi(ctx: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun servicePi(ctx: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getService(ctx, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_alarm), title, pi).build()

    private fun title(ctx: Context, a: Alarm): String {
        val time = AlarmMath.formatTime(a.hour, a.minute, use24h(ctx))
        return if (a.label.isBlank()) "알람 · $time" else "${a.label} · $time"
    }

    /** 알람 재생 중 포그라운드 서비스 알림 (잠금 화면에서 전체화면으로 알람 화면을 띄움) */
    fun buildRinging(ctx: Context, a: Alarm): Notification {
        val fullScreen = activityPi(ctx, a.id * 10, ringActivityIntent(ctx, a.id))
        val text = if (a.hasMission) "운동 미션: ${a.routine.describe()}" else "알람을 끄려면 탭하세요"
        val b = Notification.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title(ctx, a))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (a.canSnooze()) {
            b.addAction(action(ctx, "다시 알림 (${a.snoozeMinutes}분)",
                servicePi(ctx, a.id * 10 + RC_SNOOZE, AlarmService.actionIntent(ctx, AlarmService.ACTION_SNOOZE, a.id))))
        }
        if (a.hasMission) {
            // 미션 알람은 알림에서 바로 끌 수 없고, 미션 화면으로만 이동
            b.addAction(action(ctx, "운동 시작",
                activityPi(ctx, a.id * 10 + RC_MISSION, ringActivityIntent(ctx, a.id) {
                    putExtra(AlarmRingActivity.EXTRA_START_MISSION, true)
                })))
        } else {
            b.addAction(action(ctx, "끄기",
                servicePi(ctx, a.id * 10 + RC_DISMISS, AlarmService.actionIntent(ctx, AlarmService.ACTION_DISMISS, a.id))))
        }
        return b.build()
    }

    /** 포그라운드 서비스를 시작할 수 없을 때: 알람 화면을 먼저 띄우고 화면이 서비스를 시작 */
    fun showFallbackRinging(ctx: Context, alarmId: Int) {
        val pi = activityPi(ctx, alarmId * 10 + 9, ringActivityIntent(ctx, alarmId) {
            putExtra(AlarmRingActivity.EXTRA_START_SERVICE, true)
        })
        val n = Notification.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("알람")
            .setContentText("알람 화면을 열려면 탭하세요")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        notify(ctx, ID_RINGING, n)
    }

    fun showSnoozed(ctx: Context, a: Alarm, untilMs: Long) {
        val t = Instant.ofEpochMilli(untilMs).atZone(ZoneId.systemDefault())
        val whenText = AlarmMath.formatTime(t.hour, t.minute, use24h(ctx))
        val left = a.snoozesLeft()
        val leftText = when (left) {
            null -> ""
            0 -> " · 마지막 다시 알림"
            else -> " · ${left}회 남음"
        }
        val b = Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("다시 알림 · $whenText")
            .setContentText((a.label.ifBlank { "알람" }) + leftText)
            .setOngoing(false)
            .setShowWhen(false)
            .setContentIntent(activityPi(ctx, a.id * 10 + 5,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        if (!a.hasMission) {
            b.addAction(action(ctx, "지금 끄기",
                servicePi(ctx, a.id * 10 + 6, AlarmService.actionIntent(ctx, AlarmService.ACTION_DISMISS, a.id))))
        }
        notify(ctx, snoozeId(a.id), b.build())
    }

    fun cancelSnoozed(ctx: Context, alarmId: Int) {
        ctx.systemService<NotificationManager>().cancel(snoozeId(alarmId))
    }

    fun showMissed(ctx: Context, a: Alarm) {
        val n = Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("놓친 알람 · " + AlarmMath.formatTime(a.hour, a.minute, use24h(ctx)))
            .setContentText(a.label.ifBlank { "알람이 응답 없이 종료되었습니다" })
            .setAutoCancel(true)
            .setContentIntent(activityPi(ctx, a.id * 10 + 7,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            .build()
        notify(ctx, missedId(a.id), n)
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.systemService<NotificationManager>().notify(id, n)
        } catch (e: SecurityException) {
            // 알림 권한이 없는 경우: 표시만 못 할 뿐 알람 자체(소리·진동)는 계속 동작
        }
    }
}

package com.fitalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.systemService
import com.fitalarm.app.ui.MainActivity
import java.time.ZonedDateTime

/**
 * AlarmManager 등록/해제.
 * 알람 1개당 슬롯 1개(requestCode = 알람 id)만 사용하고,
 * 다시 알림 대기 중이면 그 시각을, 아니면 다음 정규 시각을 그 슬롯에 넣습니다.
 */
class AlarmScheduler(context: Context) {
    private val ctx = context.applicationContext
    private val am: AlarmManager = ctx.systemService()

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** @return 등록된 시각(epoch ms), 꺼져 있으면 null */
    fun schedule(alarm: Alarm): Long? {
        cancel(alarm.id)
        val triggerAt = AlarmMath.nextTriggerMillis(alarm, ZonedDateTime.now()) ?: return null
        val operation = firePendingIntent(alarm.id)
        if (canScheduleExact()) {
            // setAlarmClock: Doze 중에도 정확히 울리고, 상태바에 알람 아이콘이 표시됨
            val show = PendingIntent.getActivity(
                ctx, alarm.id,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
                return triggerAt
            } catch (e: SecurityException) {
                // 확인 직후 권한이 회수된 경우 → 아래 대비책으로
            }
        }
        // 권한이 없을 때의 대비책 (몇 분 늦을 수 있음). 메인 화면에서 권한 안내를 띄움
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        return triggerAt
    }

    fun cancel(alarmId: Int) {
        am.cancel(firePendingIntent(alarmId))
    }

    /** 재부팅/시간 변경/권한 변경 후 전체 재등록. 이미 지나간 다시 알림은 정리합니다. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        for (a in AlarmRepository.getAll(ctx)) {
            var alarm = a
            if (alarm.snoozedUntil in 1..now) {
                alarm = alarm.copy(
                    snoozedUntil = 0L,
                    snoozeCount = 0,
                    enabled = alarm.enabled && alarm.isRepeating,
                )
                AlarmRepository.upsert(ctx, alarm)
            }
            schedule(alarm)
        }
    }

    private fun firePendingIntent(alarmId: Int): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("fitalarm://alarm/$alarmId"))
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            ctx, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

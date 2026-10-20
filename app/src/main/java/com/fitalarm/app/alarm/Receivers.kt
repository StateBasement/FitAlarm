package com.fitalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fitalarm.app.holiday.HolidaySync

/** AlarmManager 가 알람 시각에 호출 → 알람 재생 서비스 시작 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        if (id <= 0) return
        try {
            // setAlarmClock 으로 울린 알람은 백그라운드에서 포그라운드 서비스 시작이 허용됨
            context.startForegroundService(AlarmService.startIntent(context, id))
        } catch (e: IllegalStateException) {
            // 정확한 알람 권한이 없어 일반 알람으로 등록된 경우 등: 전체화면 알림으로 화면을 먼저 띄우고
            // 화면(포그라운드)에서 서비스를 시작
            Notifications.ensureChannels(context)
            Notifications.showFallbackRinging(context, id)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.fitalarm.app.action.FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}

/** 재부팅, 시간·시간대 변경, 앱 업데이트, 정확한 알람 권한 허용 시 모든 알람 재등록 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> {
                AlarmScheduler(context).rescheduleAll()
                HolidaySync.schedulePeriodic(context)
            }
        }
    }

    private companion object {
        // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31) 와 동일한 문자열
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

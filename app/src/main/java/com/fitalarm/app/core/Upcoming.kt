package com.fitalarm.app.core

/**
 * '곧 울릴 알람' 알림: 울리기 전에 상태창에 미리 보여 주고, 내려서 '알람 보기'·'알람 취소'.
 * 순수 로직만 (알림·예약은 alarm/UpcomingReceiver, Notifications).
 */
object UpcomingAlarm {
    /** 설정에서 고를 수 있는 시점 (분). 0 = 끄기 */
    val CHOICES = listOf(0, 15, 30, 60, 120)
    const val DEFAULT_MINUTES = 30

    fun label(minutes: Int): String = when {
        minutes <= 0 -> "끄기"
        minutes % 60 == 0 -> "${minutes / 60}시간 전"
        else -> "${minutes}분 전"
    }

    /** 알림을 띄울 시각. 끄기면 null */
    fun showAt(triggerAt: Long, minutes: Int): Long? = if (minutes <= 0) null else triggerAt - minutes * 60_000L

    /** 지금 바로 보여야 하는지 (알림 시점은 지났고 아직 울리기 전) */
    fun shouldShowNow(triggerAt: Long, minutes: Int, nowMs: Long): Boolean {
        val at = showAt(triggerAt, minutes) ?: return false
        return nowMs in at until triggerAt
    }

    /**
     * '알람 취소': 반복 알람은 이번 회차만 건너뛰고(다음 회차는 그대로), 한 번만 울리는 알람은 꺼짐.
     * 다시 알림 대기 중이던 상태는 정리.
     */
    fun cancel(alarm: Alarm, triggerAt: Long): Alarm =
        if (alarm.isRepeating) alarm.copy(skipUntil = triggerAt, snoozedUntil = 0L, snoozeCount = 0)
        else alarm.copy(enabled = false, skipUntil = 0L, snoozedUntil = 0L, snoozeCount = 0)
}

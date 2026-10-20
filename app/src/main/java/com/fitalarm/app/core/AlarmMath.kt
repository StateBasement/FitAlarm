package com.fitalarm.app.core

import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.ceil

/** 다음 알람 시각 계산과 시간 표시용 문자열. 모두 순수 함수입니다. */
object AlarmMath {

    /**
     * now 이후(같은 시각 제외) 처음으로 오는 hour:minute.
     * days == Days.NONE 이면 요일 제한 없이 가장 가까운 시각(오늘 또는 내일).
     * 날짜마다 LocalDate 에서 다시 만들기 때문에 서머타임 경계에서도 시각이 밀리지 않습니다.
     */
    fun nextOccurrence(hour: Int, minute: Int, days: Int, now: ZonedDateTime): ZonedDateTime {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
        val time = LocalTime.of(hour, minute)
        val today = now.toLocalDate()
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (days != Days.NONE && !Days.contains(days, date.dayOfWeek)) continue
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        // offset 7 은 오늘과 같은 요일이므로 유효한 마스크라면 여기까지 오지 않습니다.
        throw IllegalStateException("no occurrence found for mask=$days")
    }

    /** 실제로 다음에 울릴 시각(epoch ms). 꺼져 있으면 null. 다시 알림 대기 중이면 그 시각. */
    fun nextTriggerMillis(alarm: Alarm, now: ZonedDateTime): Long? {
        if (!alarm.enabled) return null
        val nowMs = now.toInstant().toEpochMilli()
        if (alarm.snoozedUntil > nowMs) return alarm.snoozedUntil
        return nextOccurrence(alarm.hour, alarm.minute, alarm.days, now).toInstant().toEpochMilli()
    }

    /** "7시간 12분", "1일 3시간", "1분 이내" 등. 분 단위는 올림 처리합니다. */
    fun describeRemaining(fromMs: Long, toMs: Long): String {
        val diffMs = toMs - fromMs
        if (diffMs < 60_000L) return "1분 이내"
        val totalMin = ceil(diffMs / 60_000.0).toLong()
        val d = totalMin / (24 * 60)
        val h = (totalMin % (24 * 60)) / 60
        val m = totalMin % 60
        val parts = ArrayList<String>(3)
        if (d > 0) parts.add("${d}일")
        if (h > 0) parts.add("${h}시간")
        if (m > 0) parts.add("${m}분")
        return parts.joinToString(" ")
    }

    /** (오전/오후, "6:30") 쌍. 24시간 표기면 첫 값이 null 이고 "06:30". */
    fun formatTimeParts(hour: Int, minute: Int, use24h: Boolean): Pair<String?, String> {
        return if (use24h) {
            null to String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        } else {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            (if (hour < 12) "오전" else "오후") to String.format(Locale.ROOT, "%d:%02d", h12, minute)
        }
    }

    fun formatTime(hour: Int, minute: Int, use24h: Boolean): String {
        val (ampm, hm) = formatTimeParts(hour, minute, use24h)
        return if (ampm == null) hm else "$ampm $hm"
    }
}

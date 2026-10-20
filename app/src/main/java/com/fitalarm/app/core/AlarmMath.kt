package com.fitalarm.app.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.ceil

/** 그 날짜를 건너뛸지 (공휴일·휴무일). null 이면 건너뛰는 날 없음 */
fun interface SkipRule {
    /** 건너뛸 이유(예: "성탄절"), 건너뛰지 않으면 null */
    fun reason(date: LocalDate): String?
}

/** 다음 알람 시각 계산과 시간 표시용 문자열. 모두 순수 함수입니다. */
object AlarmMath {
    /** 건너뛰는 날이 계속 이어져도 이 기간 안에서는 반드시 답을 찾음 (데이터 오류 대비) */
    private const val MAX_SEARCH_DAYS = 400L

    /**
     * now 이후(같은 시각 제외) 처음으로 오는 hour:minute.
     * days == Days.NONE 이면 요일 제한 없이 가장 가까운 시각(오늘 또는 내일).
     * skip 이 주어지면 그 날짜는 건너뜀. 날짜마다 LocalDate 에서 다시 만들기 때문에
     * 서머타임 경계에서도 시각이 밀리지 않습니다.
     */
    fun nextOccurrence(
        hour: Int,
        minute: Int,
        days: Int,
        now: ZonedDateTime,
        skip: SkipRule? = null,
    ): ZonedDateTime {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
        val time = LocalTime.of(hour, minute)
        val today = now.toLocalDate()
        var firstUnskipped: ZonedDateTime? = null
        for (offset in 0L..MAX_SEARCH_DAYS) {
            val date = today.plusDays(offset)
            if (days != Days.NONE && !Days.contains(days, date.dayOfWeek)) continue
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (!candidate.isAfter(now)) continue
            if (firstUnskipped == null) firstUnskipped = candidate
            if (skip == null || skip.reason(date) == null) return candidate
        }
        // 모든 날이 건너뛰어지는 비정상 데이터: 알람을 놓치지 않도록 건너뛰기 무시
        return firstUnskipped ?: throw IllegalStateException("no occurrence found for mask=$days")
    }

    /** 반복 알람에만 쉬는 날 규칙을 적용 (1회성 알람은 사용자가 정한 그 날 울림) */
    fun effectiveSkip(alarm: Alarm, skip: SkipRule?): SkipRule? =
        if (alarm.isRepeating) skip else null

    /** 실제로 다음에 울릴 시각(epoch ms). 꺼져 있으면 null. 다시 알림 대기 중이면 그 시각. */
    fun nextTriggerMillis(alarm: Alarm, now: ZonedDateTime, skip: SkipRule? = null): Long? {
        if (!alarm.enabled) return null
        val nowMs = now.toInstant().toEpochMilli()
        if (alarm.snoozedUntil > nowMs) return alarm.snoozedUntil
        return nextOccurrence(alarm.hour, alarm.minute, alarm.days, now, effectiveSkip(alarm, skip))
            .toInstant().toEpochMilli()
    }

    /** 앞으로 건너뛸 가장 가까운 날 (알람 요일 중에서). 목록 화면의 "공휴일 쉼 · 다음 12/25" 표시용 */
    fun nextSkippedDay(alarm: Alarm, now: ZonedDateTime, skip: SkipRule?, withinDays: Long = 120): Pair<LocalDate, String>? {
        if (!alarm.isRepeating || skip == null) return null
        val today = now.toLocalDate()
        for (offset in 0L..withinDays) {
            val date = today.plusDays(offset)
            if (!Days.contains(alarm.days, date.dayOfWeek)) continue
            if (offset == 0L && !ZonedDateTime.of(date, LocalTime.of(alarm.hour, alarm.minute), now.zone).isAfter(now)) continue
            val reason = skip.reason(date) ?: continue
            return date to reason
        }
        return null
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

    private val DOW = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** "오늘", "내일", "10월 23일(금)" */
    fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "오늘"
        today.plusDays(1) -> "내일"
        else -> "${date.monthValue}월 ${date.dayOfMonth}일(${DOW[date.dayOfWeek.value - 1]})"
    }

    /** "12/25" */
    fun shortDate(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"
}

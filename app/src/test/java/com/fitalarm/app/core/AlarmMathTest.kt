package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmMathTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = seoul) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)

    @Test
    fun calendarSanity() {
        // 테스트 날짜 기준: 2026-10-20 은 화요일
        assertEquals(DayOfWeek.TUESDAY, at(2026, 10, 20, 0, 0).dayOfWeek)
    }

    @Test
    fun oneShotLaterToday() {
        val now = at(2026, 10, 20, 6, 0)
        assertEquals(at(2026, 10, 20, 7, 0), AlarmMath.nextOccurrence(7, 0, Days.NONE, now))
    }

    @Test
    fun oneShotAlreadyPassedGoesToTomorrow() {
        val now = at(2026, 10, 20, 22, 32)
        assertEquals(at(2026, 10, 21, 7, 0), AlarmMath.nextOccurrence(7, 0, Days.NONE, now))
    }

    @Test
    fun exactlyNowIsNotTreatedAsNext() {
        val now = at(2026, 10, 20, 7, 0, 0)
        assertEquals(at(2026, 10, 21, 7, 0), AlarmMath.nextOccurrence(7, 0, Days.NONE, now))
    }

    @Test
    fun weekdaysSkipWeekend() {
        val fridayMorning = at(2026, 10, 23, 8, 0) // 금요일, 7시는 이미 지남
        assertEquals(at(2026, 10, 26, 7, 0), AlarmMath.nextOccurrence(7, 0, Days.WEEKDAYS, fridayMorning))
    }

    @Test
    fun weeklySameDayPassedGoesToNextWeek() {
        val tuesdayOnly = Days.bit(DayOfWeek.TUESDAY)
        val now = at(2026, 10, 20, 8, 0)
        assertEquals(at(2026, 10, 27, 7, 0), AlarmMath.nextOccurrence(7, 0, tuesdayOnly, now))
    }

    @Test
    fun weekendFromTuesday() {
        val now = at(2026, 10, 20, 8, 0)
        assertEquals(at(2026, 10, 24, 9, 30), AlarmMath.nextOccurrence(9, 30, Days.WEEKEND, now))
    }

    @Test
    fun dstGapStillReturnsFutureTime() {
        val ny = ZoneId.of("America/New_York")
        val now = at(2026, 3, 8, 1, 0, zone = ny) // 이날 02:00~03:00 은 존재하지 않음
        val next = AlarmMath.nextOccurrence(2, 30, Days.NONE, now)
        assertTrue(next.isAfter(now))
        assertEquals(8, next.dayOfMonth)
    }

    @Test
    fun snoozeTakesPriorityOverRegularTime() {
        val now = at(2026, 10, 20, 7, 2)
        val snoozeAt = now.plusMinutes(3).toInstant().toEpochMilli()
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Days.EVERYDAY, snoozedUntil = snoozeAt)
        assertEquals(snoozeAt, AlarmMath.nextTriggerMillis(alarm, now))
    }

    @Test
    fun staleSnoozeIsIgnored() {
        val now = at(2026, 10, 20, 7, 30)
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Days.EVERYDAY,
            snoozedUntil = now.minusMinutes(5).toInstant().toEpochMilli())
        assertEquals(at(2026, 10, 21, 7, 0).toInstant().toEpochMilli(), AlarmMath.nextTriggerMillis(alarm, now))
    }

    @Test
    fun disabledAlarmHasNoTrigger() {
        assertNull(AlarmMath.nextTriggerMillis(Alarm(enabled = false), at(2026, 10, 20, 7, 0)))
    }

    @Test
    fun remainingText() {
        val h = 3_600_000L
        val m = 60_000L
        assertEquals("8시간", AlarmMath.describeRemaining(0, 8 * h))
        assertEquals("8시간", AlarmMath.describeRemaining(0, 7 * h + 59 * m + 40_000)) // 올림
        assertEquals("1분 이내", AlarmMath.describeRemaining(0, 30_000))
        assertEquals("1일 2시간 3분", AlarmMath.describeRemaining(0, 26 * h + 3 * m))
        assertEquals("5분", AlarmMath.describeRemaining(0, 5 * m))
    }

    @Test
    fun timeFormatting() {
        assertEquals("오전 12:05", AlarmMath.formatTime(0, 5, false))
        assertEquals("오후 12:00", AlarmMath.formatTime(12, 0, false))
        assertEquals("오전 6:30", AlarmMath.formatTime(6, 30, false))
        assertEquals("오후 11:59", AlarmMath.formatTime(23, 59, false))
        assertEquals("18:30", AlarmMath.formatTime(18, 30, true))
        assertEquals("06:05", AlarmMath.formatTime(6, 5, true))
    }

    @Test
    fun daysDescription() {
        assertEquals("반복 안 함", Days.describe(Days.NONE))
        assertEquals("매일", Days.describe(Days.EVERYDAY))
        assertEquals("주중", Days.describe(Days.WEEKDAYS))
        assertEquals("주말", Days.describe(Days.WEEKEND))
        assertEquals("월 수 금", Days.describe(0b0010101))
        assertEquals(Days.WEEKDAYS or Days.bit(DayOfWeek.SATURDAY), Days.toggle(Days.WEEKDAYS, 5))
    }

    @Test
    fun snoozeLimits() {
        val a = Alarm(snoozeEnabled = true, snoozeLimit = 2, snoozeCount = 1)
        assertTrue(a.canSnooze())
        assertEquals(1, a.snoozesLeft())
        assertEquals(false, a.copy(snoozeCount = 2).canSnooze())
        assertTrue(a.copy(snoozeLimit = Alarm.SNOOZE_UNLIMITED, snoozeCount = 99).canSnooze())
        assertNull(a.copy(snoozeLimit = Alarm.SNOOZE_UNLIMITED).snoozesLeft())
        assertEquals(false, a.copy(snoozeEnabled = false).canSnooze())
    }
}

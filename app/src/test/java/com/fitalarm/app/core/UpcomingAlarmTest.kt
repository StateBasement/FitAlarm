package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UpcomingAlarmTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test
    fun labels() {
        assertEquals("끄기", UpcomingAlarm.label(0))
        assertEquals("30분 전", UpcomingAlarm.label(30))
        assertEquals("1시간 전", UpcomingAlarm.label(60))
        assertEquals("2시간 전", UpcomingAlarm.label(120))
        assertTrue(UpcomingAlarm.DEFAULT_MINUTES in UpcomingAlarm.CHOICES)
    }

    @Test
    fun showsOnlyInsideTheWindow() {
        val trigger = ms(at(2026, 11, 16, 7, 0))
        assertEquals(ms(at(2026, 11, 16, 6, 30)), UpcomingAlarm.showAt(trigger, 30))
        assertNull(UpcomingAlarm.showAt(trigger, 0))
        assertFalse(UpcomingAlarm.shouldShowNow(trigger, 30, ms(at(2026, 11, 16, 6, 29))))
        assertTrue(UpcomingAlarm.shouldShowNow(trigger, 30, ms(at(2026, 11, 16, 6, 30))))
        assertTrue(UpcomingAlarm.shouldShowNow(trigger, 30, ms(at(2026, 11, 16, 6, 59))))
        assertFalse(UpcomingAlarm.shouldShowNow(trigger, 30, trigger))   // 울리는 순간부터는 알람 화면이 대신
        assertFalse(UpcomingAlarm.shouldShowNow(trigger, 0, ms(at(2026, 11, 16, 6, 50))))
    }

    @Test
    fun cancelRepeatingSkipsOnlyThisTime() {
        val a = Alarm(id = 1, hour = 7, minute = 0, days = Days.WEEKDAYS)
        val now = at(2026, 11, 16, 6, 40)   // 월요일
        val trigger = AlarmMath.nextTriggerMillis(a, now)!!
        assertEquals(ms(at(2026, 11, 16, 7, 0)), trigger)
        val cancelled = UpcomingAlarm.cancel(a, trigger)
        assertTrue(cancelled.enabled)
        // 월요일 7시는 건너뛰고 화요일 7시
        assertEquals(ms(at(2026, 11, 17, 7, 0)), AlarmMath.nextTriggerMillis(cancelled, now))
        // 취소한 회차가 지나면 원래대로
        assertEquals(ms(at(2026, 11, 17, 7, 0)), AlarmMath.nextTriggerMillis(cancelled, at(2026, 11, 16, 7, 5)))
    }

    @Test
    fun cancelOneTimeTurnsItOff() {
        val a = Alarm(id = 2, hour = 7, minute = 0, days = Days.NONE)
        val now = at(2026, 11, 16, 6, 40)
        val trigger = AlarmMath.nextTriggerMillis(a, now)!!
        val cancelled = UpcomingAlarm.cancel(a, trigger)
        assertFalse(cancelled.enabled)
        assertNull(AlarmMath.nextTriggerMillis(cancelled, now))
    }

    @Test
    fun cancelClearsPendingSnooze() {
        val a = Alarm(id = 3, hour = 7, minute = 0, days = Days.EVERYDAY, snoozeCount = 1, snoozedUntil = 123L)
        val c = UpcomingAlarm.cancel(a, 456L)
        assertEquals(0L, c.snoozedUntil)
        assertEquals(0, c.snoozeCount)
        assertEquals(456L, c.skipUntil)
    }
}

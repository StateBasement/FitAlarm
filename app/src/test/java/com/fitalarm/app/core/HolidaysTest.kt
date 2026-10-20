package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HolidaysTest {

    private fun dates(year: Int) = KoreanHolidays.forYear(year).map { it.date }.toSet()
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    /** 한국천문연구원 2027년 월력요항과 정확히 일치해야 함 */
    @Test
    fun matchesOfficial2027() {
        val expected = setOf(
            d(2027, 1, 1), d(2027, 2, 6), d(2027, 2, 7), d(2027, 2, 8), d(2027, 2, 9),
            d(2027, 3, 1), d(2027, 5, 1), d(2027, 5, 3), d(2027, 5, 5), d(2027, 5, 13),
            d(2027, 6, 6), d(2027, 7, 17), d(2027, 7, 19), d(2027, 8, 15), d(2027, 8, 16),
            d(2027, 9, 14), d(2027, 9, 15), d(2027, 9, 16), d(2027, 10, 3), d(2027, 10, 4),
            d(2027, 10, 9), d(2027, 10, 11), d(2027, 12, 25), d(2027, 12, 27),
        )
        assertEquals(expected, dates(2027))
        assertEquals("대체공휴일(설날)", KoreanHolidays.nameOf(d(2027, 2, 9)))
        assertEquals("대체공휴일(제헌절)", KoreanHolidays.nameOf(d(2027, 7, 19)))
        assertEquals("대체공휴일(노동절)", KoreanHolidays.nameOf(d(2027, 5, 3)))
    }

    /** 2028년 월력요항: 추석과 개천절이 겹쳐 10/5 대체공휴일, 국회의원선거일 */
    @Test
    fun matchesOfficial2028() {
        val expected = setOf(
            d(2028, 1, 1), d(2028, 1, 26), d(2028, 1, 27), d(2028, 1, 28), d(2028, 3, 1),
            d(2028, 4, 12), d(2028, 5, 1), d(2028, 5, 2), d(2028, 5, 5), d(2028, 6, 6),
            d(2028, 7, 17), d(2028, 8, 15), d(2028, 10, 2), d(2028, 10, 3), d(2028, 10, 4),
            d(2028, 10, 5), d(2028, 10, 9), d(2028, 12, 25),
        )
        assertEquals(expected, dates(2028))
        val oct3 = KoreanHolidays.nameOf(d(2028, 10, 3))!!
        assertTrue(oct3.contains("추석") && oct3.contains("개천절"))
    }

    @Test
    fun substituteHolidays2026To2030() {
        // 2026 개천절(토) → 10/5(월)
        assertEquals("대체공휴일(개천절)", KoreanHolidays.nameOf(d(2026, 10, 5)))
        // 2029: 어린이날(토) → 5/7, 부처님오신날(일) → 5/21, 추석 연휴 일요일 → 9/24
        assertTrue(d(2029, 5, 7) in dates(2029))
        assertTrue(d(2029, 5, 21) in dates(2029))
        assertTrue(d(2029, 9, 24) in dates(2029))
        // 2030: 설날(일) → 2/5, 어린이날(일) → 5/6
        assertTrue(d(2030, 2, 5) in dates(2030))
        assertTrue(d(2030, 5, 6) in dates(2030))
        // 현충일·1월 1일은 주말이어도 대체공휴일 없음
        assertFalse(d(2027, 6, 7) in dates(2027))
        assertFalse(d(2028, 1, 3) in dates(2028))
    }

    @Test
    fun unknownLunarYearsAreNotGuessed() {
        assertFalse(KoreanHolidays.hasLunarData(2033))
        val y = dates(2033)
        assertTrue(d(2033, 1, 1) in y)
        assertTrue(d(2033, 12, 26) in y)          // 성탄절(일) 대체공휴일
        assertTrue(KoreanHolidays.forYear(2033).none { it.name.contains("설날") || it.name.contains("추석") })
    }

    @Test
    fun icsParsing() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20270207
            DTEND;VALUE=DATE:20270208
            SUMMARY:설날
            DESCRIPTION:공휴일
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20270508
            DTEND;VALUE=DATE:20270509
            SUMMARY:어버이날
            DESCRIPTION:기념일\nTo hide observances, go to Google Calendar Settings
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20271224
            SUMMARY:크리스마스 이브
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20260603
            DTEND;VALUE=DATE:20260604
            SUMMARY:전국동시지방선거
            END:VEVENT
            BEGIN:VEVENT
            DTSTART;VALUE=DATE:20281002
            DTEND;VALUE=DATE:20281005
            SUMMARY:추석 연
             휴
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val list = IcsHolidayParser.parse(ics)
        val byDate = list.associate { it.date to it.name }
        assertEquals("설날", byDate[d(2027, 2, 7)])
        assertNull(byDate[d(2027, 5, 8)])              // 기념일은 쉬는 날 아님
        assertNull(byDate[d(2027, 12, 24)])            // 이브 제외
        assertEquals("전국동시지방선거", byDate[d(2026, 6, 3)])
        assertEquals("추석 연휴", byDate[d(2028, 10, 2)])  // 접힌 줄 이어붙이기
        assertTrue(d(2028, 10, 4) in byDate)            // 여러 날 일정
        assertFalse(d(2028, 10, 5) in byDate)           // DTEND 는 미포함
    }

    @Test
    fun holidayNameFilter() {
        assertTrue(HolidayNames.isDayOff("임시공휴일", null))
        assertTrue(HolidayNames.isDayOff("대체공휴일(어린이날)", null))
        assertTrue(HolidayNames.isDayOff("국군의 날", "공휴일"))
        assertFalse(HolidayNames.isDayOff("국군의 날", "기념일"))
        assertFalse(HolidayNames.isDayOff("식목일", null))
    }

    @Test
    fun repeatingAlarmSkipsHolidays() {
        val seoul = ZoneId.of("Asia/Seoul")
        val skip = SkipRule { KoreanHolidays.nameOf(it) }
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Days.WEEKDAYS)
        // 2026-12-24(목) 08:00 → 12/25(금) 성탄절 건너뛰고 12/28(월)
        val now = ZonedDateTime.of(2026, 12, 24, 8, 0, 0, 0, seoul)
        val next = AlarmMath.nextTriggerMillis(alarm, now, skip)
        assertEquals(ZonedDateTime.of(2026, 12, 28, 7, 0, 0, 0, seoul).toInstant().toEpochMilli(), next)
        val skipped = AlarmMath.nextSkippedDay(alarm, now, skip)
        assertEquals(d(2026, 12, 25), skipped!!.first)
        assertEquals("기독탄신일", skipped.second)
        // 옵션을 끄면 그대로 12/25
        val off = AlarmMath.nextTriggerMillis(alarm, now, null)
        assertEquals(ZonedDateTime.of(2026, 12, 25, 7, 0, 0, 0, seoul).toInstant().toEpochMilli(), off)
    }

    @Test
    fun oneShotAlarmIgnoresSkip() {
        val seoul = ZoneId.of("Asia/Seoul")
        val skip = SkipRule { KoreanHolidays.nameOf(it) }
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Days.NONE)
        val now = ZonedDateTime.of(2026, 12, 24, 8, 0, 0, 0, seoul)
        assertEquals(
            ZonedDateTime.of(2026, 12, 25, 7, 0, 0, 0, seoul).toInstant().toEpochMilli(),
            AlarmMath.nextTriggerMillis(alarm, now, skip),
        )
    }

    @Test
    fun allDaysSkippedFallsBackInsteadOfHanging() {
        val seoul = ZoneId.of("Asia/Seoul")
        val now = ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, seoul)
        val next = AlarmMath.nextOccurrence(7, 0, Days.EVERYDAY, now, SkipRule { "고장" })
        assertEquals(ZonedDateTime.of(2026, 10, 22, 7, 0, 0, 0, seoul), next)
    }
}

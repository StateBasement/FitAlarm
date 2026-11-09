package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class NunbodyTest {
    private fun photo(at: String, w: Double? = null, h: Double? = null, memo: String = "") = NunbodyPhoto(
        id = NunbodyNames.photo(LocalDateTime.parse(at)),
        takenAt = LocalDateTime.parse(at),
        width = 3000, height = 4000, source = PhotoSource.APP_BACK,
        weightKg = w, heightCm = h, memo = memo,
    )

    // ------------------------------------------------------------ 파일 이름

    @Test
    fun fileNamesLookLikeCameraApps() {
        val t = LocalDateTime.of(2026, 11, 1, 8, 20, 15)
        assertEquals("AlarmFit_20261101_082015.jpg", NunbodyNames.photo(t))
        assertEquals("AlarmFit_20261101_082015_2.jpg", NunbodyNames.photo(t, setOf("AlarmFit_20261101_082015.jpg")))
        assertEquals(
            "AlarmFit_20261101_082015_3.jpg",
            NunbodyNames.photo(t, setOf("AlarmFit_20261101_082015.jpg", "AlarmFit_20261101_082015_2.jpg")),
        )
        assertEquals("AlarmFit_20260809-20261109.csv", NunbodyNames.table(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun safeNamesRejectPaths() {
        assertTrue(NunbodyNames.isSafeName("AlarmFit_20261101_082015.jpg"))
        assertFalse(NunbodyNames.isSafeName("../index.json"))
        assertFalse(NunbodyNames.isSafeName("a/b.jpg"))
        assertFalse(NunbodyNames.isSafeName(".hidden"))
        assertFalse(NunbodyNames.isSafeName(""))
    }

    // ------------------------------------------------------------ 화질

    private val phone12 = listOf(
        PhotoSize(4000, 3000), PhotoSize(4000, 2250), PhotoSize(3264, 2448), PhotoSize(2592, 1944),
        PhotoSize(2048, 1536), PhotoSize(1600, 1200), PhotoSize(1280, 960), PhotoSize(640, 480),
    )

    @Test
    fun qualityTiersFor12mpPhone() {
        val o = PhotoQuality.options(phone12)
        assertEquals(listOf(QualityTier.MAX, QualityTier.HIGH, QualityTier.MID, QualityTier.LOW), o.map { it.tier })
        assertEquals(PhotoSize(4000, 3000), o[0].size)
        assertEquals(PhotoSize(3264, 2448), o[1].size)
        assertEquals(PhotoSize(2592, 1944), o[2].size)
        assertEquals(PhotoSize(1600, 1200), o[3].size)
        assertEquals(listOf("1200만", "800만", "500만", "200만"), o.map { PhotoQuality.megapixelLabel(it.size) })
    }

    @Test
    fun qualityTiersFollowWhatThePhoneHas() {
        // 800만이 최대인 폰: 800만이 '최고', 단계마다 더 작은 크기
        val o = PhotoQuality.options(listOf(PhotoSize(3264, 2448), PhotoSize(2592, 1944), PhotoSize(1600, 1200)))
        assertEquals(PhotoSize(3264, 2448), o[0].size)
        assertEquals(3, o.size)
        assertTrue(o.zipWithNext().all { (a, b) -> b.size.pixels < a.size.pixels })
        // 크기가 하나뿐이면 최고만
        assertEquals(1, PhotoQuality.options(listOf(PhotoSize(1920, 1440))).size)
        assertTrue(PhotoQuality.options(emptyList()).isEmpty())
    }

    @Test
    fun missingTierFallsBackToHigherOne() {
        val o = PhotoQuality.options(listOf(PhotoSize(3264, 2448), PhotoSize(2592, 1944)))
        assertEquals(QualityTier.HIGH, PhotoQuality.pick(o, QualityTier.LOW)?.tier)
        assertEquals(QualityTier.MAX, PhotoQuality.pick(o, QualityTier.MAX)?.tier)
    }

    @Test
    fun byteSizes() {
        assertEquals("4MB", formatBytes(PhotoQuality.estimatedBytes(PhotoSize(4000, 3000))))
        assertEquals("0.6MB", formatBytes(PhotoQuality.estimatedBytes(PhotoSize(1600, 1200))))
        assertEquals("48MB", formatBytes(48_200_000))
        assertEquals("0MB", formatBytes(0))
    }

    // ------------------------------------------------------------ 체중·키

    @Test
    fun parsesBodyNumbers() {
        assertEquals(BodyNumbers.Parsed.Value(68.2), BodyNumbers.parse(" 68,2 ", BodyNumbers.MIN_WEIGHT, BodyNumbers.MAX_WEIGHT))
        assertEquals(BodyNumbers.Parsed.Empty, BodyNumbers.parse("", BodyNumbers.MIN_WEIGHT, BodyNumbers.MAX_WEIGHT))
        assertEquals(BodyNumbers.Parsed.Invalid, BodyNumbers.parse("6.8", BodyNumbers.MIN_WEIGHT, BodyNumbers.MAX_WEIGHT))
        assertEquals(BodyNumbers.Parsed.Invalid, BodyNumbers.parse("abc", BodyNumbers.MIN_HEIGHT, BodyNumbers.MAX_HEIGHT))
        assertEquals("68", BodyNumbers.format(68.0))
        assertEquals("−1.4kg", BodyNumbers.formatChange(-1.4))
        assertEquals("+0.6kg", BodyNumbers.formatChange(0.6))
        assertEquals("0kg", BodyNumbers.formatChange(0.01))
    }

    @Test
    fun weightChanges() {
        val list = listOf(
            photo("2026-06-01T08:00", 71.3, 175.0),
            photo("2026-08-01T08:00"),
            photo("2026-10-01T08:00", 69.6),
            photo("2026-11-01T08:20", 68.2),
        )
        assertEquals(68.2, NunbodyStats.latestWeight(list)!!.weightKg!!, 1e-9)
        assertEquals(-1.4, NunbodyStats.changeSincePrevious(list)!!, 1e-9)
        val (delta, months) = NunbodyStats.changeSince(list, LocalDate.of(2026, 5, 9))!!
        assertEquals(-3.1, delta, 1e-9)
        assertEquals(5L, months)
        assertEquals(175.0, NunbodyStats.latestHeight(list)!!, 1e-9)
        assertNull(NunbodyStats.changeSincePrevious(list.take(2)))
    }

    // ------------------------------------------------------------ CSV

    @Test
    fun csvEscapesAndBlocksFormulas() {
        assertEquals("아침 공복", NunbodyCsv.cell("아침 공복"))
        assertEquals("\"a,b\"", NunbodyCsv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", NunbodyCsv.cell("say \"hi\""))
        assertEquals("'=1+1", NunbodyCsv.cell("=1+1"))
        assertEquals("\"줄\n바꿈\"", NunbodyCsv.cell("줄\r\n바꿈"))
        val csv = NunbodyCsv.build(listOf(photo("2026-11-01T08:20", 68.2, 175.0, "저녁 운동 후"), photo("2026-10-01T07:05")))
        val lines = csv.split("\r\n")
        assertTrue(lines[0].startsWith("﻿날짜"))
        assertEquals("2026-10-01,07:05,,,,AlarmFit_20261001_070500.jpg", lines[1])
        assertEquals("2026-11-01,08:20,68.2,175,저녁 운동 후,AlarmFit_20261101_082000.jpg", lines[2])
    }

    // ------------------------------------------------------------ 기간

    @Test
    fun rangePresets() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(LocalDate.of(2026, 11, 1) to today, NunbodyRanges.preset(RangePreset.THIS_MONTH, today, null))
        assertEquals(LocalDate.of(2026, 8, 9) to today, NunbodyRanges.preset(RangePreset.MONTHS_3, today, null))
        assertEquals(LocalDate.of(2026, 1, 3) to today, NunbodyRanges.preset(RangePreset.ALL, today, LocalDate.of(2026, 1, 3)))
        val list = listOf(photo("2026-11-01T08:20"), photo("2026-08-15T08:12"), photo("2026-07-01T08:00"))
        val inRange = NunbodyRanges.inRange(list, LocalDate.of(2026, 8, 9), today)
        assertEquals(listOf("2026-08-15", "2026-11-01"), inRange.map { it.date.toString() })
    }

    @Test
    fun calendarTapSelection() {
        val today = LocalDate.of(2026, 11, 9)
        val r = RangeSelection(today = today)
        r.tap(LocalDate.of(2026, 10, 5))
        assertEquals(LocalDate.of(2026, 10, 5) to LocalDate.of(2026, 10, 5), r.result())
        r.tap(LocalDate.of(2026, 10, 1))          // 시작보다 앞 → 시작이 바뀜
        assertEquals(LocalDate.of(2026, 10, 1), r.start)
        r.tap(LocalDate.of(2026, 10, 24))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 24), r.result())
        assertTrue(r.contains(LocalDate.of(2026, 10, 15)))
        r.tap(LocalDate.of(2026, 12, 25))         // 둘 다 있으면 새로 시작, 오늘 이후는 오늘로
        assertEquals(today, r.start)
        assertNull(r.end)
    }

    @Test
    fun calendarDragSelection() {
        val r = RangeSelection(today = LocalDate.of(2026, 11, 9))
        r.dragStart(LocalDate.of(2026, 10, 10))
        r.dragTo(LocalDate.of(2026, 10, 3))       // 뒤로 끌기
        assertEquals(LocalDate.of(2026, 10, 3) to LocalDate.of(2026, 10, 10), r.result())
        r.dragTo(LocalDate.of(2026, 10, 20))      // 다시 앞으로
        assertEquals(LocalDate.of(2026, 10, 10) to LocalDate.of(2026, 10, 20), r.result())
        r.dragEnd()
    }

    // ------------------------------------------------------------ 촬영 알림

    @Test
    fun reminderDates() {
        val nine = LocalTime.of(9, 0)
        val mon = LocalDateTime.of(2026, 11, 9, 8, 0)   // 월요일 오전 8시
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), NunbodyReminderMath.next(mon, ReminderPeriod.WEEKLY, nine, mon.toLocalDate()))
        val after = LocalDateTime.of(2026, 11, 9, 9, 30)
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 0), NunbodyReminderMath.next(after, ReminderPeriod.WEEKLY, nine, mon.toLocalDate()))
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 0), NunbodyReminderMath.next(after, ReminderPeriod.MONTHLY, nine, mon.toLocalDate()))
        val first = LocalDateTime.of(2026, 12, 1, 7, 0)
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 0), NunbodyReminderMath.next(first, ReminderPeriod.MONTHLY, nine, mon.toLocalDate()))
        // 2주마다: 켠 주의 월요일(11/9)부터 2주 간격 → 11/9 지난 뒤면 11/23
        val anchor = LocalDate.of(2026, 11, 11)
        assertEquals(LocalDateTime.of(2026, 11, 23, 9, 0), NunbodyReminderMath.next(after, ReminderPeriod.BIWEEKLY, nine, anchor))
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), NunbodyReminderMath.next(mon, ReminderPeriod.BIWEEKLY, nine, anchor))
        val wed = LocalDateTime.of(2026, 11, 18, 12, 0)
        assertEquals(LocalDateTime.of(2026, 11, 23, 9, 0), NunbodyReminderMath.next(wed, ReminderPeriod.BIWEEKLY, nine, anchor))
    }

    // ------------------------------------------------------------ 사진 방향

    @Test
    fun mirroringKeepsWhatThePreviewShowed() {
        assertEquals(2, ExifMath.mirrored(1))
        assertEquals(5, ExifMath.mirrored(6))
        assertEquals(4, ExifMath.mirrored(3))
        assertEquals(7, ExifMath.mirrored(8))
        for (o in 1..8) assertEquals(if (o == 0) 1 else o, ExifMath.mirrored(ExifMath.mirrored(o)))
        for (o in 1..8) assertEquals(ExifMath.rotationOf(o), ExifMath.rotationOf(ExifMath.mirrored(o)))
        assertTrue(ExifMath.isMirrored(ExifMath.mirrored(6)))
    }

    @Test
    fun noLockWarningSnoozesForAWeek() {
        val now = 1_000_000L
        assertTrue(NoLockWarning.due(now, 0))
        val until = NoLockWarning.snoozeUntil(now)
        assertFalse(NoLockWarning.due(now + 6L * 24 * 3600 * 1000, until))
        assertTrue(NoLockWarning.due(now + 7L * 24 * 3600 * 1000, until))
    }
}

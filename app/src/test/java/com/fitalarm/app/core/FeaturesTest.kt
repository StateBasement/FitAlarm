package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.random.Random

/** 보너스 운동, 개인 보정, 기록 통계, 음성 문장 */
class FeaturesTest {

    // ------------------------------------------------------------ 보너스 운동

    private val morning = listOf(RoutineStep(ExerciseType.SQUAT, 15), RoutineStep(ExerciseType.JUMPING_JACK, 20))

    @Test
    fun bonusAvoidsUsedBodyPartsAndMatchesIntensity() {
        val seen = HashMap<ExerciseType, Int>()
        repeat(200) { i ->
            val p = BonusPlanner.plan(morning, level = 0, lastBonus = null, random = Random(i))!!
            seen[p.step.type] = p.step.reps
        }
        // 하체·전신 제외 → 상체/코어 중 반복 수준이 맞는 것만: 팔굽혀펴기 15, 윗몸일으키기 30 (만세 85회는 제외)
        assertEquals(mapOf(ExerciseType.PUSH_UP to 15, ExerciseType.SIT_UP to 30), seen)
    }

    @Test
    fun bonusAvoidsRepeatAndScalesWithLevel() {
        val p = BonusPlanner.plan(morning, 0, ExerciseType.PUSH_UP, Random(1))!!
        assertEquals(ExerciseType.SIT_UP, p.step.type)
        assertEquals("오늘은 하체·전신을 했으니 이번엔 코어로", p.headline)
        // 최고 단계(+20%): 강도 30점 → 팔굽혀펴기 20회, 윗몸일으키기 35회 (둘 다 기본보다 많음)
        val strong = (0 until 100).map { BonusPlanner.plan(morning, BonusPlanner.MAX_LEVEL, null, Random(it))!!.step }.toSet()
        assertEquals(setOf(RoutineStep(ExerciseType.PUSH_UP, 20), RoutineStep(ExerciseType.SIT_UP, 35)), strong)
        assertEquals(4, BonusPlanner.nextLevel(4, completed = true))
        assertEquals(0, BonusPlanner.nextLevel(0, completed = false))
        assertEquals(2, BonusPlanner.nextLevel(3, completed = false))
    }

    @Test
    fun bonusGrowsWithAlarmReps() {
        val light = BonusPlanner.plan(listOf(RoutineStep(ExerciseType.SQUAT, 10)), 0, ExerciseType.SIT_UP, Random(0))!!
        val heavy = BonusPlanner.plan(listOf(RoutineStep(ExerciseType.SQUAT, 30)), 0, ExerciseType.SIT_UP, Random(0))!!
        assertTrue(heavy.step.reps > light.step.reps || heavy.step.type != light.step.type)
        assertTrue(heavy.minutes >= 1)
    }

    @Test
    fun josa() {
        assertEquals("전신으로", Josa.euroRo("전신"))
        assertEquals("하체로", Josa.euroRo("하체"))
        assertEquals("하체·전신을", Josa.eulReul("하체·전신"))
        assertEquals("상체를", Josa.eulReul("상체"))
    }

    // ------------------------------------------------------------ 개인 보정

    private var t = 0L
    private fun legs(depth: Float): PoseFrame {
        // 다리를 곧게 세운 채 엉덩이 높이만 바꿔 깊이 비율을 정확히 맞춤 (무릎 각도 180°)
        val hipY = 0.75f - depth * 0.25f
        val m = HashMap<Int, Pt>()
        for (s in Side.entries) {
            m[s.hip] = Pt(0.5f, hipY); m[s.knee] = Pt(0.5f, 0.75f); m[s.ankle] = Pt(0.5f, 1.0f)
        }
        return PoseFrame.of(m)
    }

    private fun rep(c: RepCounter, depth: Float) {
        repeat(6) { t += 50; c.update(legs(1.0f), t) }
        repeat(6) { t += 50; c.update(legs(depth), t) }
        repeat(6) { t += 50; c.update(legs(1.0f), t) }
    }

    @Test
    fun calibrationLearnsNearMissesUpToTheFloor() {
        val book = CalibrationBook(enabled = true)
        val c = RepCounter.create(ExerciseType.SQUAT, book)
        repeat(3) { rep(c, 0.5f) }                 // 기본 기준(0.45)에 살짝 못 미침 → 학습만
        assertEquals(0, c.count)
        assertTrue(book.dirty)
        repeat(2) { rep(c, 0.5f) }                 // 이제 내 범위에 맞춰져서 인정
        assertEquals(2, c.count)
        assertNotNull(book.snapshot()["squat.depth"])
    }

    @Test
    fun calibrationNeverAcceptsRepsShallowerThanTheFloor() {
        val book = CalibrationBook(enabled = true)
        val c = RepCounter.create(ExerciseType.SQUAT, book)
        repeat(8) { rep(c, 0.65f) }
        assertEquals(0, c.count)
        assertTrue(book.snapshot().isEmpty())     // 최소 기준선 밖의 동작은 학습하지 않음
    }

    @Test
    fun calibrationOffKeepsDefaults() {
        val book = CalibrationBook(enabled = false)
        val c = RepCounter.create(ExerciseType.SQUAT, book)
        repeat(6) { rep(c, 0.5f) }
        assertEquals(0, c.count)
        assertFalse(book.dirty)
    }

    @Test
    fun calibrationSurvivesSaveAndRestore() {
        val book = CalibrationBook(enabled = true)
        val c = RepCounter.create(ExerciseType.SQUAT, book)
        repeat(3) { rep(c, 0.5f) }
        val restored = CalibrationBook(enabled = true).apply { restore(book.snapshot()) }
        val c2 = RepCounter.create(ExerciseType.SQUAT, restored)
        repeat(2) { rep(c2, 0.5f) }
        assertEquals(2, c2.count)
    }

    // ------------------------------------------------------------ 기록

    private fun e(day: Int, kind: RecordKind, reps: Map<ExerciseType, Int> = emptyMap(), ms: Long = 0) =
        RecordEntry(LocalDate.of(2026, 10, day), kind, reps, ms)

    @Test
    fun streakSkipsDaysWithoutAlarmsAndStopsAtMissed() {
        val list = listOf(
            e(5, RecordKind.MISSION), e(6, RecordKind.MISSED), e(7, RecordKind.MISSION), e(8, RecordKind.DISMISS),
            e(12, RecordKind.MISSION), e(12, RecordKind.BONUS), e(13, RecordKind.MISSION),
        )
        assertEquals(4, RecordStats.currentStreak(list, LocalDate.of(2026, 10, 14)))
        assertEquals(4, RecordStats.bestStreak(list))
        assertEquals(0, RecordStats.currentStreak(list + e(14, RecordKind.MISSED), LocalDate.of(2026, 10, 14)))
        val days = RecordStats.days(list)
        assertTrue(days[LocalDate.of(2026, 10, 12)]!!.bonus)
        assertEquals(DayResult.MISSED, days[LocalDate.of(2026, 10, 6)]!!.result)
    }

    @Test
    fun monthSummary() {
        val list = listOf(
            e(1, RecordKind.MISSION, mapOf(ExerciseType.SQUAT to 15), 120_000),
            e(2, RecordKind.MISSION, mapOf(ExerciseType.SQUAT to 15), 180_000),
            e(2, RecordKind.BONUS, mapOf(ExerciseType.PUSH_UP to 15)),
            e(3, RecordKind.MISSED),
        )
        val s = RecordStats.month(list, YearMonth.of(2026, 10))
        assertEquals(2, s.successes)
        assertEquals(3, s.alarmDays)
        assertEquals(1, s.bonusSets)
        assertEquals(150_000L, s.avgMissionMs)
        assertEquals(30, s.missionReps[ExerciseType.SQUAT])
        assertEquals(15, s.bonusReps[ExerciseType.PUSH_UP])
    }

    // ------------------------------------------------------------ 음성 문장

    @Test
    fun koreanNumbers() {
        assertEquals("하나", KoreanSpeech.nativeNumber(1))
        assertEquals("열하나", KoreanSpeech.nativeNumber(11))
        assertEquals("스물", KoreanSpeech.nativeNumber(20))
        assertEquals("아흔아홉", KoreanSpeech.nativeNumber(99))
        assertEquals("150", KoreanSpeech.nativeNumber(150))
        assertEquals("열둘, 세 개 남았어요", KoreanSpeech.countPhrase(12, 15))
        assertEquals("열다섯", KoreanSpeech.countPhrase(15, 15))
        assertEquals("오전 여섯 시 30분", KoreanSpeech.timePhrase(6, 30))
        assertEquals("오후 열두 시", KoreanSpeech.timePhrase(12, 0))
    }

    @Test
    fun announcementText() {
        val text = KoreanSpeech.announcement(
            LocalDateTime.of(2026, 10, 21, 6, 30), "평일 기상", morning, hasMission = true, isSnooze = false,
        )
        assertEquals(
            "좋은 아침이에요. 10월 21일 수요일, 오전 여섯 시 30분입니다. 평일 기상 알람이에요. " +
                "오늘의 운동은 스쿼트 15회, 점핑잭 20회입니다.",
            text,
        )
        val snooze = KoreanSpeech.announcement(LocalDateTime.of(2026, 10, 21, 6, 35), "", emptyList(), false, true)
        assertTrue(snooze.contains("다시 알림이에요."))
        assertTrue(snooze.endsWith("오늘도 힘차게 시작해 볼까요?"))
    }
}

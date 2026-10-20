package com.fitalarm.app.core

import java.time.LocalDate
import java.time.YearMonth

/** 기록 종류 */
enum class RecordKind(val key: String) {
    MISSION("mission"),       // 운동 미션 완료로 알람 끔
    DISMISS("dismiss"),       // 미션 없는 알람을 끔 (또는 카메라 불가 대비책)
    MISSED("missed"),         // 끄지 않아 시간 초과로 종료
    BONUS("bonus"),           // 보너스 운동 완료
    BONUS_QUIT("bonus_quit"); // 보너스 운동 중간에 그만둠

    companion object {
        fun fromKey(k: String?): RecordKind? = entries.firstOrNull { it.key == k }
    }
}

data class RecordEntry(
    val date: LocalDate,
    val kind: RecordKind,
    val reps: Map<ExerciseType, Int> = emptyMap(),
    val durationMs: Long = 0L,
    val alarmId: Int = 0,
    val atMillis: Long = 0L,
)

enum class DayResult { NONE, SUCCESS, MISSED }

data class DayInfo(val result: DayResult, val bonus: Boolean)

data class MonthSummary(
    val successes: Int,
    val alarmDays: Int,             // 성공 + 놓친 날
    val bonusSets: Int,
    val avgMissionMs: Long?,
    val missionReps: Map<ExerciseType, Int>,
    val bonusReps: Map<ExerciseType, Int>,
)

object RecordStats {

    /** 날짜별 결과. 같은 날 성공과 놓침이 모두 있으면 성공으로 봄 */
    fun days(entries: List<RecordEntry>): Map<LocalDate, DayInfo> {
        val out = HashMap<LocalDate, DayInfo>()
        for ((date, list) in entries.groupBy { it.date }) {
            val success = list.any { it.kind == RecordKind.MISSION || it.kind == RecordKind.DISMISS }
            val missed = list.any { it.kind == RecordKind.MISSED }
            val result = when {
                success -> DayResult.SUCCESS
                missed -> DayResult.MISSED
                else -> DayResult.NONE
            }
            out[date] = DayInfo(result, list.any { it.kind == RecordKind.BONUS })
        }
        return out
    }

    /**
     * 연속 성공 일수: 오늘부터 거꾸로 세며, 성공한 날 +1, 놓친 날에서 멈춤.
     * 알람이 없던 날·공휴일(기록 없음)은 건너뛰어 연속 기록이 끊기지 않음. 오늘 아직 기록이 없어도 끊기지 않음.
     */
    fun currentStreak(entries: List<RecordEntry>, today: LocalDate): Int {
        if (entries.isEmpty()) return 0
        val map = days(entries)
        val earliest = entries.minOf { it.date }
        var d = today
        var n = 0
        while (!d.isBefore(earliest)) {
            when (map[d]?.result) {
                DayResult.SUCCESS -> n++
                DayResult.MISSED -> return n
                else -> Unit
            }
            d = d.minusDays(1)
        }
        return n
    }

    fun bestStreak(entries: List<RecordEntry>): Int {
        if (entries.isEmpty()) return 0
        val map = days(entries)
        var best = 0
        var cur = 0
        for (date in map.keys.sorted()) {
            when (map[date]?.result) {
                DayResult.SUCCESS -> {
                    cur++
                    if (cur > best) best = cur
                }
                DayResult.MISSED -> cur = 0
                else -> Unit
            }
        }
        return best
    }

    fun month(entries: List<RecordEntry>, ym: YearMonth): MonthSummary {
        val inMonth = entries.filter { YearMonth.from(it.date) == ym }
        val map = days(inMonth)
        val successes = map.values.count { it.result == DayResult.SUCCESS }
        val missed = map.values.count { it.result == DayResult.MISSED }
        val missions = inMonth.filter { it.kind == RecordKind.MISSION && it.durationMs > 0 }
        val avg = if (missions.isEmpty()) null else missions.sumOf { it.durationMs } / missions.size
        return MonthSummary(
            successes = successes,
            alarmDays = successes + missed,
            bonusSets = inMonth.count { it.kind == RecordKind.BONUS },
            avgMissionMs = avg,
            missionReps = sumReps(inMonth.filter { it.kind == RecordKind.MISSION }),
            bonusReps = sumReps(inMonth.filter { it.kind == RecordKind.BONUS }),
        )
    }

    private fun sumReps(list: List<RecordEntry>): Map<ExerciseType, Int> {
        val out = LinkedHashMap<ExerciseType, Int>()
        for (e in list) for ((t, r) in e.reps) out[t] = (out[t] ?: 0) + r
        return out
    }
}

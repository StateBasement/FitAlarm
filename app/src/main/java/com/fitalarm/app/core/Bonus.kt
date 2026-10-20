package com.fitalarm.app.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/** 알람을 끈 뒤 한 번 제안하는 보너스 운동 */
data class BonusPlan(
    val step: RoutineStep,
    val minutes: Int,          // 예상 소요 시간 (올림)
    val headline: String,      // "오늘은 하체·전신을 했으니 이번엔 상체로"
    val level: Int,            // 적용된 강도 단계
)

/**
 * 보너스 운동 고르기.
 *  - 알람 루틴에 들어간 부위는 빼고 무작위로 고름 (가능하면 지난번과 다른 운동)
 *  - 강도: 알람 루틴의 총 강도 점수(횟수 × 1회 점수)에 맞춤 → 알람 횟수를 올리면 같이 오름
 *  - 반복 수준: 루틴 평균 횟수의 0.5~2배 안에 드는 운동만 후보
 *  - 점진 증가: 보너스를 끝까지 하면 다음 제안 +5% (최대 +20%), 중간에 그만두면 한 단계 낮춤
 */
object BonusPlanner {
    const val MAX_LEVEL = 4
    const val STEP = 0.05f
    private const val MIN_RATIO = 0.5
    private const val MAX_RATIO = 2.0

    fun plan(
        routine: List<RoutineStep>,
        level: Int,
        lastBonus: ExerciseType?,
        random: Random = Random.Default,
    ): BonusPlan? {
        if (routine.isEmpty()) return null
        val lv = level.coerceIn(0, MAX_LEVEL)
        val effort = routine.sumOf { (it.reps * it.type.effort).toDouble() } * (1.0 + STEP * lv)
        val avgReps = routine.map { it.reps }.average()
        val usedParts = routine.map { it.type.part }.distinct()

        var pool = ExerciseType.entries.filter { it.part !in usedParts }
        if (pool.isEmpty()) pool = ExerciseType.entries.filter { t -> routine.none { it.type == t } }
        if (pool.isEmpty()) pool = ExerciseType.entries

        val options = pool.map { it to repsFor(effort, it) }
        val fit = options.filter { (_, r) -> r >= avgReps * MIN_RATIO && r <= avgReps * MAX_RATIO }
        val candidates = fit.ifEmpty {
            // 반복 수준이 맞는 운동이 없으면 가장 가까운 것 하나
            listOf(options.minBy { (_, r) -> abs(r / avgReps - 1.0) })
        }
        val noRepeat = candidates.filter { it.first != lastBonus }.ifEmpty { candidates }
        val (type, reps) = noRepeat[random.nextInt(noRepeat.size)]

        val minutes = ceil(reps * type.secondsPerRep / 60.0).toInt().coerceAtLeast(1)
        return BonusPlan(RoutineStep(type, reps), minutes, headline(usedParts, type.part), lv)
    }

    /** 강도 점수를 그 운동의 횟수로 환산 (10회 이상은 5 단위) */
    fun repsFor(effort: Double, type: ExerciseType): Int {
        val raw = effort / type.effort
        val r = if (raw >= 10) (raw / 5.0).roundToInt() * 5 else raw.roundToInt()
        return r.coerceIn(3, 100)
    }

    fun nextLevel(level: Int, completed: Boolean): Int =
        if (completed) (level + 1).coerceAtMost(MAX_LEVEL) else (level - 1).coerceAtLeast(0)

    private fun headline(used: List<BodyPart>, next: BodyPart): String {
        val done = used.joinToString("·") { it.title }
        return "오늘은 ${Josa.eulReul(done)} 했으니 이번엔 ${Josa.euroRo(next.title)}"
    }
}

/** 받침에 따른 조사 */
object Josa {
    private fun lastHangul(word: String): Char? = word.lastOrNull { it in '가'..'힣' }

    private fun jong(word: String): Int {
        val c = lastHangul(word) ?: return 0
        return (c.code - 0xAC00) % 28
    }

    fun eulReul(word: String) = word + if (jong(word) != 0) "을" else "를"
    fun iGa(word: String) = word + if (jong(word) != 0) "이" else "가"

    /** ㄹ 받침은 "로" */
    fun euroRo(word: String): String {
        val j = jong(word)
        return word + if (j != 0 && j != 8) "으로" else "로"
    }
}

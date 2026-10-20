package com.fitalarm.app.core

import kotlin.math.max
import kotlin.math.min

/**
 * 개인 보정 값 모음. 키는 "squat.depth" 처럼 운동.측정값.
 * Android 쪽에서 SharedPreferences 로 저장·복원합니다.
 */
class CalibrationBook(var enabled: Boolean = true) {
    class Entry(var learned: Float? = null, var samples: Int = 0)

    private val entries = LinkedHashMap<String, Entry>()

    /** 학습이 일어났으면 true (저장 필요 표시) */
    var dirty: Boolean = false

    fun entry(key: String): Entry = entries.getOrPut(key) { Entry() }

    fun snapshot(): Map<String, Pair<Float, Int>> =
        entries.filter { it.value.learned != null }.mapValues { it.value.learned!! to it.value.samples }

    fun restore(values: Map<String, Pair<Float, Int>>) {
        for ((k, v) in values) {
            val e = entry(k)
            e.learned = v.first
            e.samples = v.second
        }
    }

    fun clear() {
        entries.clear()
        dirty = true
    }
}

/**
 * 측정값 하나의 판정 기준 (히스테리시스 + 개인 보정).
 *
 *  rest       시작 자세로 인정하는 값 (고정)
 *  default    아직 학습 전의 "동작" 기준
 *  loosest    아무리 학습해도 이보다 느슨해지지 않는 최소 기준선
 *  strictest  아무리 잘해도 이보다 엄격해지지 않는 상한
 *  lowerIsActive  값이 작아질수록 동작(각도·깊이) 인지, 커질수록 동작(팔 높이) 인지
 *
 * 학습: 시작 자세 → (움직임) → 시작 자세로 돌아오는 한 번의 왕복마다 도달한 극값을 기록하고,
 * 그 평균(EMA)을 "평소 도달하는 지점"으로 삼아, 시작 자세에서 그 지점까지 REACH(65%) 위치를 기준으로 씀.
 * 최소 기준선에도 못 미친 왕복은 학습하지 않으므로, 대충 하는 쪽으로 기준이 흘러가지 않음.
 */
class AdaptiveThreshold(
    private val book: CalibrationBook,
    private val entry: CalibrationBook.Entry,
    val rest: Float,
    val defaultActive: Float,
    val loosest: Float,
    val strictest: Float,
    val lowerIsActive: Boolean,
) {
    private var inExcursion = false
    private var extreme = 0f

    /** 지금 적용 중인 "동작" 기준 */
    val active: Float
        get() {
            val learned = entry.learned
            if (!book.enabled || learned == null || entry.samples < MIN_SAMPLES) return defaultActive
            val t = rest + (learned - rest) * REACH
            return if (lowerIsActive) t.coerceIn(strictest, loosest) else t.coerceIn(loosest, strictest)
        }

    val isCalibrated: Boolean get() = book.enabled && entry.learned != null && entry.samples >= MIN_SAMPLES

    private fun isRest(v: Float) = if (lowerIsActive) v >= rest else v <= rest
    private fun isActive(v: Float) = if (lowerIsActive) v <= active else v >= active
    private fun beyondFloor(v: Float) = if (lowerIsActive) v <= loosest else v >= loosest

    /** 평활화된 값 하나를 판정하고, 왕복이 끝나면 학습 */
    fun classify(v: Float): Obs {
        if (isRest(v)) {
            if (inExcursion) {
                inExcursion = false
                learn(extreme)
            }
            return Obs.REST
        }
        if (!inExcursion) {
            inExcursion = true
            extreme = v
        } else {
            extreme = if (lowerIsActive) min(extreme, v) else max(extreme, v)
        }
        return if (isActive(v)) Obs.ACTIVE else Obs.BETWEEN
    }

    /** 사람을 놓쳤을 때: 진행 중이던 왕복은 학습하지 않음 */
    fun interrupt() {
        inExcursion = false
    }

    private fun learn(value: Float) {
        if (!book.enabled || !beyondFloor(value)) return
        val prev = entry.learned
        entry.learned = if (prev == null) value else prev + ALPHA * (value - prev)
        entry.samples++
        book.dirty = true
    }

    companion object {
        const val REACH = 0.65f
        const val ALPHA = 0.25f
        const val MIN_SAMPLES = 3
    }
}

/** 운동별 측정값 기준 정의 */
object CalibrationSpecs {
    class Spec(
        val key: String,
        val rest: Float,
        val defaultActive: Float,
        val loosest: Float,
        val strictest: Float,
        val lowerIsActive: Boolean,
    ) {
        fun create(book: CalibrationBook) =
            AdaptiveThreshold(book, book.entry(key), rest, defaultActive, loosest, strictest, lowerIsActive)
    }

    // 스쿼트/런지: (무릎y - 엉덩이y) / 정강이 길이. 서 있으면 ≈1.0, 엉덩이가 무릎 높이면 ≈0
    val SQUAT_DEPTH = Spec("squat.depth", 0.75f, 0.45f, 0.55f, 0.35f, true)
    val LUNGE_DEPTH = Spec("lunge.depth", 0.75f, 0.45f, 0.55f, 0.35f, true)
    // 팔굽혀펴기: 팔꿈치 각도
    val PUSH_UP_ELBOW = Spec("pushup.elbow", 150f, 100f, 115f, 85f, true)
    // 윗몸일으키기: 어깨-엉덩이-무릎 각도
    val SIT_UP_HIP = Spec("situp.hip", 115f, 75f, 90f, 60f, true)
    // 점핑잭: 팔 들어올린 각도(아래=0, 위=180), 발 간격 / 어깨 폭
    val JJ_ARM = Spec("jumpingjack.arm", 60f, 120f, 105f, 150f, false)
    val JJ_LEG = Spec("jumpingjack.leg", 1.1f, 1.4f, 1.25f, 1.8f, false)
    // 만세
    val ARM_RAISE = Spec("armraise.arm", 60f, 140f, 125f, 165f, false)
    // 무릎 올리기: (무릎y - 엉덩이y) / 몸통 길이
    val HIGH_KNEE = Spec("highknees.ratio", 0.6f, 0.35f, 0.45f, 0.2f, true)
}

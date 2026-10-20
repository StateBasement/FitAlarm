package com.fitalarm.app.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/** ML Kit PoseLandmark 와 같은 번호 체계 (BlazePose 33점). 필요한 것만 정의. */
object Lm {
    const val NOSE = 0
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val COUNT = 33
}

/** 이미지 좌표계 점 (x 오른쪽, y 아래쪽 증가). likelihood = 화면 안에 있을 확률 0..1 */
data class Pt(val x: Float, val y: Float, val likelihood: Float = 1f)

/** 한 프레임의 관절 좌표. Android/ML Kit 과 분리해 단위 테스트할 수 있게 함. */
class PoseFrame(private val points: Array<Pt?>) {
    init {
        require(points.size == Lm.COUNT) { "expected ${Lm.COUNT} landmarks, got ${points.size}" }
    }

    fun raw(index: Int): Pt? = points.getOrNull(index)

    /** likelihood 가 기준 이상일 때만 반환 */
    fun visible(index: Int, minLikelihood: Float = VISIBILITY): Pt? =
        points.getOrNull(index)?.takeIf { it.likelihood >= minLikelihood }

    companion object {
        const val VISIBILITY = 0.5f

        fun of(map: Map<Int, Pt>): PoseFrame {
            val arr = arrayOfNulls<Pt>(Lm.COUNT)
            for ((k, v) in map) if (k in 0 until Lm.COUNT) arr[k] = v
            return PoseFrame(arr)
        }
    }
}

/** 몸의 한쪽 관절 번호 묶음 */
enum class Side(
    val shoulder: Int, val elbow: Int, val wrist: Int,
    val hip: Int, val knee: Int, val ankle: Int,
) {
    LEFT(Lm.LEFT_SHOULDER, Lm.LEFT_ELBOW, Lm.LEFT_WRIST, Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE),
    RIGHT(Lm.RIGHT_SHOULDER, Lm.RIGHT_ELBOW, Lm.RIGHT_WRIST, Lm.RIGHT_HIP, Lm.RIGHT_KNEE, Lm.RIGHT_ANKLE),
}

object Geo {
    private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

    /** b 를 꼭짓점으로 하는 각 a-b-c (0..180도). 두 벡터 중 하나가 0 길이면 NaN */
    fun angleDeg(a: Pt, b: Pt, c: Pt): Float {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val n = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
        if (n < 1e-6f) return Float.NaN
        val cos = ((v1x * v2x + v1y * v2y) / n).coerceIn(-1f, 1f)
        return acos(cos) * RAD_TO_DEG
    }

    fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    fun mid(a: Pt, b: Pt): Pt = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f, min(a.likelihood, b.likelihood))

    /** from→to 벡터와 "아래 방향"(0,+1) 사이의 각 (0 = 아래로 늘어뜨림, 180 = 위로 뻗음) */
    fun angleFromDown(from: Pt, to: Pt): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return Float.NaN
        // atan2(|dx|, dy): dy>0(아래) → 0, dy<0(위) → 180
        return atan2(abs(dx), dy) * RAD_TO_DEG
    }

    /** a→b 선분이 수평과 이루는 각 (0 = 수평, 90 = 수직) */
    fun angleFromHorizontal(a: Pt, b: Pt): Float {
        val dx = abs(b.x - a.x)
        val dy = abs(b.y - a.y)
        if (dx < 1e-6f && dy < 1e-6f) return Float.NaN
        return atan2(dy, dx) * RAD_TO_DEG
    }
}

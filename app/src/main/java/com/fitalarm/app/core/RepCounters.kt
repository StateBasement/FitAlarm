package com.fitalarm.app.core

/**
 * 운동 횟수 인식.
 *
 * 구조 (펌웨어의 디바운스 + 히스테리시스 상태머신과 같은 개념):
 *   PoseFrame ──observe()──▶ Obs(REST/ACTIVE/BETWEEN/…) ──PhaseCounter──▶ count
 *
 *  - observe(): 관절 좌표로 측정값(각도, 비율)을 계산하고 EMA 로 평활화한 뒤
 *               두 개의 서로 떨어진 임계값으로 REST / ACTIVE / BETWEEN 판정.
 *               두 임계값 사이(BETWEEN)에서는 상태를 바꾸지 않으므로 떨림에 강함.
 *  - PhaseCounter: WAITING_START → REST → ACTIVE → REST … 전이를 관리하고,
 *               1회가 완성되는 전이에서만 카운트. 최소 간격(minIntervalMs)으로 중복 방지.
 *  - 반드시 시작 자세(REST)를 먼저 확인한 뒤부터 센다 (화면에 들어오다 잘못 세는 것 방지).
 */

/** 한 프레임에 대한 판정 결과 */
enum class Obs { MISSING, BAD_POSTURE, REST, ACTIVE, BETWEEN }

enum class Phase { WAITING_START, REST, ACTIVE }

class PhaseCounter(
    private val countOnActive: Boolean,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    var phase: Phase = Phase.WAITING_START
        private set
    var count: Int = 0
        private set
    private var lastCountAt: Long? = null

    /** @return 이번 관측으로 1회가 집계되었으면 true */
    fun step(obs: Obs, tMs: Long): Boolean {
        var counted = false
        when (obs) {
            Obs.REST -> {
                if (phase == Phase.ACTIVE && !countOnActive) counted = tryCount(tMs)
                phase = Phase.REST
            }
            Obs.ACTIVE -> {
                if (phase == Phase.REST) {
                    phase = Phase.ACTIVE
                    if (countOnActive) counted = tryCount(tMs)
                }
                // WAITING_START 에서 바로 ACTIVE 는 무시: 시작 자세부터 다시
            }
            Obs.MISSING, Obs.BAD_POSTURE, Obs.BETWEEN -> Unit
        }
        return counted
    }

    private fun tryCount(tMs: Long): Boolean {
        val last = lastCountAt
        if (last != null && tMs - last < minIntervalMs) return false
        lastCountAt = tMs
        count++
        return true
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 350L
    }
}

/** 지수이동평균. 값이 오래 끊겼다가 다시 들어오면(resetAfterMs) 새 값으로 바로 초기화. */
class Ema(private val alpha: Float = 0.5f, private val resetAfterMs: Long = 700L) {
    private var value: Float? = null
    private var lastT: Long = 0L

    fun update(x: Float, tMs: Long): Float {
        val prev = value
        val next = if (prev == null || tMs - lastT > resetAfterMs) x else alpha * x + (1f - alpha) * prev
        value = next
        lastT = tMs
        return next
    }
}

data class CountUpdate(
    val count: Int,
    val counted: Boolean,
    val hint: String,
    val tracking: Boolean,
)

data class Cues(
    val notVisible: String,
    val badPosture: String,
    val getReady: String,
    val goActive: String,
    val goRest: String,
)

abstract class RepCounter(val type: ExerciseType) {
    abstract val count: Int
    abstract fun update(frame: PoseFrame?, tMs: Long): CountUpdate

    companion object {
        const val NO_PERSON = "사람이 보이지 않아요. 카메라 앞에 서 주세요"

        fun create(type: ExerciseType): RepCounter = when (type) {
            ExerciseType.SQUAT -> PhaseRepCounter(type, Thresholds.SQUAT_CUES, false, LegBendObserver(useMin = false))
            ExerciseType.LUNGE -> PhaseRepCounter(type, Thresholds.LUNGE_CUES, false, LegBendObserver(useMin = true))
            ExerciseType.PUSH_UP -> PhaseRepCounter(type, Thresholds.PUSH_UP_CUES, false, PushUpObserver())
            ExerciseType.SIT_UP -> PhaseRepCounter(type, Thresholds.SIT_UP_CUES, true, SitUpObserver())
            ExerciseType.JUMPING_JACK -> PhaseRepCounter(type, Thresholds.JUMPING_JACK_CUES, false, JumpingJackObserver())
            ExerciseType.ARM_RAISE -> PhaseRepCounter(type, Thresholds.ARM_RAISE_CUES, false, ArmRaiseObserver())
            ExerciseType.HIGH_KNEES -> HighKneesCounter()
        }
    }
}

/**
 * 판정 임계값 모음. 실제 기기에서 너무 빡빡하거나 느슨하면 여기 숫자만 조정하면 됩니다.
 * 각도는 도(°), 비율은 몸 길이 대비 무차원 값.
 */
object Thresholds {
    // 스쿼트/런지: depth = (무릎y - 엉덩이y) / 정강이 길이. 서 있으면 ≈1.0, 엉덩이가 무릎 높이면 ≈0
    const val LEG_REST_DEPTH = 0.75f
    const val LEG_ACTIVE_DEPTH = 0.45f
    const val LEG_REST_KNEE_ANGLE = 145f
    const val SQUAT_ACTIVE_KNEE_ANGLE = 100f
    const val LUNGE_ACTIVE_KNEE_ANGLE = 110f

    // 팔굽혀펴기: 팔꿈치 각도, 몸통이 수평에 가까워야 함
    const val PUSH_UP_REST_ELBOW = 150f
    const val PUSH_UP_ACTIVE_ELBOW = 100f
    const val PUSH_UP_MAX_TORSO_FROM_HORIZONTAL = 45f

    // 윗몸일으키기: 어깨-엉덩이-무릎 각도
    const val SIT_UP_REST_HIP = 115f
    const val SIT_UP_ACTIVE_HIP = 75f
    const val SIT_UP_MAX_LYING_TORSO = 40f

    // 점핑잭: 팔 들어올린 각도(아래=0, 위=180), 발 간격 / 어깨 폭
    const val JJ_REST_ARM = 60f
    const val JJ_ACTIVE_ARM = 120f
    const val JJ_REST_LEG_RATIO = 1.1f
    const val JJ_ACTIVE_LEG_RATIO = 1.4f

    // 만세
    const val ARM_REST = 60f
    const val ARM_ACTIVE = 140f

    // 무릎 올리기: (무릎y - 엉덩이y) / 몸통 길이
    const val KNEE_REST_RATIO = 0.6f
    const val KNEE_ACTIVE_RATIO = 0.35f
    const val KNEE_MIN_INTERVAL_MS = 250L

    val SQUAT_CUES = Cues(
        notVisible = "엉덩이·무릎·발목이 모두 보이도록 조금 뒤로 물러나세요",
        badPosture = "전신이 보이게 서 주세요",
        getReady = "똑바로 서서 시작하세요",
        goActive = "엉덩이를 무릎 높이까지 내리세요",
        goRest = "좋아요! 다시 일어서세요",
    )
    val LUNGE_CUES = Cues(
        notVisible = "엉덩이·무릎·발목이 모두 보이도록 조금 뒤로 물러나세요",
        badPosture = "전신이 보이게 서 주세요",
        getReady = "똑바로 서서 시작하세요",
        goActive = "한 발을 내딛고 무릎을 굽히세요",
        goRest = "좋아요! 다시 일어서세요",
    )
    val PUSH_UP_CUES = Cues(
        notVisible = "어깨·팔꿈치·손목·엉덩이가 보이도록 옆에서 비춰주세요",
        badPosture = "엎드린 자세를 옆에서 비춰주세요",
        getReady = "팔을 펴고 엎드린 자세로 시작하세요",
        goActive = "가슴을 바닥 쪽으로 내리세요",
        goRest = "좋아요! 팔을 끝까지 펴세요",
    )
    val SIT_UP_CUES = Cues(
        notVisible = "어깨·엉덩이·무릎이 보이도록 옆에서 비춰주세요",
        badPosture = "무릎을 세우고 누운 자세를 옆에서 비춰주세요",
        getReady = "무릎을 세우고 누우세요",
        goActive = "상체를 무릎 쪽으로 일으키세요",
        goRest = "좋아요! 다시 누우세요",
    )
    val JUMPING_JACK_CUES = Cues(
        notVisible = "손끝부터 발끝까지 전신이 보이도록 뒤로 물러나세요",
        badPosture = "화면을 정면으로 바라보고 서세요",
        getReady = "팔을 내리고 발을 모은 채 서세요",
        goActive = "팔은 머리 위로, 다리는 넓게!",
        goRest = "좋아요! 팔을 내리고 발을 모으세요",
    )
    val ARM_RAISE_CUES = Cues(
        notVisible = "어깨와 손목이 화면에 보이게 해주세요",
        badPosture = "어깨와 손목이 화면에 보이게 해주세요",
        getReady = "팔을 내리고 시작하세요",
        goActive = "두 팔을 머리 위로 쭉 뻗으세요",
        goRest = "좋아요! 팔을 내리세요",
    )
    val HIGH_KNEES_CUES = Cues(
        notVisible = "엉덩이와 무릎이 보이도록 조금 뒤로 물러나세요",
        badPosture = "전신이 보이게 서 주세요",
        getReady = "똑바로 서서 시작하세요",
        goActive = "무릎을 골반 높이까지 번갈아 올리세요",
        goRest = "무릎을 골반 높이까지 번갈아 올리세요",
    )
}

/** 프레임 → Obs 판정기 (운동마다 하나) */
fun interface Observer {
    fun observe(frame: PoseFrame, tMs: Long): Obs
}

/** 판정기 + PhaseCounter 를 묶은 일반적인 1-상태머신 카운터 */
class PhaseRepCounter(
    type: ExerciseType,
    private val cues: Cues,
    countOnActive: Boolean,
    private val observer: Observer,
) : RepCounter(type) {
    private val counter = PhaseCounter(countOnActive)

    override val count: Int get() = counter.count

    override fun update(frame: PoseFrame?, tMs: Long): CountUpdate {
        val obs = if (frame == null) Obs.MISSING else observer.observe(frame, tMs)
        val counted = counter.step(obs, tMs)
        val hint = when (obs) {
            Obs.MISSING -> if (frame == null) NO_PERSON else cues.notVisible
            Obs.BAD_POSTURE -> cues.badPosture
            else -> when (counter.phase) {
                Phase.WAITING_START -> cues.getReady
                Phase.REST -> cues.goActive
                Phase.ACTIVE -> cues.goRest
            }
        }
        return CountUpdate(counter.count, counted, hint, obs != Obs.MISSING)
    }
}

/** 지정한 관절이 모두 보이는 쪽 중 신뢰도 합이 가장 큰 쪽 */
internal fun bestSide(frame: PoseFrame, pick: (Side) -> IntArray): Side? =
    Side.entries
        .mapNotNull { side ->
            val idx = pick(side)
            val pts = idx.map { frame.visible(it) }
            if (pts.any { it == null }) null else side to pts.sumOf { it!!.likelihood.toDouble() }
        }
        .maxByOrNull { it.second }
        ?.first

private fun classify(value: Float, restWhen: (Float) -> Boolean, activeWhen: (Float) -> Boolean): Obs = when {
    activeWhen(value) -> Obs.ACTIVE
    restWhen(value) -> Obs.REST
    else -> Obs.BETWEEN
}

/**
 * 스쿼트(양쪽 평균) / 런지(굽힌 쪽 = 최소값).
 * 정면에서는 무릎 각도가 원근 때문에 덜 줄어드므로 "엉덩이 하강 비율"을 함께 사용.
 */
internal class LegBendObserver(private val useMin: Boolean) : Observer {
    private val depthEma = Ema()
    private val angleEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val depths = ArrayList<Float>(2)
        val angles = ArrayList<Float>(2)
        for (side in Side.entries) {
            val hip = frame.visible(side.hip) ?: continue
            val knee = frame.visible(side.knee) ?: continue
            val ankle = frame.visible(side.ankle) ?: continue
            val shin = Geo.dist(knee, ankle)
            if (shin < 1e-3f) continue
            depths.add((knee.y - hip.y) / shin)
            val a = Geo.angleDeg(hip, knee, ankle)
            if (!a.isNaN()) angles.add(a)
        }
        if (depths.isEmpty()) return Obs.MISSING

        val rawDepth = if (useMin) depths.min() else depths.average().toFloat()
        val depth = depthEma.update(rawDepth, tMs)
        val angle = if (angles.isEmpty()) null else
            angleEma.update(if (useMin) angles.min() else angles.average().toFloat(), tMs)

        val activeAngle = if (useMin) Thresholds.LUNGE_ACTIVE_KNEE_ANGLE else Thresholds.SQUAT_ACTIVE_KNEE_ANGLE
        val active = depth <= Thresholds.LEG_ACTIVE_DEPTH || (angle != null && angle <= activeAngle)
        val rest = depth >= Thresholds.LEG_REST_DEPTH && (angle == null || angle >= Thresholds.LEG_REST_KNEE_ANGLE)
        return when {
            active -> Obs.ACTIVE
            rest -> Obs.REST
            else -> Obs.BETWEEN
        }
    }
}

internal class PushUpObserver : Observer {
    private val elbowEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val side = bestSide(frame) { intArrayOf(it.shoulder, it.elbow, it.wrist, it.hip) } ?: return Obs.MISSING
        val sh = frame.visible(side.shoulder)!!
        val el = frame.visible(side.elbow)!!
        val wr = frame.visible(side.wrist)!!
        val hp = frame.visible(side.hip)!!

        val torso = Geo.angleFromHorizontal(sh, hp)
        if (torso.isNaN() || torso > Thresholds.PUSH_UP_MAX_TORSO_FROM_HORIZONTAL) return Obs.BAD_POSTURE

        val raw = Geo.angleDeg(sh, el, wr)
        if (raw.isNaN()) return Obs.MISSING
        val elbow = elbowEma.update(raw, tMs)
        return classify(elbow, { it >= Thresholds.PUSH_UP_REST_ELBOW }, { it <= Thresholds.PUSH_UP_ACTIVE_ELBOW })
    }
}

internal class SitUpObserver : Observer {
    private val hipEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val side = bestSide(frame) { intArrayOf(it.shoulder, it.hip, it.knee) } ?: return Obs.MISSING
        val sh = frame.visible(side.shoulder)!!
        val hp = frame.visible(side.hip)!!
        val kn = frame.visible(side.knee)!!

        val raw = Geo.angleDeg(sh, hp, kn)
        if (raw.isNaN()) return Obs.MISSING
        val hipAngle = hipEma.update(raw, tMs)

        if (hipAngle <= Thresholds.SIT_UP_ACTIVE_HIP) return Obs.ACTIVE
        if (hipAngle >= Thresholds.SIT_UP_REST_HIP) {
            // 누워 있어야 시작 자세로 인정 (서서 허리 숙이기로 대체 방지)
            val torso = Geo.angleFromHorizontal(hp, sh)
            return if (!torso.isNaN() && torso <= Thresholds.SIT_UP_MAX_LYING_TORSO) Obs.REST else Obs.BAD_POSTURE
        }
        return Obs.BETWEEN
    }
}

internal class JumpingJackObserver : Observer {
    private val armEma = Ema()
    private val legEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val ls = frame.visible(Lm.LEFT_SHOULDER) ?: return Obs.MISSING
        val rs = frame.visible(Lm.RIGHT_SHOULDER) ?: return Obs.MISSING
        val lw = frame.visible(Lm.LEFT_WRIST) ?: return Obs.MISSING
        val rw = frame.visible(Lm.RIGHT_WRIST) ?: return Obs.MISSING
        val la = frame.visible(Lm.LEFT_ANKLE) ?: return Obs.MISSING
        val ra = frame.visible(Lm.RIGHT_ANKLE) ?: return Obs.MISSING

        val shoulderWidth = kotlin.math.abs(ls.x - rs.x)
        val bodyHeight = Geo.dist(Geo.mid(ls, rs), Geo.mid(la, ra))
        if (bodyHeight < 1e-3f || shoulderWidth < 0.1f * bodyHeight) return Obs.BAD_POSTURE

        val armL = Geo.angleFromDown(ls, lw)
        val armR = Geo.angleFromDown(rs, rw)
        if (armL.isNaN() || armR.isNaN()) return Obs.MISSING
        val arm = armEma.update((armL + armR) / 2f, tMs)
        val leg = legEma.update(kotlin.math.abs(la.x - ra.x) / shoulderWidth, tMs)

        return when {
            arm >= Thresholds.JJ_ACTIVE_ARM && leg >= Thresholds.JJ_ACTIVE_LEG_RATIO -> Obs.ACTIVE
            arm <= Thresholds.JJ_REST_ARM && leg <= Thresholds.JJ_REST_LEG_RATIO -> Obs.REST
            else -> Obs.BETWEEN
        }
    }
}

internal class ArmRaiseObserver : Observer {
    private val armEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val arms = Side.entries.mapNotNull { side ->
            val sh = frame.visible(side.shoulder) ?: return@mapNotNull null
            val wr = frame.visible(side.wrist) ?: return@mapNotNull null
            Geo.angleFromDown(sh, wr).takeIf { !it.isNaN() }
        }
        if (arms.isEmpty()) return Obs.MISSING
        val arm = armEma.update(arms.average().toFloat(), tMs)
        return classify(arm, { it <= Thresholds.ARM_REST }, { it >= Thresholds.ARM_ACTIVE })
    }
}

/** 제자리 무릎 올리기: 좌우 다리를 각각 독립된 상태머신으로 세고 합산 */
internal class HighKneesCounter : RepCounter(ExerciseType.HIGH_KNEES) {
    private val cues = Thresholds.HIGH_KNEES_CUES
    private val counters = Side.entries.associateWith { PhaseCounter(false, Thresholds.KNEE_MIN_INTERVAL_MS) }
    private val emas = Side.entries.associateWith { Ema() }

    override val count: Int get() = counters.values.sumOf { it.count }

    override fun update(frame: PoseFrame?, tMs: Long): CountUpdate {
        if (frame == null) return CountUpdate(count, false, RepCounter.NO_PERSON, false)
        val torso = torsoLength(frame) ?: return CountUpdate(count, false, cues.notVisible, false)

        var counted = false
        var anySide = false
        for (side in Side.entries) {
            val hip = frame.visible(side.hip) ?: continue
            val knee = frame.visible(side.knee) ?: continue
            anySide = true
            val ratio = emas.getValue(side).update((knee.y - hip.y) / torso, tMs)
            val obs = when {
                ratio <= Thresholds.KNEE_ACTIVE_RATIO -> Obs.ACTIVE
                ratio >= Thresholds.KNEE_REST_RATIO -> Obs.REST
                else -> Obs.BETWEEN
            }
            if (counters.getValue(side).step(obs, tMs)) counted = true
        }
        if (!anySide) return CountUpdate(count, false, cues.notVisible, false)

        val started = counters.values.any { it.phase != Phase.WAITING_START }
        return CountUpdate(count, counted, if (started) cues.goActive else cues.getReady, true)
    }

    private fun torsoLength(frame: PoseFrame): Float? {
        val ls = frame.visible(Lm.LEFT_SHOULDER)
        val rs = frame.visible(Lm.RIGHT_SHOULDER)
        val lh = frame.visible(Lm.LEFT_HIP)
        val rh = frame.visible(Lm.RIGHT_HIP)
        val sh = when {
            ls != null && rs != null -> Geo.mid(ls, rs)
            else -> ls ?: rs
        } ?: return null
        val hp = when {
            lh != null && rh != null -> Geo.mid(lh, rh)
            else -> lh ?: rh
        } ?: return null
        val len = Geo.dist(sh, hp)
        return if (len < 1e-3f) null else len
    }
}

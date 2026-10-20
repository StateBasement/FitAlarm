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

        /** book: 개인 보정 값. 기본은 보정 끔(고정 기준) */
        fun create(type: ExerciseType, book: CalibrationBook = CalibrationBook(enabled = false)): RepCounter = when (type) {
            ExerciseType.SQUAT -> PhaseRepCounter(type, Thresholds.SQUAT_CUES, false,
                LegBendObserver(useMin = false, depth = CalibrationSpecs.SQUAT_DEPTH.create(book)))
            ExerciseType.LUNGE -> PhaseRepCounter(type, Thresholds.LUNGE_CUES, false,
                LegBendObserver(useMin = true, depth = CalibrationSpecs.LUNGE_DEPTH.create(book)))
            ExerciseType.PUSH_UP -> PhaseRepCounter(type, Thresholds.PUSH_UP_CUES, false,
                PushUpObserver(CalibrationSpecs.PUSH_UP_ELBOW.create(book)))
            ExerciseType.SIT_UP -> PhaseRepCounter(type, Thresholds.SIT_UP_CUES, true,
                SitUpObserver(CalibrationSpecs.SIT_UP_HIP.create(book)))
            ExerciseType.JUMPING_JACK -> PhaseRepCounter(type, Thresholds.JUMPING_JACK_CUES, false,
                JumpingJackObserver(CalibrationSpecs.JJ_ARM.create(book), CalibrationSpecs.JJ_LEG.create(book)))
            ExerciseType.ARM_RAISE -> PhaseRepCounter(type, Thresholds.ARM_RAISE_CUES, false,
                ArmRaiseObserver(CalibrationSpecs.ARM_RAISE.create(book)))
            ExerciseType.HIGH_KNEES -> HighKneesCounter(book)
        }
    }
}

/**
 * 판정 임계값 모음. 실제 기기에서 너무 빡빡하거나 느슨하면 여기 숫자만 조정하면 됩니다.
 * 각도는 도(°), 비율은 몸 길이 대비 무차원 값.
 */
object Thresholds {
    // 보정하지 않는 보조 기준 (보정 대상 값은 CalibrationSpecs 참고)
    const val LEG_REST_KNEE_ANGLE = 145f
    const val SQUAT_ACTIVE_KNEE_ANGLE = 100f
    const val LUNGE_ACTIVE_KNEE_ANGLE = 110f
    const val PUSH_UP_MAX_TORSO_FROM_HORIZONTAL = 45f
    const val SIT_UP_MAX_LYING_TORSO = 40f
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

/**
 * 스쿼트(양쪽 평균) / 런지(굽힌 쪽 = 최소값).
 * 정면에서는 무릎 각도가 원근 때문에 덜 줄어드므로 "엉덩이 하강 비율"(보정 대상)을 주로 쓰고
 * 무릎 각도를 보조 기준으로 함께 사용.
 */
internal class LegBendObserver(private val useMin: Boolean, private val depth: AdaptiveThreshold) : Observer {
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
        if (depths.isEmpty()) {
            depth.interrupt()
            return Obs.MISSING
        }

        val d = depthEma.update(if (useMin) depths.min() else depths.average().toFloat(), tMs)
        val angle = if (angles.isEmpty()) null else
            angleEma.update(if (useMin) angles.min() else angles.average().toFloat(), tMs)

        val depthObs = depth.classify(d)
        val activeAngle = if (useMin) Thresholds.LUNGE_ACTIVE_KNEE_ANGLE else Thresholds.SQUAT_ACTIVE_KNEE_ANGLE
        val active = depthObs == Obs.ACTIVE || (angle != null && angle <= activeAngle)
        val rest = depthObs == Obs.REST && (angle == null || angle >= Thresholds.LEG_REST_KNEE_ANGLE)
        return when {
            active -> Obs.ACTIVE
            rest -> Obs.REST
            else -> Obs.BETWEEN
        }
    }
}

internal class PushUpObserver(private val elbow: AdaptiveThreshold) : Observer {
    private val elbowEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val side = bestSide(frame) { intArrayOf(it.shoulder, it.elbow, it.wrist, it.hip) } ?: run {
            elbow.interrupt()
            return Obs.MISSING
        }
        val sh = frame.visible(side.shoulder)!!
        val el = frame.visible(side.elbow)!!
        val wr = frame.visible(side.wrist)!!
        val hp = frame.visible(side.hip)!!

        val torso = Geo.angleFromHorizontal(sh, hp)
        if (torso.isNaN() || torso > Thresholds.PUSH_UP_MAX_TORSO_FROM_HORIZONTAL) {
            elbow.interrupt()
            return Obs.BAD_POSTURE
        }
        val raw = Geo.angleDeg(sh, el, wr)
        if (raw.isNaN()) return Obs.MISSING
        return elbow.classify(elbowEma.update(raw, tMs))
    }
}

internal class SitUpObserver(private val hip: AdaptiveThreshold) : Observer {
    private val hipEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val side = bestSide(frame) { intArrayOf(it.shoulder, it.hip, it.knee) } ?: run {
            hip.interrupt()
            return Obs.MISSING
        }
        val sh = frame.visible(side.shoulder)!!
        val hp = frame.visible(side.hip)!!
        val kn = frame.visible(side.knee)!!

        val raw = Geo.angleDeg(sh, hp, kn)
        if (raw.isNaN()) return Obs.MISSING
        val obs = hip.classify(hipEma.update(raw, tMs))
        if (obs == Obs.REST) {
            // 누워 있어야 시작 자세로 인정 (서서 허리 숙이기로 대체 방지)
            val torso = Geo.angleFromHorizontal(hp, sh)
            if (torso.isNaN() || torso > Thresholds.SIT_UP_MAX_LYING_TORSO) return Obs.BAD_POSTURE
        }
        return obs
    }
}

internal class JumpingJackObserver(
    private val arm: AdaptiveThreshold,
    private val leg: AdaptiveThreshold,
) : Observer {
    private val armEma = Ema()
    private val legEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val ls = frame.visible(Lm.LEFT_SHOULDER)
        val rs = frame.visible(Lm.RIGHT_SHOULDER)
        val lw = frame.visible(Lm.LEFT_WRIST)
        val rw = frame.visible(Lm.RIGHT_WRIST)
        val la = frame.visible(Lm.LEFT_ANKLE)
        val ra = frame.visible(Lm.RIGHT_ANKLE)
        if (ls == null || rs == null || lw == null || rw == null || la == null || ra == null) {
            arm.interrupt(); leg.interrupt()
            return Obs.MISSING
        }

        val shoulderWidth = kotlin.math.abs(ls.x - rs.x)
        val bodyHeight = Geo.dist(Geo.mid(ls, rs), Geo.mid(la, ra))
        if (bodyHeight < 1e-3f || shoulderWidth < 0.1f * bodyHeight) {
            arm.interrupt(); leg.interrupt()
            return Obs.BAD_POSTURE
        }

        val armL = Geo.angleFromDown(ls, lw)
        val armR = Geo.angleFromDown(rs, rw)
        if (armL.isNaN() || armR.isNaN()) return Obs.MISSING
        val a = arm.classify(armEma.update((armL + armR) / 2f, tMs))
        val l = leg.classify(legEma.update(kotlin.math.abs(la.x - ra.x) / shoulderWidth, tMs))
        return when {
            a == Obs.ACTIVE && l == Obs.ACTIVE -> Obs.ACTIVE
            a == Obs.REST && l == Obs.REST -> Obs.REST
            else -> Obs.BETWEEN
        }
    }
}

internal class ArmRaiseObserver(private val arm: AdaptiveThreshold) : Observer {
    private val armEma = Ema()

    override fun observe(frame: PoseFrame, tMs: Long): Obs {
        val arms = Side.entries.mapNotNull { side ->
            val sh = frame.visible(side.shoulder) ?: return@mapNotNull null
            val wr = frame.visible(side.wrist) ?: return@mapNotNull null
            Geo.angleFromDown(sh, wr).takeIf { !it.isNaN() }
        }
        if (arms.isEmpty()) {
            arm.interrupt()
            return Obs.MISSING
        }
        return arm.classify(armEma.update(arms.average().toFloat(), tMs))
    }
}

/** 제자리 무릎 올리기: 좌우 다리를 각각 독립된 상태머신으로 세고 합산 (보정 값은 공유) */
internal class HighKneesCounter(book: CalibrationBook) : RepCounter(ExerciseType.HIGH_KNEES) {
    private val cues = Thresholds.HIGH_KNEES_CUES
    private val counters = Side.entries.associateWith { PhaseCounter(false, Thresholds.KNEE_MIN_INTERVAL_MS) }
    private val emas = Side.entries.associateWith { Ema() }
    private val thresholds = Side.entries.associateWith { CalibrationSpecs.HIGH_KNEE.create(book) }

    override val count: Int get() = counters.values.sumOf { it.count }

    override fun update(frame: PoseFrame?, tMs: Long): CountUpdate {
        if (frame == null) {
            thresholds.values.forEach { it.interrupt() }
            return CountUpdate(count, false, RepCounter.NO_PERSON, false)
        }
        val torso = torsoLength(frame) ?: run {
            thresholds.values.forEach { it.interrupt() }
            return CountUpdate(count, false, cues.notVisible, false)
        }

        var counted = false
        var anySide = false
        for (side in Side.entries) {
            val hip = frame.visible(side.hip)
            val knee = frame.visible(side.knee)
            if (hip == null || knee == null) {
                thresholds.getValue(side).interrupt()
                continue
            }
            anySide = true
            val ratio = emas.getValue(side).update((knee.y - hip.y) / torso, tMs)
            val obs = thresholds.getValue(side).classify(ratio)
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

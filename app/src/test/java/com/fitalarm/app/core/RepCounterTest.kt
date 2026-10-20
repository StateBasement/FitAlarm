package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 합성한 관절 좌표로 상태머신이 정확히 1회씩 세는지 확인.
 * 좌표는 0..1 정규화 이미지 좌표 (y 가 아래로 증가).
 */
class RepCounterTest {

    private var t = 0L
    private fun next(dt: Long = 50L): Long { t += dt; return t }

    private fun feed(counter: RepCounter, frame: PoseFrame?, frames: Int): CountUpdate {
        var last: CountUpdate? = null
        repeat(frames) { last = counter.update(frame, next()) }
        return last!!
    }

    // ---------- 프레임 생성 도우미 ----------

    private fun both(left: Int, right: Int, p: Pt) = mapOf(left to p, right to p)

    private fun legs(hip: Pt, knee: Pt, ankle: Pt, extra: Map<Int, Pt> = emptyMap()): PoseFrame =
        PoseFrame.of(
            both(Lm.LEFT_HIP, Lm.RIGHT_HIP, hip) +
                both(Lm.LEFT_KNEE, Lm.RIGHT_KNEE, knee) +
                both(Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE, ankle) + extra
        )

    private val standing = legs(Pt(0.5f, 0.5f), Pt(0.5f, 0.75f), Pt(0.5f, 1.0f))
    private val deepSquat = legs(Pt(0.35f, 0.72f), Pt(0.55f, 0.75f), Pt(0.5f, 1.0f))
    private val halfSquat = legs(Pt(0.42f, 0.62f), Pt(0.53f, 0.75f), Pt(0.5f, 1.0f))

    // ---------- 스쿼트 ----------

    @Test
    fun squatCountsFullReps() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        repeat(3) {
            feed(c, standing, 4)
            feed(c, deepSquat, 4)
        }
        feed(c, standing, 4)
        assertEquals(3, c.count)
    }

    @Test
    fun squatMustStartFromStanding() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        val u = feed(c, deepSquat, 6)          // 앉은 채로 화면에 들어옴
        assertEquals(0, c.count)
        assertEquals(Thresholds.SQUAT_CUES.getReady, u.hint)
        feed(c, standing, 4)                   // 일어서도 아직 0회 (시작 자세 확인만)
        assertEquals(0, c.count)
        feed(c, deepSquat, 4)
        feed(c, standing, 4)
        assertEquals(1, c.count)
    }

    @Test
    fun shallowSquatDoesNotCount() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        feed(c, standing, 4)
        repeat(5) {
            feed(c, halfSquat, 4)
            feed(c, standing, 4)
        }
        assertEquals(0, c.count)
    }

    @Test
    fun jitterNearBottomDoesNotDoubleCount() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        feed(c, standing, 4)
        feed(c, deepSquat, 4)
        repeat(10) {                            // 바닥에서 흔들림
            feed(c, halfSquat, 1)
            feed(c, deepSquat, 1)
        }
        feed(c, standing, 4)
        assertEquals(1, c.count)
    }

    @Test
    fun briefDropoutsAreTolerated() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        feed(c, standing, 4)
        feed(c, deepSquat, 4)
        val u = feed(c, null, 3)               // 잠깐 인식 실패
        assertFalse(u.tracking)
        assertEquals(RepCounter.NO_PERSON, u.hint)
        feed(c, deepSquat, 2)
        feed(c, standing, 4)
        assertEquals(1, c.count)
    }

    @Test
    fun hiddenLegsGiveVisibilityHint() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        val upperOnly = PoseFrame.of(both(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Pt(0.5f, 0.3f)))
        val u = feed(c, upperOnly, 2)
        assertEquals(Thresholds.SQUAT_CUES.notVisible, u.hint)
        assertFalse(u.tracking)
    }

    @Test
    fun lowConfidenceLandmarksAreIgnored() {
        val c = RepCounter.create(ExerciseType.SQUAT)
        val blurry = legs(Pt(0.5f, 0.5f, 0.2f), Pt(0.5f, 0.75f, 0.2f), Pt(0.5f, 1.0f, 0.2f))
        val u = feed(c, blurry, 3)
        assertFalse(u.tracking)
    }

    // ---------- 런지 ----------

    @Test
    fun lungeCountsWhenOneLegBends() {
        val c = RepCounter.create(ExerciseType.LUNGE)
        val lunge = PoseFrame.of(
            mapOf(
                Lm.LEFT_HIP to Pt(0.5f, 0.7f), Lm.LEFT_KNEE to Pt(0.7f, 0.72f), Lm.LEFT_ANKLE to Pt(0.7f, 1.0f),
                Lm.RIGHT_HIP to Pt(0.5f, 0.7f), Lm.RIGHT_KNEE to Pt(0.42f, 0.95f), Lm.RIGHT_ANKLE to Pt(0.25f, 0.97f),
            )
        )
        repeat(2) {
            feed(c, standing, 4)
            feed(c, lunge, 4)
        }
        feed(c, standing, 4)
        assertEquals(2, c.count)
    }

    // ---------- 팔굽혀펴기 ----------

    private fun pushUp(shoulder: Pt, elbow: Pt, wrist: Pt, hip: Pt) = PoseFrame.of(
        both(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, shoulder) +
            both(Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, elbow) +
            both(Lm.LEFT_WRIST, Lm.RIGHT_WRIST, wrist) +
            both(Lm.LEFT_HIP, Lm.RIGHT_HIP, hip)
    )

    private val plankUp = pushUp(Pt(0.3f, 0.5f), Pt(0.3f, 0.6f), Pt(0.3f, 0.7f), Pt(0.7f, 0.55f))
    private val plankDown = pushUp(Pt(0.3f, 0.62f), Pt(0.4f, 0.66f), Pt(0.3f, 0.7f), Pt(0.7f, 0.58f))

    @Test
    fun pushUpCounts() {
        val c = RepCounter.create(ExerciseType.PUSH_UP)
        repeat(4) {
            feed(c, plankUp, 4)
            feed(c, plankDown, 4)
        }
        feed(c, plankUp, 4)
        assertEquals(4, c.count)
    }

    @Test
    fun pushUpWhileStandingIsRejected() {
        val c = RepCounter.create(ExerciseType.PUSH_UP)
        val standUp = pushUp(Pt(0.5f, 0.3f), Pt(0.5f, 0.45f), Pt(0.5f, 0.6f), Pt(0.5f, 0.6f))
        val standBent = pushUp(Pt(0.5f, 0.3f), Pt(0.6f, 0.4f), Pt(0.5f, 0.45f), Pt(0.5f, 0.6f))
        var u: CountUpdate? = null
        repeat(3) {
            u = feed(c, standUp, 4)
            feed(c, standBent, 4)
        }
        assertEquals(0, c.count)
        assertEquals(Thresholds.PUSH_UP_CUES.badPosture, u!!.hint)
    }

    // ---------- 윗몸일으키기 ----------

    private fun sitUp(shoulder: Pt, hip: Pt, knee: Pt) = PoseFrame.of(
        both(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, shoulder) +
            both(Lm.LEFT_HIP, Lm.RIGHT_HIP, hip) +
            both(Lm.LEFT_KNEE, Lm.RIGHT_KNEE, knee)
    )

    @Test
    fun sitUpCountsOnTheWayUp() {
        val c = RepCounter.create(ExerciseType.SIT_UP)
        val lying = sitUp(Pt(0.2f, 0.8f), Pt(0.5f, 0.8f), Pt(0.65f, 0.65f))
        val up = sitUp(Pt(0.55f, 0.5f), Pt(0.5f, 0.8f), Pt(0.65f, 0.65f))
        feed(c, lying, 4)
        val u = feed(c, up, 4)
        assertEquals(1, c.count)                // 일어나는 순간 집계
        assertTrue(u.counted || c.count == 1)
        feed(c, lying, 4)
        feed(c, up, 4)
        assertEquals(2, c.count)
    }

    @Test
    fun sitUpRequiresLyingStart() {
        val c = RepCounter.create(ExerciseType.SIT_UP)
        val standingTall = sitUp(Pt(0.5f, 0.3f), Pt(0.5f, 0.6f), Pt(0.5f, 0.8f))
        val bowing = sitUp(Pt(0.7f, 0.65f), Pt(0.5f, 0.6f), Pt(0.5f, 0.8f))
        repeat(3) {
            feed(c, standingTall, 4)
            feed(c, bowing, 4)
        }
        assertEquals(0, c.count)
    }

    // ---------- 점핑잭 ----------

    private fun jack(armsUp: Boolean, legsApart: Boolean): PoseFrame {
        val wristY = if (armsUp) 0.1f else 0.55f
        val wristDx = if (armsUp) 0.1f else 0.02f
        val ankleDx = if (legsApart) 0.2f else 0.03f
        return PoseFrame.of(
            mapOf(
                Lm.LEFT_SHOULDER to Pt(0.4f, 0.3f), Lm.RIGHT_SHOULDER to Pt(0.6f, 0.3f),
                Lm.LEFT_WRIST to Pt(0.4f - wristDx, wristY), Lm.RIGHT_WRIST to Pt(0.6f + wristDx, wristY),
                Lm.LEFT_ANKLE to Pt(0.5f - ankleDx, 0.95f), Lm.RIGHT_ANKLE to Pt(0.5f + ankleDx, 0.95f),
            )
        )
    }

    @Test
    fun jumpingJackNeedsArmsAndLegs() {
        val c = RepCounter.create(ExerciseType.JUMPING_JACK)
        feed(c, jack(armsUp = false, legsApart = false), 4)
        feed(c, jack(armsUp = true, legsApart = false), 4)   // 팔만 올림 → 무효
        feed(c, jack(armsUp = false, legsApart = false), 4)
        assertEquals(0, c.count)
        repeat(3) {
            feed(c, jack(armsUp = true, legsApart = true), 4)
            feed(c, jack(armsUp = false, legsApart = false), 4)
        }
        assertEquals(3, c.count)
    }

    // ---------- 만세 ----------

    @Test
    fun armRaiseWorksWithUpperBodyOnly() {
        val c = RepCounter.create(ExerciseType.ARM_RAISE)
        fun arms(y: Float) = PoseFrame.of(
            mapOf(
                Lm.LEFT_SHOULDER to Pt(0.4f, 0.4f), Lm.RIGHT_SHOULDER to Pt(0.6f, 0.4f),
                Lm.LEFT_WRIST to Pt(0.4f, y), Lm.RIGHT_WRIST to Pt(0.6f, y),
            )
        )
        repeat(5) {
            feed(c, arms(0.7f), 4)
            feed(c, arms(0.1f), 4)
        }
        feed(c, arms(0.7f), 4)
        assertEquals(5, c.count)
    }

    // ---------- 무릎 올리기 ----------

    @Test
    fun highKneesCountsEachLeg() {
        val c = RepCounter.create(ExerciseType.HIGH_KNEES)
        fun pose(leftKneeY: Float, rightKneeY: Float) = PoseFrame.of(
            mapOf(
                Lm.LEFT_SHOULDER to Pt(0.45f, 0.3f), Lm.RIGHT_SHOULDER to Pt(0.55f, 0.3f),
                Lm.LEFT_HIP to Pt(0.46f, 0.6f), Lm.RIGHT_HIP to Pt(0.54f, 0.6f),
                Lm.LEFT_KNEE to Pt(0.46f, leftKneeY), Lm.RIGHT_KNEE to Pt(0.54f, rightKneeY),
            )
        )
        val down = 0.85f
        val up = 0.62f
        feed(c, pose(down, down), 4)
        repeat(3) {
            feed(c, pose(up, down), 4)
            feed(c, pose(down, down), 4)
            feed(c, pose(down, up), 4)
            feed(c, pose(down, down), 4)
        }
        assertEquals(6, c.count)
    }

    // ---------- 최소 간격 ----------

    @Test
    fun phaseCounterDebounce() {
        val pc = PhaseCounter(countOnActive = false, minIntervalMs = 350)
        pc.step(Obs.REST, 0)
        pc.step(Obs.ACTIVE, 100)
        assertTrue(pc.step(Obs.REST, 200))
        pc.step(Obs.ACTIVE, 300)
        assertFalse(pc.step(Obs.REST, 400))     // 200ms 만에 또 → 무시
        pc.step(Obs.ACTIVE, 700)
        assertTrue(pc.step(Obs.REST, 800))
        assertEquals(2, pc.count)
    }
}

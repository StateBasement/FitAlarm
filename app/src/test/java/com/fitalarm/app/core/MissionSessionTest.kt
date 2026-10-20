package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionSessionTest {

    private fun arms(y: Float) = PoseFrame.of(
        mapOf(
            Lm.LEFT_SHOULDER to Pt(0.4f, 0.4f), Lm.RIGHT_SHOULDER to Pt(0.6f, 0.4f),
            Lm.LEFT_WRIST to Pt(0.4f, y), Lm.RIGHT_WRIST to Pt(0.6f, y),
        )
    )

    private val armsDown = arms(0.7f)
    private val armsUp = arms(0.1f)

    @Test
    fun routineAdvancesThroughSteps() {
        val routine = listOf(
            RoutineStep(ExerciseType.ARM_RAISE, 2),
            RoutineStep(ExerciseType.ARM_RAISE, 1),
        )
        val s = MissionSession(routine)
        assertEquals(3, s.totalReps)
        var t = 0L
        val updates = ArrayList<MissionUpdate>()
        fun feed(f: PoseFrame, n: Int) = repeat(n) { t += 50; updates.add(s.update(f, t)) }

        feed(armsDown, 4)
        feed(armsUp, 4)
        feed(armsDown, 4)          // 1회
        feed(armsUp, 4)
        feed(armsDown, 4)          // 2회 → 1단계 완료

        val stepDone = updates.filter { it.stepCompleted }
        assertEquals(1, stepDone.size)
        assertEquals(0, stepDone[0].stepIndex)
        assertEquals(1, s.stepIndex)
        assertFalse(s.isComplete)

        // 새 단계는 시작 자세부터 다시 확인
        feed(armsDown, 4)
        feed(armsUp, 4)
        feed(armsDown, 4)
        assertTrue(s.isComplete)
        val last = updates.last { it.allCompleted }
        assertEquals(3, last.doneReps)
        assertEquals(3, s.snapshot().doneReps)
    }

    @Test
    fun countsNeverExceedTarget() {
        val s = MissionSession(listOf(RoutineStep(ExerciseType.ARM_RAISE, 1)))
        var t = 0L
        repeat(4) { t += 50; s.update(armsDown, t) }
        repeat(4) { t += 50; s.update(armsUp, t) }
        repeat(4) { t += 50; s.update(armsDown, t) }
        assertTrue(s.isComplete)
        val after = s.update(armsUp, t + 50)
        assertEquals(1, after.stepCount)
        assertTrue(after.allCompleted)
    }

    @Test
    fun routineCodecRoundTrip() {
        val routine = listOf(RoutineStep(ExerciseType.SQUAT, 15), RoutineStep(ExerciseType.JUMPING_JACK, 20))
        val text = RoutineCodec.encode(routine)
        assertEquals("squat:15,jumpingjack:20", text)
        assertEquals(routine, RoutineCodec.decode(text))
        assertEquals(listOf(RoutineStep(ExerciseType.SQUAT, 3)), RoutineCodec.decode("bogus:1, squat:3,lunge:0,pushup:x"))
        assertEquals("스쿼트 15회 → 점핑잭 20회", routine.describe())
    }
}

package com.fitalarm.app.core

/** 미션 화면에 그릴 정보 한 묶음 */
data class MissionUpdate(
    val stepIndex: Int,          // 지금(또는 방금 끝난) 단계 번호, 0부터
    val step: RoutineStep,
    val stepCount: Int,          // 이 단계에서 센 횟수 (목표 이하로 잘림)
    val counted: Boolean,        // 이번 프레임에 1회 추가됨
    val stepCompleted: Boolean,  // 이번 프레임에 단계 완료
    val allCompleted: Boolean,
    val hint: String,
    val tracking: Boolean,
    val doneReps: Int,           // 전체 진행 횟수
    val totalReps: Int,
)

/** 여러 운동으로 된 루틴을 순서대로 진행. book: 개인 보정 값 (기본: 보정 끔) */
class MissionSession(
    val routine: List<RoutineStep>,
    private val book: CalibrationBook = CalibrationBook(enabled = false),
) {
    init {
        require(routine.isNotEmpty()) { "routine must not be empty" }
    }

    var stepIndex: Int = 0
        private set
    private var counter: RepCounter = RepCounter.create(routine[0].type, book)
    private var completedReps = 0

    val totalReps: Int = routine.sumOf { it.reps }
    val isComplete: Boolean get() = stepIndex >= routine.size

    fun snapshot(hint: String = ""): MissionUpdate {
        val idx = stepIndex.coerceAtMost(routine.lastIndex)
        val step = routine[idx]
        val stepCount = if (isComplete) step.reps else counter.count.coerceAtMost(step.reps)
        return MissionUpdate(
            stepIndex = idx, step = step, stepCount = stepCount,
            counted = false, stepCompleted = false, allCompleted = isComplete,
            hint = hint, tracking = false,
            doneReps = if (isComplete) totalReps else completedReps + stepCount,
            totalReps = totalReps,
        )
    }

    fun update(frame: PoseFrame?, tMs: Long): MissionUpdate {
        if (isComplete) return snapshot()

        val idx = stepIndex
        val step = routine[idx]
        val u = counter.update(frame, tMs)
        val stepCount = u.count.coerceAtMost(step.reps)
        var stepDone = false
        if (u.count >= step.reps) {
            stepDone = true
            completedReps += step.reps
            stepIndex++
            if (!isComplete) counter = RepCounter.create(routine[stepIndex].type, book)
        }
        val done = if (stepDone) completedReps else completedReps + stepCount
        return MissionUpdate(
            stepIndex = idx, step = step, stepCount = stepCount,
            counted = u.counted, stepCompleted = stepDone, allCompleted = isComplete,
            hint = u.hint, tracking = u.tracking,
            doneReps = done, totalReps = totalReps,
        )
    }
}

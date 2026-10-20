package com.fitalarm.app.core

/** 운동 부위. 보너스 운동은 알람 루틴과 다른 부위에서 고름 */
enum class BodyPart(val title: String) {
    LOWER("하체"), UPPER("상체"), CORE("코어"), CARDIO("전신"),
}

/**
 * 선택 가능한 맨몸 운동 목록.
 * setupGuide: 휴대폰을 어디에 두고 어떻게 서야 하는지, motionGuide: 1회로 인정되는 동작.
 * effort: 1회당 강도 점수 (스쿼트 = 1.0 기준). 보너스 운동 횟수를 비슷한 강도로 맞출 때 사용.
 * secondsPerRep: 1회에 걸리는 대략적인 시간 (예상 소요 시간 표시용).
 */
enum class ExerciseType(
    val key: String,
    val title: String,
    val defaultReps: Int,
    val setupGuide: String,
    val motionGuide: String,
    val part: BodyPart,
    val effort: Float,
    val secondsPerRep: Float,
) {
    SQUAT(
        "squat", "스쿼트", 15,
        "휴대폰을 세워 두고 전신이 보이도록 2m 정도 떨어져 서세요.",
        "엉덩이를 무릎 높이까지 내렸다가 일어서면 1회",
        BodyPart.LOWER, 1.0f, 3f,
    ),
    PUSH_UP(
        "pushup", "팔굽혀펴기", 10,
        "휴대폰을 몸 옆쪽 바닥에 세워 두고, 옆에서 전신이 보이게 엎드리세요.",
        "가슴을 내렸다가 팔을 끝까지 펴면 1회",
        BodyPart.UPPER, 1.5f, 3f,
    ),
    JUMPING_JACK(
        "jumpingjack", "점핑잭", 20,
        "휴대폰을 정면에 세워 두고 전신이 보이도록 서세요.",
        "팔은 머리 위로, 다리는 벌렸다가 다시 모으면 1회",
        BodyPart.CARDIO, 0.5f, 1.5f,
    ),
    LUNGE(
        "lunge", "런지", 12,
        "휴대폰을 옆쪽에 세워 두고 전신이 보이게 서세요.",
        "한 발을 내딛고 무릎을 90도까지 굽혔다 펴면 1회",
        BodyPart.LOWER, 1.2f, 3f,
    ),
    SIT_UP(
        "situp", "윗몸일으키기", 15,
        "휴대폰을 몸 옆쪽 바닥에 세워 두고, 옆에서 전신이 보이게 누우세요.",
        "누운 자세에서 상체를 무릎 쪽으로 일으키면 1회",
        BodyPart.CORE, 0.8f, 3f,
    ),
    HIGH_KNEES(
        "highknees", "제자리 무릎 올리기", 30,
        "휴대폰을 정면에 세워 두고 전신이 보이도록 서세요.",
        "무릎을 골반 높이까지 올렸다 내리면 1회 (좌우 각각)",
        BodyPart.CARDIO, 0.4f, 1f,
    ),
    ARM_RAISE(
        "armraise", "만세 (팔 들어올리기)", 15,
        "상반신이 화면에 보이게 앉거나 서세요.",
        "두 팔을 머리 위로 쭉 뻗었다가 내리면 1회",
        BodyPart.UPPER, 0.3f, 2f,
    );

    companion object {
        fun fromKey(key: String?): ExerciseType? = entries.firstOrNull { it.key == key }
    }
}

data class RoutineStep(val type: ExerciseType, val reps: Int) {
    init {
        require(reps in MIN_REPS..MAX_REPS) { "reps out of range: $reps" }
    }

    companion object {
        const val MIN_REPS = 1
        const val MAX_REPS = 200
    }
}

/** 운동 루틴을 "squat:15,jumpingjack:20" 형태 문자열로 주고받기 (Intent 전달용). */
object RoutineCodec {
    fun encode(routine: List<RoutineStep>): String =
        routine.joinToString(",") { "${it.type.key}:${it.reps}" }

    fun decode(text: String?): List<RoutineStep> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(',').mapNotNull { token ->
            val parts = token.trim().split(':')
            if (parts.size != 2) return@mapNotNull null
            val type = ExerciseType.fromKey(parts[0]) ?: return@mapNotNull null
            val reps = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (reps !in RoutineStep.MIN_REPS..RoutineStep.MAX_REPS) return@mapNotNull null
            RoutineStep(type, reps)
        }
    }
}

fun List<RoutineStep>.describe(): String = joinToString(" → ") { "${it.type.title} ${it.reps}회" }

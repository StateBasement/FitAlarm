package com.fitalarm.app.core

import java.time.DayOfWeek
import kotlin.math.max

/**
 * 반복 요일 비트마스크.
 * bit0 = 월요일 … bit6 = 일요일 (DayOfWeek.value - 1 과 동일한 순서).
 * 0 이면 "반복 안 함"(1회성 알람).
 */
object Days {
    const val NONE = 0
    const val WEEKDAYS = 0x1F
    const val WEEKEND = 0x60
    const val EVERYDAY = 0x7F

    val SHORT_LABELS: List<String> = listOf("월", "화", "수", "목", "금", "토", "일")

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun contains(mask: Int, day: DayOfWeek): Boolean = (mask and bit(day)) != 0

    fun isSet(mask: Int, index: Int): Boolean = (mask and (1 shl index)) != 0

    fun toggle(mask: Int, index: Int): Int = (mask xor (1 shl index)) and EVERYDAY

    fun describe(mask: Int): String = when (mask and EVERYDAY) {
        NONE -> "반복 안 함"
        EVERYDAY -> "매일"
        WEEKDAYS -> "주중"
        WEEKEND -> "주말"
        else -> SHORT_LABELS.filterIndexed { i, _ -> isSet(mask, i) }.joinToString(" ")
    }
}

/** 진동 패턴. timings 는 VibrationEffect.createWaveform 형식(꺼짐, 켜짐, 꺼짐, …, ms). */
enum class VibrationPattern(val key: String, val title: String, val timings: LongArray) {
    BASIC("basic", "기본", longArrayOf(0, 800, 600)),
    SHORT_BURST("short", "짧게 반복", longArrayOf(0, 200, 150, 200, 900)),
    LONG("long", "길게", longArrayOf(0, 1500, 500)),
    HEARTBEAT("heartbeat", "심장 박동", longArrayOf(0, 120, 120, 160, 800));

    companion object {
        fun fromKey(key: String?): VibrationPattern = entries.firstOrNull { it.key == key } ?: BASIC
    }
}

/**
 * 알람 1개의 설정 + 런타임 상태(다시 알림 횟수/시각).
 * Android 의존성이 없는 순수 Kotlin 클래스라 JVM 단위 테스트가 가능합니다.
 */
data class Alarm(
    val id: Int = 0,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Int = Days.NONE,
    val enabled: Boolean = true,
    val label: String = "",

    // 소리
    val soundUri: String? = null,          // null = 시스템 기본 알람음
    val soundTitle: String = DEFAULT_SOUND_TITLE,
    val silent: Boolean = false,           // true = 소리 없이 진동만
    val volume: Int = 80,                  // 0..100 (알람 스트림 최대 볼륨 대비 %)
    val crescendo: Boolean = true,         // 점점 크게

    // 진동
    val vibrate: Boolean = true,
    val vibrationPattern: VibrationPattern = VibrationPattern.BASIC,

    // 다시 알림(스누즈)
    val snoozeEnabled: Boolean = true,
    val snoozeMinutes: Int = 5,            // 반복 주기
    val snoozeLimit: Int = 3,              // 반복 횟수, SNOOZE_UNLIMITED = 무제한

    // 울림 지속 시간: 이 시간 동안 끄지 않으면 자동 다시 알림(남은 횟수가 없으면 종료)
    val ringMinutes: Int = 5,              // RING_UNTIL_DISMISSED = 끌 때까지

    // 운동 미션
    val missionEnabled: Boolean = false,
    val routine: List<RoutineStep> = emptyList(),

    // ---- 런타임 상태 (저장되지만 사용자가 직접 설정하지 않음) ----
    val snoozeCount: Int = 0,
    val snoozedUntil: Long = 0L,           // epoch ms, 0 = 다시 알림 대기 아님
) {
    val isRepeating: Boolean get() = days != Days.NONE

    /** 미션을 켰고 운동이 1개 이상 있을 때만 미션 알람으로 취급 */
    val hasMission: Boolean get() = missionEnabled && routine.isNotEmpty()

    fun canSnooze(): Boolean =
        snoozeEnabled && (snoozeLimit == SNOOZE_UNLIMITED || snoozeCount < snoozeLimit)

    /** 남은 다시 알림 횟수. null = 무제한 */
    fun snoozesLeft(): Int? = when {
        !snoozeEnabled -> 0
        snoozeLimit == SNOOZE_UNLIMITED -> null
        else -> max(0, snoozeLimit - snoozeCount)
    }

    companion object {
        const val SNOOZE_UNLIMITED = -1
        const val RING_UNTIL_DISMISSED = 0
        const val DEFAULT_SOUND_TITLE = "기본 알람음"

        val SNOOZE_MINUTE_OPTIONS: List<Int> = listOf(1, 3, 5, 10, 15, 20, 30)
        val SNOOZE_LIMIT_OPTIONS: List<Int> = listOf(1, 2, 3, 5, 10, SNOOZE_UNLIMITED)
        val RING_MINUTE_OPTIONS: List<Int> = listOf(1, 3, 5, 10, 15, 30, RING_UNTIL_DISMISSED)

        fun describeSnoozeLimit(limit: Int): String =
            if (limit == SNOOZE_UNLIMITED) "무제한" else "${limit}회"

        fun describeRingMinutes(minutes: Int): String =
            if (minutes == RING_UNTIL_DISMISSED) "끌 때까지" else "${minutes}분"
    }
}

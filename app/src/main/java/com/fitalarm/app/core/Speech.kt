package com.fitalarm.app.core

import java.time.LocalDateTime

/** 음성(TTS)으로 읽을 한국어 문장 만들기 */
object KoreanSpeech {
    private val ONES = arrayOf("", "하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val TENS = arrayOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")

    // 수 관형사 (한 개, 두 시)
    private val ONES_ADNOMINAL = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")

    private val DOW = arrayOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")

    /** 1~99 는 고유어("열하나"), 그 외는 숫자 그대로 */
    fun nativeNumber(n: Int): String {
        if (n !in 1..99) return n.toString()
        return TENS[n / 10] + ONES[n % 10]
    }

    /** "한", "두", "스무", "스물한" (개수·시각 앞) */
    fun nativeAdnominal(n: Int): String {
        if (n !in 1..99) return n.toString()
        if (n == 20) return "스무"
        return TENS[n / 10] + ONES_ADNOMINAL[n % 10]
    }

    /** 운동 중 카운트. 남은 횟수가 1~3 이면 "세 개 남았어요" 를 덧붙임 */
    fun countPhrase(count: Int, target: Int): String {
        val left = target - count
        val base = nativeNumber(count)
        return if (left in 1..3) "$base, ${nativeAdnominal(left)} 개 남았어요" else base
    }

    /** "오전 여섯 시 삼십 분" 이 잘 읽히도록 시는 고유어, 분은 숫자 */
    fun timePhrase(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "오전" else "오후"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val h = "${nativeAdnominal(h12)} 시"
        return if (minute == 0) "$ampm $h" else "$ampm $h ${minute}분"
    }

    private fun greeting(hour: Int): String = when (hour) {
        in 4..10 -> "좋은 아침이에요."
        in 11..16 -> "좋은 오후예요."
        in 17..21 -> "좋은 저녁이에요."
        else -> "알람 시간이에요."
    }

    /** 알람이 울릴 때 읽는 안내 */
    fun announcement(
        now: LocalDateTime,
        label: String,
        routine: List<RoutineStep>,
        hasMission: Boolean,
        isSnooze: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append(greeting(now.hour)).append(' ')
        sb.append("${now.monthValue}월 ${now.dayOfMonth}일 ${DOW[now.dayOfWeek.value - 1]}, ")
        sb.append(timePhrase(now.hour, now.minute)).append("입니다. ")
        if (isSnooze) sb.append("다시 알림이에요. ")
        if (label.isNotBlank()) sb.append("${label.trim()} 알람이에요. ")
        if (hasMission && routine.isNotEmpty()) {
            sb.append("오늘의 운동은 ")
            sb.append(routine.joinToString(", ") { "${it.type.title.substringBefore(" (")} ${it.reps}회" })
            sb.append("입니다.")
        } else {
            sb.append("오늘도 힘차게 시작해 볼까요?")
        }
        return sb.toString().trim()
    }

    fun stepDone(done: ExerciseType, next: RoutineStep?): String =
        if (next == null) "미션 완료! 좋은 하루 보내세요."
        else "${done.title.substringBefore(" (")} 완료! 다음은 ${next.type.title.substringBefore(" (")} ${next.reps}회예요."
}

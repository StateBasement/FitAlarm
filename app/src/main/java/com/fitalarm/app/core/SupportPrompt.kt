package com.fitalarm.app.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 응원(후원) 팝업 관련 저장 상태 */
data class SupportPromptState(
    val neverShow: Boolean = false,          // "다시 보지 않기"
    val supporter: Boolean = false,          // 이미 후원함
    val lastShown: LocalDate? = null,
    val shownMilestones: Set<String> = emptySet(),
)

data class SupportMilestone(val key: String, val headline: String, val message: String)

/**
 * 응원 팝업을 띄울지 결정 (순수 함수).
 * 귀찮지 않도록:
 *  - 설치 후 7일 동안은 띄우지 않음
 *  - 한 번 띄우면 30일 동안 다시 띄우지 않음
 *  - 의미 있는 순간(연속 성공 7·30·100·365일, 누적 성공 20일)에만, 순간마다 딱 한 번
 *  - "다시 보지 않기"를 눌렀거나 이미 후원했으면 영원히 띄우지 않음
 */
object SupportPromptRule {
    val STREAK_MILESTONES = listOf(7, 30, 100, 365)
    const val TOTAL_MILESTONE = 20
    const val MIN_DAYS_SINCE_INSTALL = 7L
    const val MIN_DAYS_BETWEEN = 30L

    fun pick(
        state: SupportPromptState,
        today: LocalDate,
        installDate: LocalDate,
        streak: Int,
        successDays: Int,
    ): SupportMilestone? {
        if (state.neverShow || state.supporter) return null
        if (ChronoUnit.DAYS.between(installDate, today) < MIN_DAYS_SINCE_INSTALL) return null
        val last = state.lastShown
        if (last != null && ChronoUnit.DAYS.between(last, today) < MIN_DAYS_BETWEEN) return null

        // 가장 큰 달성 단계 하나만 (작은 단계를 건너뛰었으면 그 단계는 다시 묻지 않음)
        val streakHit = STREAK_MILESTONES.filter { streak >= it }.maxOrNull()
        if (streakHit != null && "streak$streakHit" !in state.shownMilestones) {
            return SupportMilestone(
                key = "streak$streakHit",
                headline = "${streakHit}일 연속 성공!",
                message = "꾸준히 해내고 있어요. 피타람이 아침에 도움이 되고 있다면 응원해 주세요.",
            )
        }
        if (successDays >= TOTAL_MILESTONE && "total$TOTAL_MILESTONE" !in state.shownMilestones) {
            return SupportMilestone(
                key = "total$TOTAL_MILESTONE",
                headline = "벌써 ${successDays}번의 아침 운동!",
                message = "피타람이 마음에 드셨다면 커피 한 잔으로 응원해 주세요. 더 좋은 기능을 만드는 데 쓸게요.",
            )
        }
        return null
    }

    /** 팝업을 띄운 뒤 저장할 상태. 이미 지나간 작은 단계도 함께 표시해 다시 묻지 않음 */
    fun afterShown(state: SupportPromptState, shown: SupportMilestone, today: LocalDate, streak: Int): SupportPromptState {
        val passed = STREAK_MILESTONES.filter { streak >= it }.map { "streak$it" }
        return state.copy(lastShown = today, shownMilestones = state.shownMilestones + passed + shown.key)
    }
}

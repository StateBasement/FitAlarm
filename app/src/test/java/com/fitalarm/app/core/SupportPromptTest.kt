package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SupportPromptTest {
    private val install = LocalDate.of(2026, 10, 1)
    private val today = LocalDate.of(2026, 10, 29)

    @Test
    fun notDuringFirstWeek() {
        assertNull(SupportPromptRule.pick(SupportPromptState(), install.plusDays(6), install, 7, 7))
        assertEquals("streak7", SupportPromptRule.pick(SupportPromptState(), install.plusDays(7), install, 7, 7)?.key)
    }

    @Test
    fun milestonesOnlyOnce() {
        val first = SupportPromptRule.pick(SupportPromptState(), today, install, 8, 10)!!
        assertEquals("streak7", first.key)
        val after = SupportPromptRule.afterShown(SupportPromptState(), first, today, 8)
        // 30일 안에는 다시 안 뜸
        assertNull(SupportPromptRule.pick(after, today.plusDays(29), install, 9, 25))
        // 30일 뒤: 연속 7일은 이미 보여줬으니 누적 20일 단계
        assertEquals("total20", SupportPromptRule.pick(after, today.plusDays(30), install, 9, 25)?.key)
    }

    @Test
    fun skippedSmallMilestonesAreNotAskedLater() {
        val s = SupportPromptRule.pick(SupportPromptState(), today, install, 35, 40)!!
        assertEquals("streak30", s.key)
        val after = SupportPromptRule.afterShown(SupportPromptState(), s, today, 35)
        val later = SupportPromptRule.pick(after, today.plusDays(40), install, 35, 40)
        assertEquals("total20", later?.key)   // streak7 은 다시 묻지 않음
    }

    @Test
    fun neverShowAndSupporterStopEverything() {
        assertNull(SupportPromptRule.pick(SupportPromptState(neverShow = true), today, install, 100, 100))
        assertNull(SupportPromptRule.pick(SupportPromptState(supporter = true), today, install, 100, 100))
    }

    @Test
    fun nothingToCelebrate() {
        assertNull(SupportPromptRule.pick(SupportPromptState(), today, install, 3, 5))
    }
}

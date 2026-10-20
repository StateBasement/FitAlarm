package com.fitalarm.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * CI 에서 실제 구글 공휴일 달력(.ics)을 내려받아 HOLIDAY_ICS_FILE 로 넘겨주면 실행되는 검증.
 * 파서가 실제 피드를 읽는지, 내장 규칙과 어디가 다른지를 build/holiday-feed-summary.txt 로 남김.
 * 파일이 없으면(로컬 실행) 건너뜀.
 */
class LiveHolidayFeedTest {
    @Test
    fun parsesRealGoogleFeed() {
        val path = System.getenv("HOLIDAY_ICS_FILE") ?: return
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return

        val parsed = IcsHolidayParser.parse(f.readText())
        val out = StringBuilder()
        out.append("parsed=${parsed.size}\n")
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2027, 12, 31)
        val feed = parsed.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.associate { it.date to it.name }
        out.append("feed 2026-10..2027-12: ").append(feed.entries.joinToString("; ") { "${it.key} ${it.value}" }).append('\n')

        val builtIn = (2026..2027).flatMap { KoreanHolidays.forYear(it) }
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.associate { it.date to it.name }
        val onlyFeed = feed.keys - builtIn.keys
        val onlyBuiltIn = builtIn.keys - feed.keys
        out.append("only in feed: ").append(onlyFeed.sorted().joinToString { "$it ${feed[it]}" }).append('\n')
        out.append("only built-in: ").append(onlyBuiltIn.sorted().joinToString { "$it ${builtIn[it]}" }).append('\n')

        // 원본 일정 이름/설명 표본 (필터 동작 확인용)
        val raw = Regex("SUMMARY:(.*)").findAll(f.readText()).map { it.groupValues[1].trim() }.distinct().take(60).toList()
        out.append("raw summaries: ").append(raw.joinToString(" | ")).append('\n')
        val descs = Regex("DESCRIPTION:(.*)").findAll(f.readText()).map { it.groupValues[1].trim().take(40) }.distinct().take(5).toList()
        out.append("raw descriptions: ").append(descs.joinToString(" | ")).append('\n')

        File("build").mkdirs()
        File("build/holiday-feed-summary.txt").writeText(out.toString())
        assertTrue("feed should contain holidays", parsed.isNotEmpty())
    }
}

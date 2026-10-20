package com.fitalarm.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class Holiday(val date: LocalDate, val name: String)

/**
 * 앱에 내장한 대한민국 공휴일 규칙 (관공서의 공휴일에 관한 규정 기준).
 *
 * - 양력 공휴일은 매년 계산.
 * - 음력 명절(설날·부처님오신날·추석)은 한국천문연구원 월력요항 기준 날짜표로만 넣는다.
 *   표에 없는 해를 추측하면 평일 알람이 울리지 않는 사고가 날 수 있으므로 계산하지 않고
 *   외부 달력(구글 공휴일 달력, 폰 달력)에 맡긴다.
 * - 대체공휴일:
 *     설·추석 연휴: 일요일 또는 다른 공휴일과 겹치면
 *     어린이날: 토·일요일 또는 다른 공휴일과 겹치면
 *     국경일(3·1절, 제헌절, 광복절, 개천절, 한글날)·부처님오신날·기독탄신일·노동절: 토·일요일과 겹치면
 *     → 그 날 다음의 첫 번째 비공휴일(토·일·공휴일이 아닌 날)
 *     1월 1일, 현충일, 선거일: 대체공휴일 없음
 */
object KoreanHolidays {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    // 음력 1월 1일 / 4월 8일 / 8월 15일의 양력 날짜 (월력요항)
    private val SEOLLAL = mapOf(
        2025 to d(2025, 1, 29), 2026 to d(2026, 2, 17), 2027 to d(2027, 2, 7), 2028 to d(2028, 1, 27),
        2029 to d(2029, 2, 13), 2030 to d(2030, 2, 3), 2031 to d(2031, 1, 23),
    )
    private val BUDDHA = mapOf(
        2025 to d(2025, 5, 5), 2026 to d(2026, 5, 24), 2027 to d(2027, 5, 13), 2028 to d(2028, 5, 2),
        2029 to d(2029, 5, 20), 2030 to d(2030, 5, 9),
    )
    private val CHUSEOK = mapOf(
        2025 to d(2025, 10, 6), 2026 to d(2026, 9, 25), 2027 to d(2027, 9, 15), 2028 to d(2028, 10, 3),
        2029 to d(2029, 9, 22), 2030 to d(2030, 9, 12), 2031 to d(2031, 10, 1),
    )
    // 날짜가 법으로 정해진 선거일만 (임시공휴일·보궐선거는 외부 달력으로)
    private val ELECTIONS = listOf(Holiday(d(2028, 4, 12), "국회의원선거일"))

    private enum class Rule { NONE, SUNDAY_OR_OVERLAP, WEEKEND_OR_OVERLAP, WEEKEND }

    private class Base(val date: LocalDate, val name: String, val rule: Rule)

    private val cache = HashMap<Int, List<Holiday>>()

    /** 해당 연도 공휴일 (대체공휴일 포함, 날짜순, 같은 날은 이름을 합침) */
    @Synchronized
    fun forYear(year: Int): List<Holiday> = cache.getOrPut(year) { compute(year) }

    private fun compute(year: Int): List<Holiday> {
        val base = ArrayList<Base>()
        fun add(date: LocalDate, name: String, rule: Rule) = base.add(Base(date, name, rule))
        fun add(m: Int, day: Int, name: String, rule: Rule) = add(d(year, m, day), name, rule)
        fun addThree(center: LocalDate, name: String) {
            add(center.minusDays(1), name, Rule.SUNDAY_OR_OVERLAP)
            add(center, name, Rule.SUNDAY_OR_OVERLAP)
            add(center.plusDays(1), name, Rule.SUNDAY_OR_OVERLAP)
        }

        add(1, 1, "1월 1일", Rule.NONE)
        SEOLLAL[year]?.let { addThree(it, "설날") }
        add(3, 1, "3·1절", Rule.WEEKEND)
        if (year >= 2026) add(5, 1, "노동절", Rule.WEEKEND)
        add(5, 5, "어린이날", Rule.WEEKEND_OR_OVERLAP)
        BUDDHA[year]?.let { add(it, "부처님오신날", Rule.WEEKEND) }
        add(6, 6, "현충일", Rule.NONE)
        if (year >= 2026) add(7, 17, "제헌절", Rule.WEEKEND)
        add(8, 15, "광복절", Rule.WEEKEND)
        CHUSEOK[year]?.let { addThree(it, "추석") }
        add(10, 3, "개천절", Rule.WEEKEND)
        add(10, 9, "한글날", Rule.WEEKEND)
        add(12, 25, "기독탄신일", Rule.WEEKEND)
        ELECTIONS.filter { it.date.year == year }.forEach { add(it.date, it.name, Rule.NONE) }

        // 하루를 잃은 공휴일 찾기
        val lost = ArrayList<Base>()
        for (b in base) {
            val dow = b.date.dayOfWeek
            val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
            val overlaps = base.any { it !== b && it.date == b.date }
            val loses = when (b.rule) {
                Rule.NONE -> false
                Rule.SUNDAY_OR_OVERLAP -> dow == DayOfWeek.SUNDAY || overlaps
                Rule.WEEKEND_OR_OVERLAP -> weekend || overlaps
                Rule.WEEKEND -> weekend
            }
            if (loses) lost.add(b)
        }

        // 대체공휴일 배정: 날짜순으로, 다음 첫 비공휴일(토·일·공휴일 아님)
        val taken = base.map { it.date }.toHashSet()
        val result = ArrayList<Holiday>()
        base.forEach { result.add(Holiday(it.date, it.name)) }
        for (b in lost.sortedBy { it.date }) {
            var c = b.date.plusDays(1)
            while (c.dayOfWeek == DayOfWeek.SATURDAY || c.dayOfWeek == DayOfWeek.SUNDAY || c in taken) {
                c = c.plusDays(1)
            }
            taken.add(c)
            result.add(Holiday(c, "대체공휴일(${b.name})"))
        }

        return result.groupBy { it.date }
            .map { (date, list) -> Holiday(date, list.map { it.name }.distinct().joinToString(", ")) }
            .sortedBy { it.date }
    }

    /** 음력 명절 날짜표가 있어 내장 규칙만으로 완전한 해인지 */
    fun hasLunarData(year: Int): Boolean = SEOLLAL.containsKey(year) && CHUSEOK.containsKey(year) && BUDDHA.containsKey(year)

    fun nameOf(date: LocalDate): String? = forYear(date.year).firstOrNull { it.date == date }?.name
}

/** 공휴일 달력 일정 이름이 "쉬는 날"인지 판단 (기념일·이브 등은 제외) */
object HolidayNames {
    private val OBSERVANCE = listOf("기념일", "Observance", "observance")
    private val PUBLIC = listOf("공휴일", "Public holiday", "public holiday")
    private val EXCLUDE = listOf("이브", "전야", "Eve")
    private val KEYWORDS = listOf(
        "설날", "설 연휴", "섣달", "추석", "한가위", "부처님", "석가탄신", "기독탄신", "성탄", "크리스마스",
        "신정", "새해", "1월 1일", "삼일절", "3·1절", "3.1절", "3∙1절", "어린이날", "현충일", "제헌절",
        "광복절", "개천절", "한글날", "노동절", "대체공휴일", "대체 공휴일", "대체휴일", "임시공휴일", "임시 공휴일",
        "선거",
    )

    fun isDayOff(summary: String, description: String?): Boolean {
        val s = summary.trim()
        if (s.isEmpty()) return false
        if (EXCLUDE.any { s.contains(it) }) return false
        val desc = description.orEmpty()
        if (OBSERVANCE.any { desc.contains(it) } && PUBLIC.none { desc.contains(it) }) return false
        if (PUBLIC.any { desc.contains(it) }) return true
        return KEYWORDS.any { s.contains(it) }
    }
}

/** iCalendar(.ics) 에서 하루 종일 공휴일 일정만 뽑아냄 (구글 공개 공휴일 달력용) */
object IcsHolidayParser {
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun parse(text: String): List<Holiday> {
        val lines = unfold(text)
        val out = ArrayList<Holiday>()
        var inEvent = false
        var start: LocalDate? = null
        var end: LocalDate? = null
        var summary = ""
        var description: String? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true; start = null; end = null; summary = ""; description = null
                }
                line == "END:VEVENT" -> {
                    val s = start
                    if (inEvent && s != null && HolidayNames.isDayOff(summary, description)) {
                        // DTEND 는 종일 일정에서 "다음 날"(미포함). 없으면 하루짜리
                        val e = end?.takeIf { it.isAfter(s) } ?: s.plusDays(1)
                        var dd: LocalDate = s
                        var guard = 0
                        while (dd.isBefore(e) && guard < 10) {
                            out.add(Holiday(dd, summary))
                            dd = dd.plusDays(1); guard++
                        }
                    }
                    inEvent = false
                }
                !inEvent -> Unit
                line.startsWith("DTSTART") -> start = parseDate(line)
                line.startsWith("DTEND") -> end = parseDate(line)
                line.startsWith("SUMMARY") -> summary = unescape(value(line))
                line.startsWith("DESCRIPTION") -> description = unescape(value(line))
            }
        }
        return out.groupBy { it.date }
            .map { (date, list) -> Holiday(date, list.map { it.name }.distinct().joinToString(", ")) }
            .sortedBy { it.date }
    }

    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(raw.size)
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + l.substring(1)
            } else {
                out.add(l)
            }
        }
        return out
    }

    private fun value(line: String): String {
        val i = line.indexOf(':')
        return if (i < 0) "" else line.substring(i + 1)
    }

    private fun parseDate(line: String): LocalDate? {
        val v = value(line).trim()
        if (v.length < 8) return null
        return try {
            if (v.length >= 15 && v.contains('T')) {
                // 시각이 있는 일정: UTC 면 한국 시간으로 날짜 판단
                val date = LocalDate.parse(v.substring(0, 8), DATE)
                if (v.endsWith("Z")) {
                    val hour = v.substring(9, 11).toInt()
                    date.atTime(hour, 0).atOffset(ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
                } else date
            } else {
                LocalDate.parse(v.substring(0, 8), DATE)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()
}

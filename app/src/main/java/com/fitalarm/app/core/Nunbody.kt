package com.fitalarm.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * 눈바디(몸 사진) 기록의 순수 로직. 안드로이드에 의존하지 않아 단위 테스트로 확인합니다.
 * 사진 파일·설정 저장은 data/NunbodyRepository, 화면은 ui/Nunbody*.kt
 */

/** 어떤 카메라로 찍었는지 */
enum class PhotoSource(val key: String, val title: String) {
    APP_BACK("app_back", "후면"),
    APP_FRONT("app_front", "전면"),
    SYSTEM("system", "기본 카메라");

    companion object {
        fun fromKey(key: String?): PhotoSource = entries.firstOrNull { it.key == key } ?: APP_BACK
    }
}

/** 눈바디 사진 한 장. id 는 앱 안 파일 이름이자 내보낼 때의 파일 이름 */
data class NunbodyPhoto(
    val id: String,
    val takenAt: LocalDateTime,
    val width: Int,
    val height: Int,
    val source: PhotoSource,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val memo: String = "",
    val bytes: Long = 0,
) {
    val date: LocalDate get() = takenAt.toLocalDate()
}

// ============================================================================ 파일 이름

/** 일반 카메라 앱처럼 앱이름_날짜_시각. 앱 언어와 상관없이 영어 */
object NunbodyNames {
    const val PREFIX = "AlarmFit_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)

    /** 같은 초에 두 장이면 _2, _3 … */
    fun photo(takenAt: LocalDateTime, taken: Set<String> = emptySet()): String {
        val base = PREFIX + STAMP.format(takenAt)
        var name = "$base.jpg"
        var n = 2
        while (name in taken) {
            name = "${base}_$n.jpg"
            n++
        }
        return name
    }

    /** 체중 기록 표: 기간 */
    fun table(from: LocalDate, to: LocalDate): String = PREFIX + DAY.format(from) + "-" + DAY.format(to) + ".csv"

    /** 앱이 만든 이름인지 (공유·카메라 파일 이름 검사용) */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 80 && name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } &&
            !name.startsWith(".") && !name.contains("..")
}

// ============================================================================ 화질

data class PhotoSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

enum class QualityTier(val key: String, val title: String, val targetPixels: Long) {
    MAX("max", "최고", Long.MAX_VALUE),
    HIGH("high", "높음", 8_000_000),
    MID("mid", "보통", 5_000_000),
    LOW("low", "절약", 2_000_000);

    companion object {
        fun fromKey(key: String?): QualityTier = entries.firstOrNull { it.key == key } ?: MAX
    }
}

data class QualityOption(val tier: QualityTier, val size: PhotoSize)

object PhotoQuality {
    /** 너무 작은 크기는 몸 사진으로 쓸 수 없어 목록에서 뺌 */
    private const val MIN_PIXELS = 1_000_000L

    /**
     * 카메라가 실제로 지원하는 JPEG 크기에서 최고·높음·보통·절약을 고름.
     * 가장 큰 크기와 같은 비율만 쓰고, 단계마다 바로 위보다 25% 이상 작은 것 중 목표에 가장 가까운 크기.
     * 지원하는 크기가 적은 폰은 그만큼만 나옴 (800만이 최대인 폰은 800만이 '최고').
     */
    fun options(sizes: List<PhotoSize>): List<QualityOption> {
        val valid = sizes.filter { it.width > 0 && it.height > 0 }
        val max = valid.maxByOrNull { it.pixels } ?: return emptyList()
        val ratio = max.width.toDouble() / max.height
        val sameRatio = valid.filter { abs(it.width.toDouble() / it.height - ratio) < 0.02 && it.pixels >= MIN_PIXELS }
        val out = mutableListOf(QualityOption(QualityTier.MAX, max))
        for (tier in listOf(QualityTier.HIGH, QualityTier.MID, QualityTier.LOW)) {
            val upper = out.last().size.pixels * 3 / 4
            val pick = sameRatio.filter { it.pixels <= upper }.minByOrNull { abs(it.pixels - tier.targetPixels) } ?: break
            out += QualityOption(tier, pick)
        }
        return out
    }

    /** 고른 단계가 이 카메라에 없으면 그보다 한 단계 높은 것 */
    fun pick(options: List<QualityOption>, wanted: QualityTier): QualityOption? =
        options.lastOrNull { it.tier.ordinal <= wanted.ordinal } ?: options.firstOrNull()

    /** 1200만, 800만, 500만 … (100만 단위로 반올림, 100만 미만은 10만 단위) */
    fun megapixelLabel(size: PhotoSize): String {
        val man = size.pixels / 10_000.0
        val rounded = if (man >= 100) (man / 100).roundToLong() * 100 else (man / 10).roundToLong() * 10
        return "${rounded}만"
    }

    /** JPEG 한 장 예상 크기 (몸 사진 기준 대략 픽셀당 0.33바이트) */
    fun estimatedBytes(size: PhotoSize): Long = (size.pixels * 0.33).toLong()
}

/** 4MB, 2.7MB, 0.6MB, 48MB */
fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return when {
        mb >= 10 -> "${mb.roundToLong()}MB"
        mb >= 0.1 -> trimZero(String.format(Locale.ROOT, "%.1f", mb)) + "MB"
        bytes <= 0 -> "0MB"
        else -> "0.1MB"
    }
}

private fun trimZero(s: String): String = if (s.endsWith(".0")) s.dropLast(2) else s

// ============================================================================ 체중·키

object BodyNumbers {
    const val MIN_WEIGHT = 20.0
    const val MAX_WEIGHT = 300.0
    const val MIN_HEIGHT = 80.0
    const val MAX_HEIGHT = 250.0

    /** "68.2", "68,2", " 68 " → 68.2. 비었으면 null, 범위를 벗어나면 예외 대신 Invalid */
    fun parse(text: String, min: Double, max: Double): Parsed {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Parsed.Empty
        val v = t.toDoubleOrNull() ?: return Parsed.Invalid
        if (v.isNaN() || v < min || v > max) return Parsed.Invalid
        return Parsed.Value((v * 10).roundToLong() / 10.0)
    }

    sealed class Parsed {
        data object Empty : Parsed()
        data object Invalid : Parsed()
        data class Value(val v: Double) : Parsed()
    }

    /** 68.2 → "68.2", 68.0 → "68" */
    fun format(v: Double): String = trimZero(String.format(Locale.ROOT, "%.1f", v))

    /** −1.4kg / +0.6kg / 0kg (마이너스는 읽기 쉬운 유니코드 기호) */
    fun formatChange(delta: Double, unit: String = "kg"): String {
        val r = (delta * 10).roundToLong() / 10.0
        return when {
            r > 0 -> "+${format(r)}$unit"
            r < 0 -> "−${format(-r)}$unit"
            else -> "0$unit"
        }
    }
}

object NunbodyStats {
    /** 체중이 있는 사진만, 오래된 순 */
    fun weighed(photos: List<NunbodyPhoto>): List<NunbodyPhoto> =
        photos.filter { it.weightKg != null }.sortedBy { it.takenAt }

    fun latestWeight(photos: List<NunbodyPhoto>): NunbodyPhoto? = weighed(photos).lastOrNull()

    /** 가장 최근 체중 − 그 전 체중 */
    fun changeSincePrevious(photos: List<NunbodyPhoto>): Double? {
        val w = weighed(photos)
        if (w.size < 2) return null
        return w[w.size - 1].weightKg!! - w[w.size - 2].weightKg!!
    }

    /** since 이후 첫 체중부터 가장 최근 체중까지의 변화와 그 사이 개월 수 */
    fun changeSince(photos: List<NunbodyPhoto>, since: LocalDate): Pair<Double, Long>? {
        val w = weighed(photos).filter { !it.date.isBefore(since) }
        if (w.size < 2) return null
        val first = w.first()
        val last = w.last()
        val months = ChronoUnit.MONTHS.between(first.date.withDayOfMonth(1), last.date.withDayOfMonth(1)).coerceAtLeast(1)
        return (last.weightKg!! - first.weightKg!!) to months
    }

    /** 가장 최근 키 (사진마다 키를 다시 적을 필요 없게) */
    fun latestHeight(photos: List<NunbodyPhoto>): Double? =
        photos.filter { it.heightCm != null }.maxByOrNull { it.takenAt }?.heightCm
}

// ============================================================================ 기록 표 (CSV)

object NunbodyCsv {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** 엑셀에서 한글이 깨지지 않게 UTF-8 BOM 을 붙이고, 줄바꿈은 CRLF */
    fun build(photos: List<NunbodyPhoto>): String {
        val sb = StringBuilder("﻿")
        sb.append("날짜,시각,체중(kg),키(cm),메모,사진 파일\r\n")
        for (p in photos.sortedBy { it.takenAt }) {
            sb.append(DATE.format(p.takenAt)).append(',')
                .append(TIME.format(p.takenAt)).append(',')
                .append(p.weightKg?.let { BodyNumbers.format(it) } ?: "").append(',')
                .append(p.heightCm?.let { BodyNumbers.format(it) } ?: "").append(',')
                .append(cell(p.memo)).append(',')
                .append(cell(p.id)).append("\r\n")
        }
        return sb.toString()
    }

    /** 쉼표·따옴표·줄바꿈은 따옴표로 감싸고, =,+,-,@ 로 시작하면 엑셀 수식으로 실행되지 않게 ' 를 붙임 */
    fun cell(text: String): String {
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        if (t.isNotEmpty() && t[0] in "=+-@\t") t = "'$t"
        return if (t.any { it == ',' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
}

// ============================================================================ 기간

enum class RangePreset(val title: String) {
    THIS_MONTH("이번 달"),
    MONTHS_3("3개월"),
    MONTHS_6("6개월"),
    ALL("전체");
}

object NunbodyRanges {
    fun preset(p: RangePreset, today: LocalDate, earliest: LocalDate?): Pair<LocalDate, LocalDate> = when (p) {
        RangePreset.THIS_MONTH -> today.withDayOfMonth(1) to today
        RangePreset.MONTHS_3 -> today.minusMonths(3) to today
        RangePreset.MONTHS_6 -> today.minusMonths(6) to today
        RangePreset.ALL -> minOf(earliest ?: today, today) to today
    }

    /** 기간 안의 사진, 찍은 순서대로 (내보낼 때 이 순서로 저장) */
    fun inRange(photos: List<NunbodyPhoto>, from: LocalDate, to: LocalDate): List<NunbodyPhoto> =
        photos.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.takenAt }
}

/**
 * 달력에서 기간 고르기.
 *  - 탭: 처음 누르면 시작, 다음에 누르면 끝. 시작보다 앞을 누르면 시작이 바뀜. 둘 다 있으면 새로 시작
 *  - 끌기: 누른 날부터 손가락이 있는 날까지 (어느 방향이든)
 *  - 오늘 이후는 고를 수 없음
 */
class RangeSelection(var start: LocalDate? = null, var end: LocalDate? = null, private val today: LocalDate) {
    private var dragAnchor: LocalDate? = null

    private fun clamp(d: LocalDate) = if (d.isAfter(today)) today else d

    fun tap(day: LocalDate) {
        val d = clamp(day)
        val s = start
        when {
            s == null || end != null -> { start = d; end = null }
            d.isBefore(s) -> start = d
            else -> end = d
        }
    }

    fun dragStart(day: LocalDate) {
        val d = clamp(day)
        dragAnchor = d
        start = d
        end = d
    }

    fun dragTo(day: LocalDate) {
        val a = dragAnchor ?: return dragStart(day)
        val d = clamp(day)
        if (d.isBefore(a)) { start = d; end = a } else { start = a; end = d }
    }

    fun dragEnd() {
        dragAnchor = null
    }

    /** 끝을 아직 안 골랐으면 시작 하루 */
    fun result(): Pair<LocalDate, LocalDate>? {
        val s = start ?: return null
        return s to (end ?: s)
    }

    fun contains(d: LocalDate): Boolean {
        val s = start ?: return false
        val e = end ?: s
        return !d.isBefore(s) && !d.isAfter(e)
    }
}

// ============================================================================ 촬영 알림

enum class ReminderPeriod(val key: String, val title: String) {
    WEEKLY("weekly", "매주 월요일"),
    BIWEEKLY("biweekly", "2주마다 월요일"),
    MONTHLY("monthly", "매월 1일");

    companion object {
        fun fromKey(key: String?): ReminderPeriod = entries.firstOrNull { it.key == key } ?: MONTHLY
    }
}

object NunbodyReminderMath {
    /**
     * now 이후 첫 알림 시각.
     * 2주마다는 anchor(알림을 켠 날)가 속한 주의 월요일부터 2주 간격.
     */
    fun next(now: LocalDateTime, period: ReminderPeriod, time: LocalTime, anchor: LocalDate): LocalDateTime {
        val today = now.toLocalDate()
        return when (period) {
            ReminderPeriod.WEEKLY -> {
                var d = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                if (!d.atTime(time).isAfter(now)) d = d.plusWeeks(1)
                d.atTime(time)
            }
            ReminderPeriod.BIWEEKLY -> {
                val base = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                var d = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                if (d.isBefore(base)) d = base
                while (ChronoUnit.WEEKS.between(base, d) % 2 != 0L || !d.atTime(time).isAfter(now)) d = d.plusWeeks(1)
                d.atTime(time)
            }
            ReminderPeriod.MONTHLY -> {
                var d = today.withDayOfMonth(1)
                if (!d.atTime(time).isAfter(now)) d = d.plusMonths(1)
                d.atTime(time)
            }
        }
    }
}

// ============================================================================ 사진 방향 (EXIF)

object ExifMath {
    /** EXIF 방향값 → 바로 세우는 데 필요한 시계 방향 회전(도) */
    fun rotationOf(orientation: Int): Int = when (orientation) {
        6, 5 -> 90
        3, 4 -> 180
        8, 7 -> 270
        else -> 0
    }

    fun isMirrored(orientation: Int): Boolean = orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7

    /**
     * 지금 방향값에 '보이는 모습 그대로 좌우 반전'을 더한 방향값.
     * 전면 카메라는 미리보기가 거울처럼 보이므로, 찍힌 사진도 화면에 보이던 모습 그대로 저장할 때 씀 (화질 손실 없음).
     * 1↔2, 6↔5, 3↔4, 8↔7
     */
    fun mirrored(orientation: Int): Int = when (orientation) {
        1, 0 -> 2
        2 -> 1
        6 -> 5
        5 -> 6
        3 -> 4
        4 -> 3
        8 -> 7
        7 -> 8
        else -> 2
    }
}

// ============================================================================ 잠금 없는 폰 경고

object NoLockWarning {
    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    fun due(nowMs: Long, snoozedUntilMs: Long): Boolean = nowMs >= snoozedUntilMs

    fun snoozeUntil(nowMs: Long): Long = nowMs + SNOOZE_MS
}

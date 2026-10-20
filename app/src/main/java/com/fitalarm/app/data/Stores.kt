package com.fitalarm.app.data

import android.content.Context
import android.content.SharedPreferences
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.CalibrationBook
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.Holiday
import com.fitalarm.app.core.KoreanHolidays
import com.fitalarm.app.core.RecordEntry
import com.fitalarm.app.core.RecordKind
import com.fitalarm.app.core.SkipRule
import com.fitalarm.app.core.VoiceStyle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// ============================================================================ 설정

data class AppSettings(
    val voiceAnnounce: Boolean = true,
    val voiceStyle: VoiceStyle = VoiceStyle.WARM_FEMALE,
    val announceRepeat: Boolean = false,
    val voiceCount: Boolean = true,
    val bonusEnabled: Boolean = true,
    val bonusLevel: Int = 0,
    val lastBonus: ExerciseType? = null,
    val calibration: Boolean = true,
    val holidayAutoUpdate: Boolean = true,
    val useDeviceCalendar: Boolean = true,
)

object SettingsRepository {
    private const val PREFS = "fit_alarm_settings"
    private const val KEY = "settings_v1"
    private val lock = Any()
    @Volatile private var cache: AppSettings? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): AppSettings = cache ?: synchronized(lock) {
        cache ?: (prefs(ctx).getString(KEY, null)?.let { fromJson(it) } ?: AppSettings()).also { cache = it }
    }

    fun update(ctx: Context, change: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val next = change(get(ctx))
        prefs(ctx).edit().putString(KEY, toJson(next).toString()).commit()
        cache = next
        next
    }

    internal fun toJson(s: AppSettings): JSONObject = JSONObject().apply {
        put("voiceAnnounce", s.voiceAnnounce)
        put("voiceStyle", s.voiceStyle.key)
        put("announceRepeat", s.announceRepeat)
        put("voiceCount", s.voiceCount)
        put("bonusEnabled", s.bonusEnabled)
        put("bonusLevel", s.bonusLevel)
        put("lastBonus", s.lastBonus?.key ?: JSONObject.NULL)
        put("calibration", s.calibration)
        put("holidayAutoUpdate", s.holidayAutoUpdate)
        put("useDeviceCalendar", s.useDeviceCalendar)
    }

    internal fun fromJson(text: String): AppSettings = try {
        val o = JSONObject(text)
        val d = AppSettings()
        AppSettings(
            voiceAnnounce = o.optBoolean("voiceAnnounce", d.voiceAnnounce),
            voiceStyle = VoiceStyle.fromKey(o.optString("voiceStyle")),
            announceRepeat = o.optBoolean("announceRepeat", d.announceRepeat),
            voiceCount = o.optBoolean("voiceCount", d.voiceCount),
            bonusEnabled = o.optBoolean("bonusEnabled", d.bonusEnabled),
            bonusLevel = o.optInt("bonusLevel", 0).coerceIn(0, 4),
            lastBonus = if (o.isNull("lastBonus")) null else ExerciseType.fromKey(o.optString("lastBonus")),
            calibration = o.optBoolean("calibration", d.calibration),
            holidayAutoUpdate = o.optBoolean("holidayAutoUpdate", d.holidayAutoUpdate),
            useDeviceCalendar = o.optBoolean("useDeviceCalendar", d.useDeviceCalendar),
        )
    } catch (e: JSONException) {
        AppSettings()
    }

    internal fun replace(ctx: Context, s: AppSettings) {
        update(ctx) { s }
    }
}

// ============================================================================ 공휴일·휴무일

/**
 * 쉬는 날 판단에 쓰는 데이터.
 *  - 앱 내장 공휴일 규칙 (KoreanHolidays)
 *  - 구글 공개 공휴일 달력에서 받아온 목록 (임시공휴일·선거일 반영)
 *  - 폰 기본 달력의 공휴일 달력에서 읽은 목록
 *  - 사용자가 직접 등록한 휴무일
 */
object HolidayRepository {
    private const val PREFS = "fit_alarm_holidays"
    private const val KEY_REMOTE = "remote"
    private const val KEY_REMOTE_AT = "remote_at"
    private const val KEY_REMOTE_STATUS = "remote_status"
    private const val KEY_DEVICE = "device"
    private const val KEY_DEVICE_AT = "device_at"
    private const val KEY_DEVICE_SOURCES = "device_sources"
    private const val KEY_DAYS_OFF = "days_off"

    class Snapshot(
        val remote: Map<LocalDate, String>,
        val remoteAt: Long,
        val remoteStatus: String,
        val device: Map<LocalDate, String>,
        val deviceAt: Long,
        val deviceSources: List<String>,
        val daysOff: Map<LocalDate, String>,
    )

    private val lock = Any()
    @Volatile private var cache: Snapshot? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(ctx: Context): Snapshot = cache ?: synchronized(lock) {
        cache ?: load(ctx).also { cache = it }
    }

    private fun load(ctx: Context): Snapshot {
        val p = prefs(ctx)
        return Snapshot(
            remote = readMap(p.getString(KEY_REMOTE, null)),
            remoteAt = p.getLong(KEY_REMOTE_AT, 0L),
            remoteStatus = p.getString(KEY_REMOTE_STATUS, "") ?: "",
            device = readMap(p.getString(KEY_DEVICE, null)),
            deviceAt = p.getLong(KEY_DEVICE_AT, 0L),
            deviceSources = readList(p.getString(KEY_DEVICE_SOURCES, null)),
            daysOff = readMap(p.getString(KEY_DAYS_OFF, null)),
        )
    }

    /** 공휴일 이름 (내장 규칙 우선), 아니면 null */
    fun holidayName(ctx: Context, date: LocalDate): String? {
        KoreanHolidays.nameOf(date)?.let { return it }
        val s = snapshot(ctx)
        return s.remote[date] ?: s.device[date]
    }

    fun dayOffMemo(ctx: Context, date: LocalDate): String? =
        snapshot(ctx).daysOff[date]?.let { it.ifBlank { "휴무일" } }

    /** 알람 옵션에 맞는 건너뛰기 규칙 */
    fun skipRuleFor(ctx: Context, alarm: Alarm): SkipRule? {
        if (!alarm.skipHolidays && !alarm.skipDaysOff) return null
        val app = ctx.applicationContext
        return SkipRule { date ->
            (if (alarm.skipHolidays) holidayName(app, date) else null)
                ?: (if (alarm.skipDaysOff) dayOffMemo(app, date) else null)
        }
    }

    /** from 부터 앞으로의 공휴일 (모든 출처 합침) */
    fun upcoming(ctx: Context, from: LocalDate, limit: Int): List<Holiday> {
        val s = snapshot(ctx)
        val map = sortedMapOf<LocalDate, String>()
        for (y in from.year..from.year + 2) {
            KoreanHolidays.forYear(y).forEach { map[it.date] = it.name }
        }
        s.device.forEach { (d, n) -> map.putIfAbsent(d, n) }
        s.remote.forEach { (d, n) -> map.putIfAbsent(d, n) }
        return map.filterKeys { !it.isBefore(from) }.entries.take(limit).map { Holiday(it.key, it.value) }
    }

    fun saveRemote(ctx: Context, list: List<Holiday>, status: String) = synchronized(lock) {
        prefs(ctx).edit()
            .putString(KEY_REMOTE, writeMap(list.associate { it.date to it.name }))
            .putLong(KEY_REMOTE_AT, System.currentTimeMillis())
            .putString(KEY_REMOTE_STATUS, status)
            .commit()
        cache = null
    }

    fun saveRemoteStatus(ctx: Context, status: String) = synchronized(lock) {
        prefs(ctx).edit().putString(KEY_REMOTE_STATUS, status).commit()
        cache = null
    }

    fun saveDevice(ctx: Context, list: List<Holiday>, sources: List<String>) = synchronized(lock) {
        prefs(ctx).edit()
            .putString(KEY_DEVICE, writeMap(list.associate { it.date to it.name }))
            .putLong(KEY_DEVICE_AT, System.currentTimeMillis())
            .putString(KEY_DEVICE_SOURCES, JSONArray(sources).toString())
            .commit()
        cache = null
    }

    fun addDayOff(ctx: Context, date: LocalDate, memo: String) = editDaysOff(ctx) { it[date] = memo.trim() }

    fun removeDayOff(ctx: Context, date: LocalDate) = editDaysOff(ctx) { it.remove(date) }

    internal fun replaceDaysOff(ctx: Context, days: Map<LocalDate, String>) = editDaysOff(ctx) {
        it.clear(); it.putAll(days)
    }

    private fun editDaysOff(ctx: Context, change: (MutableMap<LocalDate, String>) -> Unit) = synchronized(lock) {
        val m = snapshot(ctx).daysOff.toMutableMap()
        change(m)
        // 지난 휴무일은 30일 지나면 정리
        val cutoff = LocalDate.now().minusDays(30)
        m.keys.removeAll { it.isBefore(cutoff) }
        prefs(ctx).edit().putString(KEY_DAYS_OFF, writeMap(m)).commit()
        cache = null
    }

    private fun readMap(text: String?): Map<LocalDate, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = HashMap<LocalDate, String>()
            o.keys().forEach { k -> runCatching { out[LocalDate.parse(k)] = o.optString(k) } }
            out
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    private fun writeMap(m: Map<LocalDate, String>): String =
        JSONObject().apply { m.forEach { (d, n) -> put(d.toString(), n) } }.toString()

    private fun readList(text: String?): List<String> = try {
        if (text.isNullOrBlank()) emptyList() else JSONArray(text).let { a -> (0 until a.length()).map { a.optString(it) } }
    } catch (e: JSONException) {
        emptyList()
    }
}

// ============================================================================ 기록

object RecordRepository {
    private const val FILE = "records.jsonl"
    private val lock = Any()
    @Volatile private var cache: List<RecordEntry>? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun all(ctx: Context): List<RecordEntry> = cache ?: synchronized(lock) {
        cache ?: load(ctx).also { cache = it }
    }

    fun append(ctx: Context, entry: RecordEntry) = synchronized(lock) {
        try {
            file(ctx).appendText(toJson(entry).toString() + "\n")
        } catch (e: Exception) {
            // 기록 실패가 알람 동작을 막지 않도록 무시
        }
        cache = (cache ?: load(ctx)) + entry
    }

    internal fun replaceAll(ctx: Context, entries: List<RecordEntry>) = synchronized(lock) {
        file(ctx).writeText(entries.joinToString("") { toJson(it).toString() + "\n" })
        cache = entries
    }

    private fun load(ctx: Context): List<RecordEntry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { line -> if (line.isBlank()) null else fromJson(JSONObject(line)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun toJson(e: RecordEntry): JSONObject = JSONObject().apply {
        put("date", e.date.toString())
        put("kind", e.kind.key)
        put("reps", JSONObject().apply { e.reps.forEach { (t, r) -> put(t.key, r) } })
        put("ms", e.durationMs)
        put("alarm", e.alarmId)
        put("at", e.atMillis)
    }

    internal fun fromJson(o: JSONObject): RecordEntry? {
        val kind = RecordKind.fromKey(o.optString("kind")) ?: return null
        val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return null
        val reps = LinkedHashMap<ExerciseType, Int>()
        o.optJSONObject("reps")?.let { r -> r.keys().forEach { k -> ExerciseType.fromKey(k)?.let { reps[it] = r.optInt(k) } } }
        return RecordEntry(date, kind, reps, o.optLong("ms"), o.optInt("alarm"), o.optLong("at"))
    }
}

// ============================================================================ 개인 보정

object CalibrationRepository {
    private const val PREFS = "fit_alarm_calibration"
    private const val KEY = "book_v1"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): CalibrationBook {
        val book = CalibrationBook(enabled = SettingsRepository.get(ctx).calibration)
        book.restore(read(prefs(ctx).getString(KEY, null)))
        book.dirty = false
        return book
    }

    fun save(ctx: Context, book: CalibrationBook) {
        if (!book.dirty) return
        prefs(ctx).edit().putString(KEY, write(book.snapshot())).commit()
        book.dirty = false
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).commit()
    }

    fun learnedCount(ctx: Context): Int = read(prefs(ctx).getString(KEY, null)).count { it.value.second >= 3 }

    internal fun exportJson(ctx: Context): JSONObject = JSONObject(prefs(ctx).getString(KEY, null) ?: "{}")

    internal fun importJson(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString(KEY, o.toString()).commit()
    }

    private fun read(text: String?): Map<String, Pair<Float, Int>> = try {
        if (text.isNullOrBlank()) emptyMap() else {
            val o = JSONObject(text)
            val out = HashMap<String, Pair<Float, Int>>()
            o.keys().forEach { k ->
                val a = o.optJSONArray(k) ?: return@forEach
                out[k] = a.optDouble(0).toFloat() to a.optInt(1)
            }
            out
        }
    } catch (e: JSONException) {
        emptyMap()
    }

    private fun write(m: Map<String, Pair<Float, Int>>): String =
        JSONObject().apply { m.forEach { (k, v) -> put(k, JSONArray().put(v.first.toDouble()).put(v.second)) } }.toString()
}

package com.fitalarm.app.data

import android.content.Context
import android.content.SharedPreferences
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.Days
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.RoutineStep
import com.fitalarm.app.core.VibrationPattern
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 알람 목록 저장소 (SharedPreferences + JSON).
 * 데이터가 수 KB 수준이라 DB 대신 단순 저장을 사용하고,
 * 리시버/서비스에서 쓴 직후 프로세스가 죽어도 남도록 commit()(동기 기록)을 씁니다.
 */
object AlarmRepository {
    private const val PREFS = "fit_alarm_store"
    private const val KEY_ALARMS = "alarms_v1"
    private const val KEY_NEXT_ID = "next_id"
    private val lock = Any()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(ctx: Context): List<Alarm> = synchronized(lock) { load(ctx) }
        .sortedWith(compareBy<Alarm>({ it.hour }, { it.minute }, { it.id }))

    fun get(ctx: Context, id: Int): Alarm? = synchronized(lock) { load(ctx).firstOrNull { it.id == id } }

    /** id 가 0 이면 새 id 를 부여해 추가, 아니면 교체. 저장된 알람을 반환 */
    fun upsert(ctx: Context, alarm: Alarm): Alarm = synchronized(lock) {
        val p = prefs(ctx)
        val list = load(ctx).toMutableList()
        var saved = alarm
        var nextId = p.getInt(KEY_NEXT_ID, 1)
        if (saved.id <= 0) {
            saved = saved.copy(id = nextId)
            nextId++
        }
        val idx = list.indexOfFirst { it.id == saved.id }
        if (idx >= 0) list[idx] = saved else list.add(saved)
        p.edit()
            .putString(KEY_ALARMS, JSONArray().apply { list.forEach { put(toJson(it)) } }.toString())
            .putInt(KEY_NEXT_ID, nextId)
            .commit()
        saved
    }

    fun delete(ctx: Context, id: Int) = synchronized(lock) {
        val list = load(ctx).filterNot { it.id == id }
        prefs(ctx).edit()
            .putString(KEY_ALARMS, JSONArray().apply { list.forEach { put(toJson(it)) } }.toString())
            .commit()
    }

    private fun load(ctx: Context): List<Alarm> {
        val text = prefs(ctx).getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** 전체 교체 (백업 복원용) */
    fun replaceAll(ctx: Context, alarms: List<Alarm>) = synchronized(lock) {
        val nextId = (alarms.maxOfOrNull { it.id } ?: 0) + 1
        prefs(ctx).edit()
            .putString(KEY_ALARMS, JSONArray().apply { alarms.forEach { put(toJson(it)) } }.toString())
            .putInt(KEY_NEXT_ID, nextId)
            .commit()
    }

    internal fun toJson(a: Alarm): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("hour", a.hour)
        put("minute", a.minute)
        put("days", a.days)
        put("enabled", a.enabled)
        put("label", a.label)
        put("soundUri", a.soundUri ?: JSONObject.NULL)
        put("soundTitle", a.soundTitle)
        put("silent", a.silent)
        put("volume", a.volume)
        put("crescendo", a.crescendo)
        put("vibrate", a.vibrate)
        put("vibrationPattern", a.vibrationPattern.key)
        put("snoozeEnabled", a.snoozeEnabled)
        put("snoozeMinutes", a.snoozeMinutes)
        put("snoozeLimit", a.snoozeLimit)
        put("ringMinutes", a.ringMinutes)
        put("missionEnabled", a.missionEnabled)
        put("routine", JSONArray().apply {
            a.routine.forEach { put(JSONObject().put("type", it.type.key).put("reps", it.reps)) }
        })
        put("skipHolidays", a.skipHolidays)
        put("skipDaysOff", a.skipDaysOff)
        put("announce", a.announce)
        put("snoozeCount", a.snoozeCount)
        put("snoozedUntil", a.snoozedUntil)
    }

    internal fun fromJson(o: JSONObject): Alarm? {
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        val d = Alarm()
        val routineArr = o.optJSONArray("routine")
        val routine = if (routineArr == null) emptyList() else (0 until routineArr.length()).mapNotNull { i ->
            val r = routineArr.optJSONObject(i) ?: return@mapNotNull null
            val type = ExerciseType.fromKey(r.optString("type")) ?: return@mapNotNull null
            val reps = r.optInt("reps", type.defaultReps).coerceIn(RoutineStep.MIN_REPS, RoutineStep.MAX_REPS)
            RoutineStep(type, reps)
        }
        return Alarm(
            id = id,
            hour = o.optInt("hour", d.hour).coerceIn(0, 23),
            minute = o.optInt("minute", d.minute).coerceIn(0, 59),
            days = o.optInt("days", d.days) and Days.EVERYDAY,
            enabled = o.optBoolean("enabled", d.enabled),
            label = o.optString("label", d.label),
            soundUri = if (o.isNull("soundUri")) null else o.optString("soundUri").ifBlank { null },
            soundTitle = o.optString("soundTitle", d.soundTitle),
            silent = o.optBoolean("silent", d.silent),
            volume = o.optInt("volume", d.volume).coerceIn(0, 100),
            crescendo = o.optBoolean("crescendo", d.crescendo),
            vibrate = o.optBoolean("vibrate", d.vibrate),
            vibrationPattern = VibrationPattern.fromKey(o.optString("vibrationPattern")),
            snoozeEnabled = o.optBoolean("snoozeEnabled", d.snoozeEnabled),
            snoozeMinutes = o.optInt("snoozeMinutes", d.snoozeMinutes).coerceIn(1, 60),
            snoozeLimit = o.optInt("snoozeLimit", d.snoozeLimit),
            ringMinutes = o.optInt("ringMinutes", d.ringMinutes).coerceAtLeast(0),
            missionEnabled = o.optBoolean("missionEnabled", d.missionEnabled),
            routine = routine,
            skipHolidays = o.optBoolean("skipHolidays", d.skipHolidays),
            skipDaysOff = o.optBoolean("skipDaysOff", d.skipDaysOff),
            announce = o.optBoolean("announce", d.announce),
            snoozeCount = o.optInt("snoozeCount", 0).coerceAtLeast(0),
            snoozedUntil = o.optLong("snoozedUntil", 0L).coerceAtLeast(0L),
        )
    }
}

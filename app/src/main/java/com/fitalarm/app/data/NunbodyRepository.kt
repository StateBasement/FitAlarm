package com.fitalarm.app.data

import android.content.Context
import android.content.SharedPreferences
import com.fitalarm.app.core.NunbodyNames
import com.fitalarm.app.core.NunbodyPhoto
import com.fitalarm.app.core.PhotoSource
import com.fitalarm.app.core.QualityTier
import com.fitalarm.app.core.ReminderPeriod
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** 눈바디 설정. 앱 전체 설정과 따로 저장 (백업 파일에도 넣지 않음: 사진이 없는 설정만 옮겨지는 것을 피함) */
data class NunbodySettings(
    /** 운동 미션 안의 선택 기능. 기본 꺼짐 */
    val enabled: Boolean = false,
    val lock: Boolean = true,
    val captureBlock: Boolean = true,
    val blur: Boolean = true,
    /** 찍을 때 폰 기본 카메라 앱 사용 */
    val useSystemCamera: Boolean = false,
    val quality: QualityTier = QualityTier.MAX,
    val front: Boolean = false,
    val ghost: Boolean = true,
    val timerSec: Int = 5,
    val reminderOn: Boolean = false,
    val reminderPeriod: ReminderPeriod = ReminderPeriod.MONTHLY,
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    /** 2주마다 알림의 기준일 (알림을 켠 날) */
    val reminderAnchor: LocalDate? = null,
    /** 화면 잠금이 없는 폰 경고를 이 시각까지 보이지 않음 */
    val noLockWarnUntil: Long = 0L,
)

/**
 * 눈바디 사진과 설정.
 * 사진은 앱 전용 저장 공간(files/nunbody)에만 저장: 갤러리·다른 앱에서 보이지 않고, 구글 클라우드 백업에서도 제외(backup_rules).
 * 사용자가 직접 내보낼 때만 앱 밖으로 나감 (NunbodyExport).
 */
object NunbodyRepository {
    private const val PREFS = "fit_alarm_nunbody"
    private const val KEY = "settings_v1"
    private const val DIR = "nunbody"
    private const val INDEX = "index.json"

    private val lock = Any()
    @Volatile private var settingsCache: NunbodySettings? = null
    @Volatile private var photosCache: List<NunbodyPhoto>? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 설정

    fun settings(ctx: Context): NunbodySettings = settingsCache ?: synchronized(lock) {
        settingsCache ?: (prefs(ctx).getString(KEY, null)?.let { fromJson(it) } ?: NunbodySettings()).also { settingsCache = it }
    }

    fun update(ctx: Context, change: (NunbodySettings) -> NunbodySettings): NunbodySettings = synchronized(lock) {
        val next = change(settings(ctx))
        prefs(ctx).edit().putString(KEY, toJson(next).toString()).commit()
        settingsCache = next
        next
    }

    fun isEnabled(ctx: Context): Boolean = settings(ctx).enabled

    private fun toJson(s: NunbodySettings) = JSONObject().apply {
        put("enabled", s.enabled)
        put("lock", s.lock)
        put("captureBlock", s.captureBlock)
        put("blur", s.blur)
        put("useSystemCamera", s.useSystemCamera)
        put("quality", s.quality.key)
        put("front", s.front)
        put("ghost", s.ghost)
        put("timerSec", s.timerSec)
        put("reminderOn", s.reminderOn)
        put("reminderPeriod", s.reminderPeriod.key)
        put("reminderHour", s.reminderHour)
        put("reminderMinute", s.reminderMinute)
        put("reminderAnchor", s.reminderAnchor?.toString() ?: JSONObject.NULL)
        put("noLockWarnUntil", s.noLockWarnUntil)
    }

    private fun fromJson(text: String): NunbodySettings = try {
        val o = JSONObject(text)
        val d = NunbodySettings()
        NunbodySettings(
            enabled = o.optBoolean("enabled", d.enabled),
            lock = o.optBoolean("lock", d.lock),
            captureBlock = o.optBoolean("captureBlock", d.captureBlock),
            blur = o.optBoolean("blur", d.blur),
            useSystemCamera = o.optBoolean("useSystemCamera", d.useSystemCamera),
            quality = QualityTier.fromKey(o.optString("quality")),
            front = o.optBoolean("front", d.front),
            ghost = o.optBoolean("ghost", d.ghost),
            timerSec = o.optInt("timerSec", d.timerSec).let { if (it in TIMERS) it else d.timerSec },
            reminderOn = o.optBoolean("reminderOn", d.reminderOn),
            reminderPeriod = ReminderPeriod.fromKey(o.optString("reminderPeriod")),
            reminderHour = o.optInt("reminderHour", d.reminderHour).coerceIn(0, 23),
            reminderMinute = o.optInt("reminderMinute", d.reminderMinute).coerceIn(0, 59),
            reminderAnchor = if (o.isNull("reminderAnchor")) null else runCatching { LocalDate.parse(o.optString("reminderAnchor")) }.getOrNull(),
            noLockWarnUntil = o.optLong("noLockWarnUntil", 0L),
        )
    } catch (e: JSONException) {
        NunbodySettings()
    }

    val TIMERS = listOf(0, 3, 5, 10)

    // ------------------------------------------------------------------ 사진

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    fun file(ctx: Context, p: NunbodyPhoto): File = File(dir(ctx), p.id)

    /** 최근에 찍은 순 */
    fun photos(ctx: Context): List<NunbodyPhoto> = photosCache ?: synchronized(lock) {
        photosCache ?: load(ctx).also { photosCache = it }
    }

    fun get(ctx: Context, id: String): NunbodyPhoto? = photos(ctx).firstOrNull { it.id == id }

    fun totalBytes(ctx: Context): Long = photos(ctx).sumOf { it.bytes }

    /** 찍은 사진(임시 파일)을 앱 저장 공간으로 옮기고 기록에 추가 */
    fun add(
        ctx: Context,
        temp: File,
        takenAt: LocalDateTime,
        width: Int,
        height: Int,
        source: PhotoSource,
        weightKg: Double?,
        heightCm: Double?,
    ): NunbodyPhoto = synchronized(lock) {
        val current = photos(ctx)
        val name = NunbodyNames.photo(takenAt, current.map { it.id }.toSet())
        val dest = File(dir(ctx), name)
        if (!temp.renameTo(dest)) {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }
        val p = NunbodyPhoto(name, takenAt, width, height, source, weightKg, heightCm, "", dest.length())
        save(ctx, current + p)
        p
    }

    fun update(ctx: Context, photo: NunbodyPhoto) = synchronized(lock) {
        save(ctx, photos(ctx).map { if (it.id == photo.id) photo else it })
    }

    fun delete(ctx: Context, photo: NunbodyPhoto) = synchronized(lock) {
        file(ctx, photo).delete()
        save(ctx, photos(ctx).filterNot { it.id == photo.id })
    }

    fun deleteAll(ctx: Context) = synchronized(lock) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        photosCache = emptyList()
    }

    private fun save(ctx: Context, list: List<NunbodyPhoto>) {
        val sorted = list.sortedByDescending { it.takenAt }
        val arr = JSONArray()
        sorted.forEach { arr.put(toJson(it)) }
        val f = File(dir(ctx), INDEX)
        val tmp = File(dir(ctx), "$INDEX.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(arr.toString())
            tmp.delete()
        }
        photosCache = sorted
    }

    private fun load(ctx: Context): List<NunbodyPhoto> {
        val f = File(dir(ctx), INDEX)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { fromPhotoJson(it) } }
                .filter { File(dir(ctx), it.id).exists() }   // 파일이 지워진 기록은 숨김
                .sortedByDescending { it.takenAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toJson(p: NunbodyPhoto) = JSONObject().apply {
        put("id", p.id)
        put("at", p.takenAt.toString())
        put("w", p.width)
        put("h", p.height)
        put("src", p.source.key)
        put("kg", p.weightKg ?: JSONObject.NULL)
        put("cm", p.heightCm ?: JSONObject.NULL)
        put("memo", p.memo)
        put("bytes", p.bytes)
    }

    private fun fromPhotoJson(o: JSONObject): NunbodyPhoto? {
        val id = o.optString("id")
        if (!NunbodyNames.isSafeName(id)) return null
        val at = runCatching { LocalDateTime.parse(o.optString("at")) }.getOrNull() ?: return null
        return NunbodyPhoto(
            id = id,
            takenAt = at,
            width = o.optInt("w"),
            height = o.optInt("h"),
            source = PhotoSource.fromKey(o.optString("src")),
            weightKg = if (o.isNull("kg")) null else o.optDouble("kg").takeIf { !it.isNaN() },
            heightCm = if (o.isNull("cm")) null else o.optDouble("cm").takeIf { !it.isNaN() },
            memo = o.optString("memo"),
            bytes = o.optLong("bytes"),
        )
    }

    // ------------------------------------------------------------------ 임시 파일

    /** 촬영 직후·기본 카메라 앱이 쓰는 임시 파일 (저장하면 nunbody 폴더로 옮김) */
    fun captureFile(ctx: Context): File =
        File(File(ctx.applicationContext.cacheDir, CAPTURE_DIR).apply { mkdirs() }, CAPTURE_NAME)

    /** 공유할 때 복사해 두는 폴더 (공유할 때마다 비움) */
    fun shareDir(ctx: Context): File = File(ctx.applicationContext.cacheDir, SHARE_DIR).apply { mkdirs() }

    const val CAPTURE_DIR = "nunbody_capture"
    const val CAPTURE_NAME = "capture.jpg"
    const val SHARE_DIR = "nunbody_share"
}

package com.fitalarm.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 설정 백업/복원: 알람, 앱 설정, 내 휴무일, 개인 보정, 기록을 JSON 파일 하나로.
 * 알람음으로 고른 "내 음악 파일"은 다른 폰에서는 접근 권한이 없으므로 기본 알람음으로 대체됩니다.
 */
object BackupManager {
    private const val VERSION = 2

    data class Summary(val alarms: Int, val records: Int, val daysOff: Int)

    fun export(ctx: Context, uri: Uri): Summary {
        val alarms = AlarmRepository.getAll(ctx)
        val records = RecordRepository.all(ctx)
        val daysOff = HolidayRepository.snapshot(ctx).daysOff
        val root = JSONObject().apply {
            put("app", "fitalarm")
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("alarms", JSONArray().apply { alarms.forEach { put(AlarmRepository.toJson(it)) } })
            put("settings", SettingsRepository.toJson(SettingsRepository.get(ctx)))
            put("daysOff", JSONObject().apply { daysOff.forEach { (d, m) -> put(d.toString(), m) } })
            put("calibration", CalibrationRepository.exportJson(ctx))
            put("records", JSONArray().apply { records.forEach { put(RecordRepository.toJson(it)) } })
        }
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("파일을 열 수 없습니다")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        return Summary(alarms.size, records.size, daysOff.size)
    }

    /** 파일을 읽어 검증만 함 (복원 전 확인 대화상자용) */
    fun inspect(ctx: Context, uri: Uri): Pair<JSONObject, Summary> {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("파일을 열 수 없습니다")
        val root = JSONObject(text)
        require(root.optString("app") == "fitalarm") { "알람핏 백업 파일이 아닙니다" }
        val summary = Summary(
            root.optJSONArray("alarms")?.length() ?: 0,
            root.optJSONArray("records")?.length() ?: 0,
            root.optJSONObject("daysOff")?.length() ?: 0,
        )
        return root to summary
    }

    /** 현재 데이터를 모두 백업 내용으로 교체. 호출한 쪽에서 알람 재등록 */
    fun restore(ctx: Context, root: JSONObject) {
        val alarmsArr = root.optJSONArray("alarms") ?: JSONArray()
        val alarms = (0 until alarmsArr.length()).mapNotNull { i ->
            alarmsArr.optJSONObject(i)?.let { AlarmRepository.fromJson(it) }
        }.map { it.copy(snoozedUntil = 0L, snoozeCount = 0) }
        AlarmRepository.replaceAll(ctx, alarms)

        root.optJSONObject("settings")?.let { SettingsRepository.replace(ctx, SettingsRepository.fromJson(it.toString())) }

        val days = HashMap<LocalDate, String>()
        root.optJSONObject("daysOff")?.let { o ->
            o.keys().forEach { k -> runCatching { days[LocalDate.parse(k)] = o.optString(k) } }
        }
        HolidayRepository.replaceDaysOff(ctx, days)

        root.optJSONObject("calibration")?.let { CalibrationRepository.importJson(ctx, it) }

        val recArr = root.optJSONArray("records") ?: JSONArray()
        val records = (0 until recArr.length()).mapNotNull { i -> recArr.optJSONObject(i)?.let { RecordRepository.fromJson(it) } }
        RecordRepository.replaceAll(ctx, records)
    }
}

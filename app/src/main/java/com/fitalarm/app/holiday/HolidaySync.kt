package com.fitalarm.app.holiday

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.core.Holiday
import com.fitalarm.app.core.HolidayNames
import com.fitalarm.app.core.IcsHolidayParser
import com.fitalarm.app.data.HolidayRepository
import com.fitalarm.app.data.SettingsRepository
import com.fitalarm.app.systemService
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 공휴일 목록 동기화.
 *  1) 구글 공개 "대한민국의 휴일" 달력(.ics) — 인터넷, 키 불필요. 임시공휴일·선거일이 지정되면 반영
 *  2) 폰 기본 달력에 있는 공휴일 달력 — 캘린더 읽기 권한. 공휴일 달력만 골라 읽고 개인 일정은 보지 않음
 * 둘 다 실패해도 앱 내장 규칙(KoreanHolidays)으로 동작.
 */
object HolidaySync {
    private const val GOOGLE_ICS =
        "https://calendar.google.com/calendar/ical/ko.south_korea%23holiday%40group.v.calendar.google.com/public/basic.ics"
    private const val JOB_ID = 4201
    private const val STALE_MS = 20 * 60 * 60 * 1000L
    private const val MAX_BYTES = 3 * 1024 * 1024

    private val HOLIDAY_CALENDAR = Regex("holiday|휴일|공휴일|legal", RegexOption.IGNORE_CASE)

    data class Result(val remoteCount: Int?, val deviceCount: Int?, val message: String)

    private val main = Handler(Looper.getMainLooper())

    /** 블로킹 호출 (백그라운드 스레드에서) */
    fun syncNow(ctx: Context): Result {
        val app = ctx.applicationContext
        val settings = SettingsRepository.get(app)
        var remoteCount: Int? = null
        var deviceCount: Int? = null
        val msgs = ArrayList<String>()

        if (settings.holidayAutoUpdate) {
            try {
                val list = fetchGoogle().filter { inWindow(it.date) }
                if (list.isNotEmpty()) {
                    HolidayRepository.saveRemote(app, list, "ok")
                    remoteCount = list.size
                } else {
                    HolidayRepository.saveRemoteStatus(app, "empty")
                    msgs.add("구글 달력에서 공휴일을 찾지 못했어요")
                }
            } catch (e: Exception) {
                HolidayRepository.saveRemoteStatus(app, "error")
                msgs.add("구글 달력을 받아오지 못했어요 (인터넷 연결 확인)")
            }
        }

        if (settings.useDeviceCalendar && hasCalendarPermission(app)) {
            try {
                val (list, sources) = readDeviceCalendars(app)
                HolidayRepository.saveDevice(app, list, sources)
                deviceCount = list.size
            } catch (e: Exception) {
                msgs.add("폰 달력을 읽지 못했어요")
            }
        }

        AlarmScheduler(app).rescheduleAll()
        return Result(remoteCount, deviceCount, msgs.joinToString("\n"))
    }

    fun syncAsync(ctx: Context, onDone: ((Result) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val r = try {
                syncNow(app)
            } catch (e: Exception) {
                Result(null, null, "동기화 중 오류가 발생했어요")
            }
            if (onDone != null) main.post { onDone(r) }
        }.start()
    }

    /** 마지막 동기화가 오래됐으면 백그라운드로 갱신 (앱 열 때) */
    fun syncIfStale(ctx: Context) {
        val s = HolidayRepository.snapshot(ctx)
        val now = System.currentTimeMillis()
        if (now - s.remoteAt > STALE_MS || now - s.deviceAt > STALE_MS) syncAsync(ctx)
    }

    fun hasCalendarPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 하루 한 번, 인터넷 연결 시 동기화 (재부팅 후에도 유지) */
    fun schedulePeriodic(ctx: Context) {
        val js = ctx.systemService<JobScheduler>()
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, HolidaySyncJob::class.java))
            .setPeriodic(24 * 60 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        try {
            js.schedule(job)
        } catch (e: Exception) {
            // 예약 실패 시 앱을 열 때마다 갱신하는 것으로 대체
        }
    }

    private fun inWindow(d: LocalDate): Boolean {
        val today = LocalDate.now()
        return !d.isBefore(today.minusDays(7)) && d.isBefore(today.plusDays(800))
    }

    private fun fetchGoogle(): List<Holiday> {
        val conn = URL(GOOGLE_ICS).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "text/calendar")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) throw IllegalStateException("too large")
                }
            }
            return IcsHolidayParser.parse(buf.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }

    /** 폰 달력 중 "공휴일 달력"으로 보이는 것만 골라 그 일정만 읽음 */
    private fun readDeviceCalendars(ctx: Context): Pair<List<Holiday>, List<String>> {
        val cr = ctx.contentResolver
        val ids = ArrayList<Long>()
        val names = ArrayList<String>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.NAME,
        )
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val fields = (1 until proj.size).map { c.getString(it).orEmpty() }
                if (fields.any { HOLIDAY_CALENDAR.containsMatchIn(it) }) {
                    ids.add(c.getLong(0))
                    names.add(fields[0].ifBlank { fields[4] }.ifBlank { "공휴일 달력" })
                }
            }
        }
        if (ids.isEmpty()) return emptyList<Holiday>() to emptyList()

        val today = LocalDate.now()
        val begin = today.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = today.plusDays(800).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val out = ArrayList<Holiday>()
        cr.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DESCRIPTION,
            ),
            "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0).orEmpty()
                val beginMs = c.getLong(1)
                val allDay = c.getInt(2) != 0
                val desc = c.getString(3)
                if (!HolidayNames.isDayOff(title, desc)) continue
                // 종일 일정의 시작 시각은 UTC 자정
                val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                out.add(Holiday(Instant.ofEpochMilli(beginMs).atZone(zone).toLocalDate(), title))
            }
        }
        return out.distinctBy { it.date } to names.distinct()
    }
}

class HolidaySyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                HolidaySync.syncNow(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

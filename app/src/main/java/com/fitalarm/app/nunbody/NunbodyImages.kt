package com.fitalarm.app.nunbody

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.fitalarm.app.core.ExifMath
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 눈바디 사진 읽기·작은 사진 만들기. 원본(최대 1200만 화소 이상)을 그대로 띄우면 메모리가 모자라므로
 * 항상 필요한 크기로 줄여서 읽고, 사진의 방향(EXIF)을 반영해 바로 세움.
 */
object NunbodyImages {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** 가리기(흐리게): 아주 작게 줄인 사진을 크게 늘려 보여 주면 알아볼 수 없을 만큼 흐려짐 */
    private const val BLUR_SIDE = 20

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun orientation(file: File): Int = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 바로 세웠을 때의 가로·세로 */
    fun uprightSize(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        val rot = ExifMath.rotationOf(orientation(file))
        return if (rot % 180 == 90) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    /** 긴 변이 longSide 보다 조금 크거나 같게 줄여서 읽음 */
    fun decode(file: File, longSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= longSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = try {
            BitmapFactory.decodeFile(file.path, opts)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        val longNow = maxOf(bmp.width, bmp.height)
        if (longNow > longSide * 1.5f) {
            val scale = longSide.toFloat() / longNow
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        }
        return upright(bmp, orientation(file))
    }

    /** EXIF 방향값대로 돌리고 뒤집기 (8가지 모두) */
    private fun upright(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: OutOfMemoryError) {
            src
        }
    }

    /**
     * 목록용 비동기 읽기. 재사용되는 ImageView 에 늦게 도착한 다른 사진이 들어가지 않도록 tag 로 확인.
     * blur=true 면 알아볼 수 없게 흐린 사진.
     */
    fun load(view: ImageView, file: File, longSide: Int, blur: Boolean) {
        val side = if (blur) BLUR_SIDE else longSide
        val key = file.path + "@" + side + "@" + file.lastModified()
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        worker.execute {
            val bmp = decode(file, side)
            if (bmp != null) cache.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    /** 무거운 작업을 백그라운드에서, 결과는 메인 스레드로 */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        worker.execute {
            val r = work()
            main.post { done(r) }
        }
    }

    fun clearCache() = cache.evictAll()

    // ------------------------------------------------------------------ EXIF 정리

    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

    /** 사진에 담긴 위치(GPS) 정보를 모두 지움. 몸 사진에 집 위치가 붙어 나가지 않게 */
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_VERSION_ID,
    )

    /**
     * 저장 직전 정리: 위치 정보 삭제, 찍은 시각 기록, (전면 카메라면) 화면에 보이던 모습 그대로 좌우 반전.
     * 반전은 방향값만 바꾸므로 화질 손실이 없음.
     */
    fun finalizeCapture(file: File, takenAt: LocalDateTime, mirror: Boolean): Boolean = try {
        val exif = ExifInterface(file.path)
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        val stamp = EXIF_TIME.format(takenAt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        if (mirror) {
            val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifMath.mirrored(o).toString())
        }
        exif.saveAttributes()
        true
    } catch (e: Exception) {
        false
    }

    /** 사진에 적힌 찍은 시각 (없으면 null) */
    fun takenAt(file: File): LocalDateTime? = try {
        val t = ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME)
        t?.let { LocalDateTime.parse(it.trim(), EXIF_TIME) }
    } catch (e: Exception) {
        null
    }
}

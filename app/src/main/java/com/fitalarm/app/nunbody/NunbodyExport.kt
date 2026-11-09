package com.fitalarm.app.nunbody

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fitalarm.app.core.NunbodyCsv
import com.fitalarm.app.core.NunbodyNames
import com.fitalarm.app.core.NunbodyPhoto
import com.fitalarm.app.data.NunbodyRepository
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 사용자가 고른 사진을 앱 밖으로 내보내기.
 *  - 갤러리에 저장: 'Pictures/알람핏' 앨범에 낱개 파일로, 찍은 순서대로. 갤러리의 '찍은 날짜'는 원래 찍은 시각
 *  - 보내기: 공유 창 한 번으로 여러 장 (카톡·메시지·메일 등)
 *  - 기록 표(CSV): 갤러리에는 사진만 들어가므로 '다운로드/알람핏' 폴더에
 * 이미 내보낸 사진(같은 이름)은 건너뜀. 위치 정보는 저장할 때 이미 지워 둠.
 */
object NunbodyExport {
    const val ALBUM = "알람핏"

    class Result(val saved: Int, val skipped: Int, val failed: Int, val tableSaved: Boolean)

    class Table(val name: String, val content: String)

    fun table(photos: List<NunbodyPhoto>, from: LocalDate, to: LocalDate) =
        Table(NunbodyNames.table(from, to), NunbodyCsv.build(photos))

    /** Android 9 이하는 공용 사진 폴더에 쓰려면 저장소 권한이 필요 */
    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 백그라운드 스레드에서 호출 */
    fun saveToGallery(ctx: Context, photos: List<NunbodyPhoto>, table: Table?): Result {
        var saved = 0
        var skipped = 0
        var failed = 0
        for (p in photos.sortedBy { it.takenAt }) {
            val src = NunbodyRepository.file(ctx, p)
            if (!src.exists()) {
                failed++
                continue
            }
            val taken = p.takenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            when (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveImageQ(ctx, src, p.id, taken) else saveImageLegacy(ctx, src, p.id)) {
                Outcome.SAVED -> saved++
                Outcome.EXISTS -> skipped++
                Outcome.FAILED -> failed++
            }
        }
        val tableOk = table?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveTableQ(ctx, it) else saveTableLegacy(ctx, it)
        } ?: false
        return Result(saved, skipped, failed, tableOk)
    }

    private enum class Outcome { SAVED, EXISTS, FAILED }

    private fun saveImageQ(ctx: Context, src: File, name: String, takenMs: Long): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Outcome.FAILED
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_PICTURES}/$ALBUM/"
        try {
            resolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, relative), null,
            )?.use { if (it.moveToFirst()) return Outcome.EXISTS }
        } catch (e: Exception) {
            // 확인할 수 없으면 그냥 저장 (같은 이름이면 갤러리가 (1)을 붙임)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.DATE_TAKEN, takenMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        return try {
            uri = resolver.insert(collection, values) ?: return Outcome.FAILED
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Outcome.SAVED
        } catch (e: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            Outcome.FAILED
        }
    }

    @Suppress("DEPRECATION")
    private fun saveImageLegacy(ctx: Context, src: File, name: String): Outcome = try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        val dest = File(dir, name)
        if (dest.exists()) Outcome.EXISTS else {
            src.copyTo(dest)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf("image/jpeg"), null)
            Outcome.SAVED
        }
    } catch (e: Exception) {
        Outcome.FAILED
    }

    private fun saveTableQ(ctx: Context, t: Table): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, t.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        return try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(t.content.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveTableLegacy(ctx: Context, t: Table): Boolean = try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ALBUM).apply { mkdirs() }
        var dest = File(dir, t.name)
        var n = 2
        while (dest.exists()) dest = File(dir, t.name.removeSuffix(".csv") + " ($n).csv").also { n++ }
        dest.writeText(t.content, Charsets.UTF_8)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf("text/csv"), null)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 공유 창 준비 (백그라운드 스레드에서 호출). 내보낼 사진만 공유용 폴더에 복사해 둔 뒤
     * 그 파일들에 대해서만 받는 앱이 읽을 수 있게 권한을 줌. 지난 복사본은 1시간 뒤 정리
     * (받는 앱이 아직 읽는 중일 수 있어 바로 지우지 않음).
     */
    fun shareIntent(ctx: Context, photos: List<NunbodyPhoto>, table: Table?): Intent? {
        cleanupShared(ctx)
        val dir = NunbodyRepository.shareDir(ctx)
        val uris = ArrayList<Uri>()
        for (p in photos.sortedBy { it.takenAt }) {
            val src = NunbodyRepository.file(ctx, p)
            if (!src.exists()) continue
            try {
                src.copyTo(File(dir, p.id), overwrite = true)
                uris.add(NunbodyFileProvider.shareUri(ctx, p.id))
            } catch (e: Exception) {
                // 복사 실패한 사진은 빼고 보냄
            }
        }
        if (table != null) {
            try {
                File(dir, table.name).writeText(table.content, Charsets.UTF_8)
                uris.add(NunbodyFileProvider.shareUri(ctx, table.name))
            } catch (e: Exception) {
                // 표 없이 보냄
            }
        }
        if (uris.isEmpty()) return null
        val onlyImages = table == null
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = if (onlyImages) "image/jpeg" else if (uris.size == 1) "text/csv" else "*/*"
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "보내기").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = clip }
    }

    /** 앱을 켤 때 지난 공유용 복사본 정리 */
    fun cleanupShared(ctx: Context) {
        NunbodyRepository.shareDir(ctx).listFiles()?.forEach { f ->
            if (System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000L) f.delete()
        }
    }
}

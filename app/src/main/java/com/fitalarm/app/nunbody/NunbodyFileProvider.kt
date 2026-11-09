package com.fitalarm.app.nunbody

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.fitalarm.app.core.NunbodyNames
import com.fitalarm.app.data.NunbodyRepository
import java.io.File
import java.io.FileNotFoundException

/**
 * 다른 앱과 파일을 주고받는 최소한의 통로 (AndroidX FileProvider 대신).
 *  - share/<이름>: 사용자가 '보내기'로 고른 사진·기록 표 (읽기만)
 *  - capture/capture.jpg: 폰 기본 카메라 앱이 찍은 사진을 써 넣는 임시 파일 (쓰기 허용)
 * 외부에 공개(exported)하지 않고, 앱이 넘겨준 주소에 대해서만 그때그때 권한을 줌(grantUriPermissions).
 */
class NunbodyFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): Pair<File, Boolean>? {
        val ctx = context ?: return null
        val segs = uri.pathSegments
        if (segs.size != 2) return null
        val name = segs[1]
        if (!NunbodyNames.isSafeName(name)) return null
        return when (segs[0]) {
            SHARE -> File(NunbodyRepository.shareDir(ctx), name) to false
            CAPTURE -> if (name == NunbodyRepository.CAPTURE_NAME) NunbodyRepository.captureFile(ctx) to true else null
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (file, writable) = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        val wantsWrite = mode.contains('w') || mode.contains('a')
        if (wantsWrite && !writable) throw SecurityException("read only")
        if (!wantsWrite && !file.exists()) throw FileNotFoundException(uri.toString())
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val (file, _) = resolve(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }.toTypedArray<Any?>()
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "csv" -> "text/csv"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val SHARE = "share"
        private const val CAPTURE = "capture"

        fun authority(ctx: Context): String = ctx.packageName + ".nunbody"

        fun shareUri(ctx: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(SHARE).appendPath(name).build()

        fun captureUri(ctx: Context): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(CAPTURE).appendPath(NunbodyRepository.CAPTURE_NAME).build()
    }
}

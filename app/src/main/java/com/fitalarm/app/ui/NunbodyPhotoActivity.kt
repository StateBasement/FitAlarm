package com.fitalarm.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.BodyNumbers
import com.fitalarm.app.core.NunbodyPhoto
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.monetize.ExportGate
import com.fitalarm.app.nunbody.NunbodyExport
import com.fitalarm.app.nunbody.NunbodyImages
import java.time.format.TextStyle
import java.util.Locale

/** 사진 한 장: 크게 보기, 체중·키·메모 편집, 내보내기(갤러리·보내기·기간), 삭제 */
class NunbodyPhotoActivity : NunbodyBaseActivity() {
    private lateinit var photo: NunbodyPhoto
    private lateinit var subtitle: TextView
    private lateinit var memo: TextView
    private lateinit var memoWrap: View
    private var pendingGallery = false

    override fun onUnlocked() {
        val p = intent.getStringExtra(EXTRA_ID)?.let { NunbodyRepository.get(this, it) }
        if (p == null) {
            finish()
            return
        }
        photo = p
        setBody(build())
    }

    override fun onRefresh() {
        val p = NunbodyRepository.get(this, photo.id)
        if (p == null) {
            finish()
            return
        }
        photo = p
        renderInfo()
    }

    private fun build(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        val d = photo.date
        val (header, sub) = nbHeader(
            "${d.monthValue}월 ${d.dayOfMonth}일 (${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})",
            subtitle = "",
            actions = listOf(iconButton(R.drawable.ic_edit, "체중·키·메모 편집", Palette.TEXT_DIM) { edit() }),
        )
        subtitle = sub!!
        root.addView(centeredColumn(header), lp())
        val box = FrameLayout(this).apply { clipRounded(Palette.SURFACE_HI, dpf(22)) }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${d.monthValue}월 ${d.dayOfMonth}일 눈바디 사진"
        }
        box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(centeredColumn(box), lp(MATCH, 0, 1f).margins(l = dp(20), r = dp(20), t = dp(6)))
        memo = label("", 14f, Palette.TEXT).apply { setLineSpacing(0f, 1.25f) }
        memoWrap = centeredColumn(memo)
        root.addView(memoWrap, lp().margins(l = dp(24), r = dp(24), t = dp(10)))
        val bar = vbox { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        bar.addView(pillButton("내보내기", filled = true, heightDp = 54) { exportSheet() }, lp())
        root.addView(centeredColumn(bar), lp())
        root.applySystemBarPadding()
        NunbodyImages.load(img, NunbodyRepository.file(this, photo), 1600, blur = false)
        renderInfo()
        return root
    }

    private fun renderInfo() {
        val parts = ArrayList<String>()
        parts.add(AlarmMath.formatTime(photo.takenAt.hour, photo.takenAt.minute, android.text.format.DateFormat.is24HourFormat(this)))
        photo.weightKg?.let { parts.add("체중 ${BodyNumbers.format(it)}kg") }
        photo.heightCm?.let { parts.add("키 ${BodyNumbers.format(it)}cm") }
        subtitle.text = parts.joinToString(" · ")
        memo.text = photo.memo
        memoWrap.visibility = if (photo.memo.isBlank()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ 편집

    private fun edit() {
        val w = numberField("체중 (kg)", photo.weightKg?.let { BodyNumbers.format(it) } ?: "")
        val h = numberField("키 (cm)", photo.heightCm?.let { BodyNumbers.format(it) } ?: "")
        val m = EditText(this).apply {
            hint = "메모 (예: 아침 공복)"
            setText(photo.memo)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            textSize = 16f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_FAINT)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Palette.SURFACE, dpf(14), Palette.OUTLINE, dp(1.5f))
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        val body = vbox {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(fieldRow("체중", w, "kg"), lp())
            addView(fieldRow("키", h, "cm"), lp().margins(t = dp(10)))
            addView(label("메모", 14.5f, Palette.TEXT_DIM), lp().margins(t = dp(14), b = dp(6)))
            addView(m, lp())
            addView(label("메모는 이 화면과 기록 표에만 나와요", 12f, Palette.TEXT_DIM), lp().margins(t = dp(6)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("기록 편집")
            .setView(body)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kg = BodyNumbers.parse(w.text.toString(), BodyNumbers.MIN_WEIGHT, BodyNumbers.MAX_WEIGHT)
                val cm = BodyNumbers.parse(h.text.toString(), BodyNumbers.MIN_HEIGHT, BodyNumbers.MAX_HEIGHT)
                if (kg is BodyNumbers.Parsed.Invalid) {
                    toast("체중은 ${BodyNumbers.MIN_WEIGHT.toInt()}~${BodyNumbers.MAX_WEIGHT.toInt()}kg 사이로 적어 주세요")
                    return@setOnClickListener
                }
                if (cm is BodyNumbers.Parsed.Invalid) {
                    toast("키는 ${BodyNumbers.MIN_HEIGHT.toInt()}~${BodyNumbers.MAX_HEIGHT.toInt()}cm 사이로 적어 주세요")
                    return@setOnClickListener
                }
                photo = photo.copy(
                    weightKg = (kg as? BodyNumbers.Parsed.Value)?.v,
                    heightCm = (cm as? BodyNumbers.Parsed.Value)?.v,
                    memo = m.text.toString().trim(),
                )
                NunbodyRepository.update(this, photo)
                renderInfo()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ 내보내기

    private fun exportSheet() {
        val body = vbox { addView(label("내보내기", 20f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(4), b = dp(6))) }
        lateinit var sheet: Dialog
        body.addView(sheetRow(R.drawable.ic_download, "갤러리에 저장", "원본 화질 그대로 휴대폰 갤러리에 저장해요", Palette.ACCENT, Palette.ACCENT_SOFT) {
            sheet.dismiss()
            saveToGallery()
        })
        body.addView(sheetRow(R.drawable.ic_share, "보내기", "카카오톡·메시지 등으로 바로 보내요", Palette.ON_SKY, Palette.SKY_SOFT) {
            sheet.dismiss()
            share()
        })
        body.addView(sheetRow(R.drawable.ic_calendar, "기간 정해서 한 번에", "여러 날의 사진을 모아서 받아요", Palette.TEXT, Palette.SURFACE_HI) {
            sheet.dismiss()
            startActivity(Intent(this, NunbodyExportActivity::class.java))
        })
        body.addView(divider())
        body.addView(sheetRow(R.drawable.ic_trash, "삭제", "이 사진과 그날 기록을 지워요", Palette.DANGER, Palette.WARM_SOFT, Palette.DANGER) {
            sheet.dismiss()
            confirmDelete()
        })
        body.addView(label("내보낸 사진은 알람핏 밖에 저장되어 잠금이 적용되지 않아요.", 12.5f, Palette.TEXT_DIM).apply {
            setLineSpacing(0f, 1.25f)
        }, lp().margins(t = dp(10)))
        sheet = bottomSheet(body)
        sheet.show()
    }

    private fun saveToGallery() {
        if (NunbodyExport.needsStoragePermission && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingGallery = true
            goingAway()   // 권한 창을 다녀오는 동안 다시 잠그지 않음
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        ExportGate.run(this) {
            NunbodyImages.background({ NunbodyExport.saveToGallery(this, listOf(photo), null) }) { r ->
                toast(
                    when {
                        r.saved > 0 -> "갤러리의 '알람핏' 앨범에 저장했어요"
                        r.skipped > 0 -> "이미 갤러리에 저장된 사진이에요"
                        else -> "저장하지 못했어요"
                    }
                )
            }
        }
    }

    private fun share() {
        ExportGate.run(this) {
            NunbodyImages.background({ NunbodyExport.shareIntent(this, listOf(photo), null) }) { intent ->
                if (intent == null) {
                    toast("보낼 사진을 준비하지 못했어요")
                    return@background
                }
                goingAway()
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    toast("보낼 앱을 찾지 못했어요")
                }
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("사진 삭제")
            .setMessage("이 사진과 그날 적은 체중·키·메모를 지울까요? 되돌릴 수 없어요.\n(이미 갤러리에 내보낸 사진은 지워지지 않아요)")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                NunbodyRepository.delete(this, photo)
                toast("삭제했어요")
                finish()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && pendingGallery) {
            pendingGallery = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) saveToGallery()
            else toast("사진을 갤러리에 저장하려면 저장 권한이 필요해요")
        }
    }

    companion object {
        const val EXTRA_ID = "photo_id"
        private const val REQ_STORAGE = 71
    }
}

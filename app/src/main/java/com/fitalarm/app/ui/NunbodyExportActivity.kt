package com.fitalarm.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.core.NunbodyNames
import com.fitalarm.app.core.NunbodyPhoto
import com.fitalarm.app.core.NunbodyRanges
import com.fitalarm.app.core.RangePreset
import com.fitalarm.app.core.RangeSelection
import com.fitalarm.app.core.formatBytes
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.monetize.ExportGate
import com.fitalarm.app.nunbody.NunbodyExport
import com.fitalarm.app.nunbody.NunbodyImages
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 기간 정해서 내보내기: 여러 날의 사진을 한 번에 갤러리에 저장하거나 보내기.
 * 하루하루 따로 받지 않아도 되도록 (나중에 광고를 켜도 한 번에 한 편만).
 */
class NunbodyExportActivity : NunbodyBaseActivity() {
    private val today = LocalDate.now()
    private var from: LocalDate = today.minusMonths(3)
    private var to: LocalDate = today
    private var preset: RangePreset? = RangePreset.MONTHS_3
    private var withTable = true
    private var busy = false
    private var pendingGallery = false

    private lateinit var body: LinearLayout
    private lateinit var countText: TextView
    private lateinit var bottomNote: TextView

    private val dotDate = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ROOT)

    override fun onUnlocked() {
        setBody(build())
        render()
    }

    override fun onRefresh() = render()

    private fun inRange(): List<NunbodyPhoto> = NunbodyRanges.inRange(NunbodyRepository.photos(this), from, to)

    private fun build(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        root.addView(centeredColumn(nbHeader("기간 정해서 내보내기").first), lp())
        body = vbox { setPadding(dp(20), dp(4), dp(20), dp(20)) }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(centeredColumn(body))
        }, lp(MATCH, 0, 1f))
        val bar = vbox { setPadding(dp(20), dp(8), dp(20), dp(14)) }
        bottomNote = label("", 12.5f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }
        bar.addView(bottomNote, lp().margins(b = dp(8)))
        bar.addView(hbox {
            addView(pillButton("갤러리에 저장", filled = false, heightDp = 54) { saveToGallery() }, lp(0, WRAP, 1f).margins(r = dp(5)))
            addView(pillButton("보내기", filled = true, heightDp = 54) { share() }, lp(0, WRAP, 1f).margins(l = dp(5)))
        }, lp())
        root.addView(centeredColumn(bar), lp())
        root.applySystemBarPadding()
        return root
    }

    private fun render() {
        body.removeAllViews()
        val photos = inRange()
        val blur = nb.blur

        // ------------------------------------------------ 기간
        body.addView(card {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(label("기간", 16f, Palette.TEXT, Fonts.BOLD), lp().margins(b = dp(10)))
            addView(hbox {
                RangePreset.entries.forEachIndexed { i, p ->
                    val on = p == preset
                    addView(label(p.title, 13.5f, if (on) Palette.ON_ACCENT_SOFT else Palette.TEXT, if (on) Fonts.BOLD else null).apply {
                        gravity = Gravity.CENTER
                        background = ripple(
                            rounded(if (on) Palette.ACCENT_SOFT else Palette.SURFACE, dpf(99), if (on) Palette.ACCENT else Palette.OUTLINE, dp(1.5f)),
                            rounded(Palette.ACCENT_SOFT, dpf(99)),
                        )
                        isClickable = true
                        contentDescription = p.title + if (on) ", 선택됨" else ""
                        setOnClickListener {
                            val earliest = NunbodyRepository.photos(this@NunbodyExportActivity).minOfOrNull { it.date }
                            val (f, t) = NunbodyRanges.preset(p, today, earliest)
                            from = f
                            to = t
                            preset = p
                            render()
                        }
                    }, lp(0, dp(40), 1f).margins(l = if (i == 0) 0 else dp(6)))
                }
            }, lp())
            addView(hbox {
                setPadding(dp(14), 0, dp(12), 0)
                background = ripple(rounded(Palette.SURFACE, dpf(14), Palette.OUTLINE, dp(1.5f)), rounded(Palette.ACCENT_SOFT, dpf(14)))
                isClickable = true
                contentDescription = "달력에서 기간 고르기, 지금 ${from}부터 ${to}까지"
                setOnClickListener { openCalendar() }
                addView(icon(R.drawable.ic_calendar, Palette.ACCENT, 20), lp(dp(20), dp(20)).margins(r = dp(10)))
                addView(label("${dotDate.format(from)} ~ ${dotDate.format(to)}", 15f), lp(0, WRAP, 1f))
                addView(label("달력에서 고르기", 13f, Palette.ACCENT, Fonts.BOLD))
            }, lp(MATCH, dp(48)).margins(t = dp(12)))
        }, lp())

        // ------------------------------------------------ 담길 사진
        body.addView(card {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val bytes = photos.sumOf { it.bytes }
            val days = photos.map { it.date }.distinct().size
            countText = label(if (photos.isEmpty()) "이 기간에는 사진이 없어요" else "사진 ${photos.size}장", 17f, Palette.TEXT, Fonts.BOLD)
            addView(hbox {
                gravity = Gravity.BOTTOM
                addView(countText)
                if (photos.isNotEmpty()) addView(label("약 ${formatBytes(bytes)} · 기록 ${days}일", 14f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(l = dp(8)))
            }, lp())
            if (photos.isNotEmpty()) {
                val strip = hbox()
                photos.forEach { p ->
                    val (frame, img) = photoTile(1.4f)
                    NunbodyImages.load(img, NunbodyRepository.file(this@NunbodyExportActivity, p), 160, blur)
                    strip.addView(frame, lp(dp(40), WRAP).margins(r = dp(6)))
                }
                addView(HorizontalScrollView(this@NunbodyExportActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                }, lp().margins(t = dp(10)))
                val f = photos.first().date
                val l = photos.last().date
                addView(label("${f.monthValue}월 ${f.dayOfMonth}일 ~ ${l.monthValue}월 ${l.dayOfMonth}일에 찍은 사진", 12.5f, Palette.TEXT_DIM), lp().margins(t = dp(8)))
            }
        }, lp().margins(t = dp(14)))

        // ------------------------------------------------ 파일
        body.addView(card("파일") {
            val (row, sw, _) = switchRowWithRefs("체중 기록 표", "날짜별 체중·키·메모 (엑셀에서 열려요)", withTable) { on ->
                withTable = on
                render()
            }
            sw.contentDescription = "체중 기록 표 함께 담기"
            addView(row)
            addView(divider())
            addView(label("파일 이름", 15f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(10)))
            val names = StringBuilder()
            photos.take(2).forEach { names.append(it.id).append('\n') }
            if (photos.size > 2) names.append("… (찍은 순서대로 ${photos.size}장)\n")
            if (withTable) names.append(NunbodyNames.table(from, to))
            addView(label(names.toString().trimEnd().ifEmpty { "-" }, 12.5f, Palette.TEXT).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(Palette.BG, dpf(10))
                setLineSpacing(0f, 1.35f)
                typeface = android.graphics.Typeface.MONOSPACE
            }, lp().margins(t = dp(6)))
            addView(label("갤러리의 '알람핏' 앨범에 한 장씩, 찍은 순서대로 저장돼요." + if (withTable) " 기록 표는 다운로드 › 알람핏 폴더에 들어가요." else "",
                12.5f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.3f) }, lp().margins(t = dp(8), b = dp(8)))
        }, lp().margins(t = dp(14)))

        bottomNote.text = if (photos.isEmpty()) "기간을 바꿔 보세요" else "${photos.size}장을 한 번에 받아요 · 지금은 무료예요"
    }

    // ------------------------------------------------------------------ 달력

    private fun openCalendar() {
        val all = NunbodyRepository.photos(this)
        val photoDays = all.map { it.date }.toSet()
        val sel = RangeSelection(from, to, today)
        val content = vbox()
        content.addView(label("기간 선택", 20f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(4)))
        val startBox = rangeBox()
        val endBox = rangeBox()
        content.addView(hbox {
            addView(startBox, lp(0, WRAP, 1f).margins(r = dp(4)))
            addView(endBox, lp(0, WRAP, 1f).margins(l = dp(4)))
        }, lp().margins(t = dp(12)))
        val monthTitle = label("", 16f, Palette.TEXT, Fonts.BOLD).apply { gravity = Gravity.CENTER }
        lateinit var applyButton: TextView
        lateinit var cal: RangeCalendarView
        fun refreshTexts() {
            val r = sel.result()
            val s = sel.start
            val e = sel.end
            fun fmt(d: LocalDate?) = d?.let { "${it.monthValue}월 ${it.dayOfMonth}일 (${it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.KOREAN)})" } ?: "고르세요"
            setBox(startBox, "시작", fmt(s), active = s == null)
            setBox(endBox, if (s != null && e == null) "끝 · 고르는 중" else "끝", fmt(e), active = s != null && e == null)
            if (r == null) {
                applyButton.text = "날짜를 골라 주세요"
            } else {
                val days = r.second.toEpochDay() - r.first.toEpochDay() + 1
                val n = NunbodyRanges.inRange(all, r.first, r.second).size
                applyButton.text = "${days}일 · 사진 ${n}장 적용"
            }
        }
        cal = RangeCalendarView(this, sel, today, photoDays, onChange = { refreshTexts() }) { m ->
            monthTitle.text = "${m.year}년 ${m.monthValue}월"
        }
        content.addView(hbox {
            addView(iconButton(R.drawable.ic_chevron_left, "이전 달") { cal.month = cal.month.minusMonths(1) })
            addView(monthTitle, lp(0, WRAP, 1f))
            addView(iconButton(R.drawable.ic_chevron_right, "다음 달") {
                val next = cal.month.plusMonths(1)
                if (!next.atDay(1).isAfter(today)) cal.month = next
            })
        }, lp().margins(t = dp(8)))
        content.addView(cal, lp().margins(t = dp(2)))
        content.addView(label("날짜를 차례로 눌러 시작·끝을 고르거나, 누른 채 끌어서 한 번에 정해요. 점은 사진 있는 날이에요.", 12.5f, Palette.TEXT_DIM).apply {
            setLineSpacing(0f, 1.3f)
        }, lp().margins(t = dp(6)))
        lateinit var dialog: Dialog
        applyButton = pillButton("", filled = true, heightDp = 54) {
            val r = sel.result() ?: return@pillButton
            from = r.first
            to = r.second
            preset = null
            dialog.dismiss()
            render()
        }
        content.addView(hbox {
            addView(pillButton("초기화", filled = false, heightDp = 54) {
                sel.start = null
                sel.end = null
                cal.invalidate()
                refreshTexts()
            }, lp(dp(100), WRAP).margins(r = dp(8)))
            addView(applyButton, lp(0, WRAP, 1f))
        }, lp().margins(t = dp(14)))
        cal.month = YearMonth.from(to)
        refreshTexts()
        dialog = bottomSheet(content)
        dialog.show()
    }

    private fun rangeBox(): LinearLayout = vbox {
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label("", 12f, Palette.TEXT_DIM))
        addView(label("", 15f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(2)))
    }

    private fun setBox(box: LinearLayout, title: String, value: String, active: Boolean) {
        (box.getChildAt(0) as TextView).apply {
            text = title
            setTextColor(if (active) Palette.ACCENT else Palette.TEXT_DIM)
        }
        (box.getChildAt(1) as TextView).text = value
        box.background = rounded(if (active) Palette.BG else Palette.SURFACE, dpf(14), if (active) Palette.ACCENT else Palette.OUTLINE, dp(1.5f))
    }

    // ------------------------------------------------------------------ 내보내기

    private fun table(photos: List<NunbodyPhoto>) = if (withTable) NunbodyExport.table(photos, from, to) else null

    private fun saveToGallery() {
        val photos = inRange()
        if (photos.isEmpty() || busy) {
            if (photos.isEmpty()) toast("이 기간에는 사진이 없어요")
            return
        }
        if (NunbodyExport.needsStoragePermission && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingGallery = true
            goingAway()   // 권한 창을 다녀오는 동안 다시 잠그지 않음
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        ExportGate.run(this) {
            busy = true
            val progress = progressDialog("갤러리에 저장하는 중…")
            NunbodyImages.background({ NunbodyExport.saveToGallery(this, photos, table(photos)) }) { r ->
                busy = false
                progress.dismiss()
                val parts = ArrayList<String>()
                if (r.saved > 0) parts.add("사진 ${r.saved}장 저장")
                if (r.skipped > 0) parts.add("이미 있던 ${r.skipped}장은 건너뜀")
                if (r.failed > 0) parts.add("${r.failed}장 실패")
                if (withTable) parts.add(if (r.tableSaved) "기록 표는 다운로드 › 알람핏" else "기록 표 저장 실패")
                AlertDialog.Builder(this)
                    .setTitle("갤러리에 저장했어요")
                    .setMessage(parts.joinToString("\n") + "\n\n갤러리의 '알람핏' 앨범에서 볼 수 있어요. 내보낸 사진에는 잠금이 적용되지 않아요.")
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
    }

    private fun share() {
        val photos = inRange()
        if (photos.isEmpty() || busy) {
            if (photos.isEmpty()) toast("이 기간에는 사진이 없어요")
            return
        }
        ExportGate.run(this) {
            busy = true
            val progress = progressDialog("보낼 사진을 준비하는 중…")
            NunbodyImages.background({ NunbodyExport.shareIntent(this, photos, table(photos)) }) { intent ->
                busy = false
                progress.dismiss()
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

    private fun progressDialog(text: String): AlertDialog =
        AlertDialog.Builder(this)
            .setView(hbox {
                setPadding(dp(24), dp(22), dp(24), dp(22))
                addView(android.widget.ProgressBar(this@NunbodyExportActivity), lp(dp(32), dp(32)).margins(r = dp(16)))
                addView(label(text, 15f), lp(0, WRAP, 1f))
            })
            .setCancelable(false)
            .show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && pendingGallery) {
            pendingGallery = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) saveToGallery()
            else toast("사진을 갤러리에 저장하려면 저장 권한이 필요해요")
        }
    }

    companion object {
        private const val REQ_STORAGE = 72
    }
}

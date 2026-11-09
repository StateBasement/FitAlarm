package com.fitalarm.app.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fitalarm.app.R
import com.fitalarm.app.core.BodyNumbers
import com.fitalarm.app.core.NunbodyPhoto
import com.fitalarm.app.core.NunbodyStats
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.nunbody.NunbodyImages
import java.time.LocalDate
import java.time.YearMonth

/** 눈바디: 체중 그래프 + 달별 사진 타임라인 + 사진 찍기 */
class NunbodyActivity : NunbodyBaseActivity() {
    private lateinit var list: LinearLayout

    override fun onUnlocked() {
        setBody(build())
        render()
    }

    override fun onRefresh() = render()

    private fun build(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        val (header, _) = nbHeader(
            "눈바디",
            actions = listOf(
                iconButton(R.drawable.ic_download, "기간 정해서 내보내기", Palette.TEXT_DIM) {
                    startActivity(Intent(this, NunbodyExportActivity::class.java))
                },
                iconButton(R.drawable.ic_settings, "눈바디 설정", Palette.TEXT_DIM) {
                    startActivity(Intent(this, NunbodySettingsActivity::class.java))
                },
            ),
        )
        root.addView(centeredColumn(header), lp())
        list = vbox { setPadding(dp(20), dp(4), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(centeredColumn(list))
        }, lp(MATCH, 0, 1f))
        val bar = vbox { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        bar.addView(pillButton("사진 찍기", filled = true, heightDp = 54) { shoot() }, lp())
        root.addView(centeredColumn(bar), lp())
        root.applySystemBarPadding()
        return root
    }

    private fun shoot() = startActivity(Intent(this, NunbodyCameraActivity::class.java))

    private fun render() {
        list.removeAllViews()
        val s = nb
        val photos = NunbodyRepository.photos(this)
        val today = LocalDate.now()

        // ------------------------------------------------ 체중
        list.addView(card {
            setPadding(dp(18), dp(16), dp(18), dp(12))
            val latest = NunbodyStats.latestWeight(photos)
            val height = NunbodyStats.latestHeight(photos)
            addView(hbox {
                addView(label("체중", 16f, Palette.TEXT, Fonts.BOLD), lp(0, WRAP, 1f))
                if (height != null) addView(label("키 ${BodyNumbers.format(height)}cm", 12.5f, Palette.TEXT_DIM))
            }, lp())
            if (latest == null) {
                addView(label("사진을 저장할 때 체중을 적으면 여기에 그래프로 보여요", 13.5f, Palette.TEXT_DIM).apply {
                    setLineSpacing(0f, 1.25f)
                }, lp().margins(t = dp(10), b = dp(8)))
            } else {
                addView(hbox {
                    gravity = Gravity.BOTTOM
                    addView(label(BodyNumbers.format(latest.weightKg!!), 30f, Palette.TEXT, Fonts.BOLD))
                    addView(label("kg", 16f, Palette.TEXT, Fonts.MEDIUM), lp(WRAP, WRAP).margins(l = dp(2), r = dp(10)))
                    val change = NunbodyStats.changeSince(photos, today.minusMonths(6))
                    if (change != null) {
                        addView(label("${change.second}개월 동안 ${BodyNumbers.formatChange(change.first)}", 13f, Palette.TEXT_DIM, Fonts.MEDIUM),
                            lp(WRAP, WRAP).margins(b = dp(4)))
                    }
                }, lp().margins(t = dp(10)))
                addView(WeightChartView(this@NunbodyActivity).apply {
                    setData(NunbodyStats.weighed(photos).map { it.date to it.weightKg!! }, today)
                }, lp(MATCH, dp(116)).margins(t = dp(6)))
            }
        }, lp())

        // ------------------------------------------------ 사진
        list.addView(hbox {
            addView(label("사진", 16f, Palette.TEXT, Fonts.BOLD), lp(0, WRAP, 1f))
            val chip = hbox {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = ripple(rounded(Palette.ACCENT_SOFT, dpf(18)), rounded(Palette.ACCENT_SOFT, dpf(18)))
                isClickable = true
                minimumHeight = dp(36)
                contentDescription = if (s.blur) "사진 가리기 켜짐. 누르면 끔" else "사진 가리기 꺼짐. 누르면 켬"
                addView(icon(if (s.blur) R.drawable.ic_eye_off else R.drawable.ic_eye, Palette.ON_ACCENT_SOFT, 15), lp(dp(15), dp(15)).margins(r = dp(6)))
                addView(label(if (s.blur) "사진 가리기 켜짐" else "사진 가리기 꺼짐", 12.5f, Palette.ON_ACCENT_SOFT, Fonts.MEDIUM))
                setOnClickListener {
                    NunbodyRepository.update(this@NunbodyActivity) { it.copy(blur = !it.blur) }
                    render()
                }
            }
            addView(chip)
        }, lp().margins(t = dp(20)))

        val byMonth = photos.groupBy { YearMonth.from(it.takenAt) }.toSortedMap(compareByDescending { it })
        val thisMonth = YearMonth.from(today)
        if (!byMonth.containsKey(thisMonth)) byMonth[thisMonth] = emptyList()
        for ((ym, items) in byMonth) {
            list.addView(label("${ym.year}년 ${ym.monthValue}월", 13f, Palette.TEXT_DIM, Fonts.BOLD), lp().margins(t = dp(14), b = dp(8)))
            val cells = ArrayList<View>()
            items.forEach { cells.add(tile(it, s.blur)) }
            if (ym == thisMonth) cells.add(shootTile())
            list.addView(gridRows(cells), lp())
        }
        if (photos.isEmpty()) {
            list.addView(label("아직 사진이 없어요. 아래 '사진 찍기'로 첫 사진을 남겨 보세요.\n사진은 이 폰의 알람핏 안에만 저장돼요.", 13.5f, Palette.TEXT_DIM).apply {
                setLineSpacing(0f, 1.3f)
            }, lp().margins(t = dp(14)))
        }
    }

    private fun tile(p: NunbodyPhoto, blur: Boolean): View {
        val (frame, img) = photoTile(1.2f, "${p.date.dayOfMonth}일")
        frame.contentDescription = "${p.date.monthValue}월 ${p.date.dayOfMonth}일 사진"
        frame.isClickable = true
        frame.foreground = rippleOnly(14)
        frame.setOnClickListener {
            startActivity(Intent(this, NunbodyPhotoActivity::class.java).putExtra(NunbodyPhotoActivity.EXTRA_ID, p.id))
        }
        NunbodyImages.load(img, NunbodyRepository.file(this, p), 420, blur)
        return frame
    }

    private fun shootTile(): View = RatioFrame(this, 1.2f).apply {
        background = dashedBorder(Palette.OUTLINE, dpf(2), dpf(14), dpf(6))
        foreground = rippleOnly(14)
        isClickable = true
        contentDescription = "새 사진 찍기"
        setOnClickListener { shoot() }
        addView(vbox {
            gravity = Gravity.CENTER
            addView(icon(R.drawable.ic_camera, Palette.ACCENT, 26))
            addView(label("찍기", 13f, Palette.ACCENT, Fonts.MEDIUM), lp(WRAP, WRAP).margins(t = dp(6)))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

package com.fitalarm.app.ui

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import com.fitalarm.app.R
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.BodyNumbers
import com.fitalarm.app.core.NunbodyStats
import com.fitalarm.app.core.PhotoSource
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.nunbody.NunbodyImages
import java.time.LocalDateTime

/** 찍은 사진 확인 + 체중·키 (모두 선택) → 앱 안에 저장 */
class NunbodySaveActivity : NunbodyBaseActivity() {
    private lateinit var weightField: EditText
    private lateinit var heightField: EditText
    private lateinit var source: PhotoSource
    private lateinit var takenAt: LocalDateTime
    private var saving = false

    override fun onUnlocked() {
        source = PhotoSource.fromKey(intent.getStringExtra(EXTRA_SOURCE))
        takenAt = intent.getStringExtra(EXTRA_TAKEN_AT)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: LocalDateTime.now().withNano(0)
        val file = NunbodyRepository.captureFile(this)
        if (!file.exists()) {
            finish()
            return
        }
        setBody(build())
        val img = findViewById<ImageView>(IMG_ID)
        NunbodyImages.load(img, file, 900, blur = false)
    }

    private fun build(): View {
        val file = NunbodyRepository.captureFile(this)
        val (w, h) = NunbodyImages.uprightSize(file)
        val root = vbox { setBackgroundColor(Palette.BG) }
        root.addView(centeredColumn(nbHeader("사진 저장", closeIcon = true).first), lp())

        val content = vbox { setPadding(dp(20), dp(4), dp(20), dp(20)) }
        content.addView(hbox {
            gravity = Gravity.CENTER_VERTICAL
            addView(RatioFrame(this@NunbodySaveActivity, 1.38f).apply {
                clipRounded(Palette.SURFACE_HI, dpf(18))
                addView(ImageView(this@NunbodySaveActivity).apply {
                    id = IMG_ID
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "방금 찍은 사진"
                }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            }, lp(dp(130), WRAP).margins(r = dp(16)))
            addView(vbox {
                addView(label("${takenAt.monthValue}월 ${takenAt.dayOfMonth}일 (${dayName()})", 17f, Palette.TEXT, Fonts.BOLD))
                val time = AlarmMath.formatTime(takenAt.hour, takenAt.minute, android.text.format.DateFormat.is24HourFormat(this@NunbodySaveActivity))
                addView(label("$time · ${source.title} · ${w}×${h}", 13f, Palette.TEXT_DIM), lp().margins(t = dp(6)))
                addView(pillButton("다시 찍기", filled = false, heightDp = 40) { retake() }, lp(WRAP, WRAP).margins(t = dp(12)))
            }, lp(0, WRAP, 1f))
        }, lp())

        val photos = NunbodyRepository.photos(this)
        weightField = numberField("예: ${NunbodyStats.latestWeight(photos)?.weightKg?.let { BodyNumbers.format(it) } ?: "68.2"}")
        heightField = numberField("예: 175", NunbodyStats.latestHeight(photos)?.let { BodyNumbers.format(it) } ?: "")
        content.addView(card {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(hbox {
                addView(label("체중·키", 16f, Palette.TEXT, Fonts.BOLD))
                addView(label("키는 지난번 값 그대로 · 비워 둬도 돼요", 12f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(l = dp(8)))
            }, lp())
            addView(fieldRow("체중", weightField, "kg"), lp().margins(t = dp(12)))
            addView(fieldRow("키", heightField, "cm"), lp().margins(t = dp(10)))
        }, lp().margins(t = dp(16)))

        content.addView(noteBox(R.drawable.ic_shield, "사진은 이 폰의 알람핏 안에만 저장돼요. 갤러리나 클라우드에 올라가지 않아요.", Palette.ACCENT_SOFT, Palette.ON_ACCENT_SOFT),
            lp().margins(t = dp(14)))

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(centeredColumn(content))
        }, lp(MATCH, 0, 1f))
        val bar = vbox { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        bar.addView(pillButton("저장", filled = true, heightDp = 54) { save() }, lp())
        root.addView(centeredColumn(bar), lp())
        root.applySystemBarPadding(includeIme = true)
        return root
    }

    private fun dayName(): String = takenAt.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)

    private fun retake() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun save() {
        if (saving) return
        val kg = when (val r = BodyNumbers.parse(weightField.text.toString(), BodyNumbers.MIN_WEIGHT, BodyNumbers.MAX_WEIGHT)) {
            is BodyNumbers.Parsed.Value -> r.v
            BodyNumbers.Parsed.Empty -> null
            BodyNumbers.Parsed.Invalid -> {
                toast("체중은 ${BodyNumbers.MIN_WEIGHT.toInt()}~${BodyNumbers.MAX_WEIGHT.toInt()}kg 사이로 적어 주세요")
                return
            }
        }
        val cm = when (val r = BodyNumbers.parse(heightField.text.toString(), BodyNumbers.MIN_HEIGHT, BodyNumbers.MAX_HEIGHT)) {
            is BodyNumbers.Parsed.Value -> r.v
            BodyNumbers.Parsed.Empty -> null
            BodyNumbers.Parsed.Invalid -> {
                toast("키는 ${BodyNumbers.MIN_HEIGHT.toInt()}~${BodyNumbers.MAX_HEIGHT.toInt()}cm 사이로 적어 주세요")
                return
            }
        }
        saving = true
        val file = NunbodyRepository.captureFile(this)
        val (w, h) = NunbodyImages.uprightSize(file)
        try {
            NunbodyRepository.add(this, file, takenAt, w, h, source, kg, cm)
        } catch (e: Exception) {
            saving = false
            toast("사진을 저장하지 못했어요. 저장 공간을 확인해 주세요")
            return
        }
        toast("저장했어요")
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TAKEN_AT = "taken_at"
        private const val IMG_ID = 0x7a01
    }
}

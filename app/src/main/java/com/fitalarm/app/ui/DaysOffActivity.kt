package com.fitalarm.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fitalarm.app.R
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.data.HolidayRepository
import java.time.LocalDate

/** 내 휴무일 관리 + 다가오는 공휴일 확인 */
class DaysOffActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var holidayBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        setupEdgeToEdge()
        setContentView(build())
        render()
    }

    private fun build(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        val header = hbox {
            setPadding(dp(8), dp(10), dp(20), dp(6))
            addView(FrameLayout(this@DaysOffActivity).apply {
                background = rippleOnly(22)
                isClickable = true
                contentDescription = "뒤로"
                setOnClickListener { finish() }
                addView(icon(R.drawable.ic_chevron_left, Palette.TEXT, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, lp(dp(44), dp(44)))
            addView(label("쉬는 날", 22f, Palette.TEXT, Fonts.BOLD), lp(WRAP, WRAP).margins(l = dp(4)))
        }
        root.addView(header, lp())

        val content = vbox { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        content.addView(card("내 휴무일") {
            addView(label("연차·회사 휴무처럼 쉬는 날을 등록하면, '휴무일 건너뛰기'를 켠 반복 알람이 그날 울리지 않아요.", 13f, Palette.TEXT_DIM).apply {
                setLineSpacing(0f, 1.2f)
            }, lp().margins(t = dp(2), b = dp(8)))
            listBox = vbox()
            addView(listBox, lp())
            addView(pillButton("+ 휴무일 추가", filled = false, heightDp = 46) { pickDate() }, lp().margins(t = dp(8), b = dp(8)))
        }, lp())

        content.addView(card("다가오는 공휴일") {
            holidayBox = vbox()
            addView(holidayBox, lp())
        }, lp().margins(t = dp(12)))

        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        root.applySystemBarPadding()
        return root
    }

    private fun render() {
        val snap = HolidayRepository.snapshot(this)
        val today = LocalDate.now()
        listBox.removeAllViews()
        val mine = snap.daysOff.filterKeys { !it.isBefore(today) }.toSortedMap()
        if (mine.isEmpty()) {
            listBox.addView(label("등록된 휴무일이 없어요", 14f, Palette.TEXT_DIM), lp().margins(t = dp(4), b = dp(4)))
        }
        for ((date, memo) in mine) {
            listBox.addView(hbox {
                setPadding(0, dp(8), 0, dp(8))
                addView(vbox {
                    addView(label(dateText(date), 16f))
                    if (memo.isNotBlank()) addView(label(memo, 13f, Palette.TEXT_DIM), lp().margins(t = dp(2)))
                }, lp(0, WRAP, 1f))
                addView(FrameLayout(this@DaysOffActivity).apply {
                    background = rippleOnly(20)
                    isClickable = true
                    contentDescription = "${dateText(date)} 삭제"
                    setOnClickListener { confirmRemove(date) }
                    addView(icon(R.drawable.ic_trash, Palette.DANGER, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                }, lp(dp(40), dp(40)))
            }, lp())
        }

        holidayBox.removeAllViews()
        for (h in HolidayRepository.upcoming(this, today, 15)) {
            holidayBox.addView(hbox {
                setPadding(0, dp(7), 0, dp(7))
                addView(label(dateText(h.date), 15f), lp(dp(130), WRAP))
                addView(label(h.name, 15f, Palette.ON_SKY).apply { maxLines = 2 }, lp(0, WRAP, 1f))
            }, lp())
        }
        val sources = ArrayList<String>()
        sources.add("앱 내장 규칙")
        if (snap.remoteAt > 0) sources.add("구글 공휴일 달력")
        if (snap.deviceSources.isNotEmpty()) sources.add("폰 달력(" + snap.deviceSources.joinToString(", ") + ")")
        holidayBox.addView(label("출처: " + sources.joinToString(" · "), 12f, Palette.TEXT_DIM).apply {
            setLineSpacing(0f, 1.2f)
        }, lp().margins(t = dp(8), b = dp(6)))
    }

    private fun dateText(d: LocalDate): String {
        val dow = arrayOf("월", "화", "수", "목", "금", "토", "일")[d.dayOfWeek.value - 1]
        val year = if (d.year != LocalDate.now().year) "${d.year}년 " else ""
        return "$year${d.monthValue}월 ${d.dayOfMonth}일 ($dow)"
    }

    private fun pickDate() {
        val t = LocalDate.now()
        DatePickerDialog(this, { _, y, m, day ->
            askMemo(LocalDate.of(y, m + 1, day))
        }, t.year, t.monthValue - 1, t.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun askMemo(date: LocalDate) {
        val input = EditText(this).apply {
            hint = "메모 (선택, 예: 연차)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(dateText(date))
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가") { _, _ ->
                HolidayRepository.addDayOff(this, date, input.text.toString())
                AlarmScheduler(this).rescheduleAll()
                render()
            }
            .show()
    }

    private fun confirmRemove(date: LocalDate) {
        AlertDialog.Builder(this)
            .setMessage("${dateText(date)} 휴무일을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                HolidayRepository.removeDayOff(this, date)
                AlarmScheduler(this).rescheduleAll()
                render()
            }
            .show()
    }
}

package com.fitalarm.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.core.DayResult
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.RecordStats
import com.fitalarm.app.data.HolidayRepository
import com.fitalarm.app.data.RecordRepository
import java.time.LocalDate
import java.time.YearMonth

/** 기록 탭: 연속 성공, 월간 달력, 운동량 */
class RecordsPage(host: MainActivity) : Page(host) {
    private val ctx = host
    private var month: YearMonth = YearMonth.now()
    private lateinit var monthTitle: TextView
    private lateinit var body: LinearLayout

    override val root: View = build()

    private fun dp(v: Number) = ctx.dp(v)

    private fun build(): View = with(ctx) {
        val content = vbox { setPadding(dp(20), dp(20), dp(20), dp(28)) }
        val header = hbox {
            addView(label("기록", 30f, Palette.TEXT, Fonts.BOLD))
            addView(spacer())
            addView(navButton(R.drawable.ic_chevron_left, "이전 달") { month = month.minusMonths(1); refresh() })
            monthTitle = label("", 15f, Palette.TEXT, Fonts.MEDIUM)
            addView(monthTitle, lp(WRAP, WRAP).margins(l = dp(2), r = dp(2)))
            addView(navButton(R.drawable.ic_chevron_right, "다음 달") { month = month.plusMonths(1); refresh() })
        }
        content.addView(header, lp())
        body = vbox()
        content.addView(body, lp().margins(t = dp(12)))
        ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun navButton(iconRes: Int, desc: String, onClick: () -> Unit): View = FrameLayout(ctx).apply {
        background = ctx.rippleOnly(18)
        isClickable = true
        contentDescription = desc
        setOnClickListener { onClick() }
        addView(ctx.icon(iconRes, Palette.TEXT, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        layoutParams = lp(dp(40), dp(40))
    }

    override fun refresh() {
        monthTitle.text = "${month.year}년 ${month.monthValue}월"
        body.removeAllViews()
        val all = RecordRepository.all(ctx)
        val today = LocalDate.now()
        val streak = RecordStats.currentStreak(all, today)
        val best = RecordStats.bestStreak(all)
        val summary = RecordStats.month(all, month)
        val days = RecordStats.days(all)

        with(ctx) {
            // 연속 기록
            body.addView(hbox {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = rounded(Palette.WARM_SOFT, dpf(22))
                addView(icon(R.drawable.ic_flame, Palette.WARM, 36), lp(dp(36), dp(36)).margins(r = dp(14)))
                addView(vbox {
                    addView(label(if (streak > 0) "${streak}일 연속 성공" else "오늘부터 시작해요", 20f, Palette.ON_WARM, Fonts.BOLD))
                    addView(label("최고 ${best}일 · 공휴일과 알람 없는 날은 기록이 끊기지 않아요", 12.5f, Palette.ON_WARM).apply {
                        setLineSpacing(0f, 1.2f)
                    }, lp().margins(t = dp(3)))
                }, lp(0, WRAP, 1f))
            }, lp())

            // 요약 3칸
            val tiles = hbox()
            val avg = summary.avgMissionMs?.let { ms -> val s = ms / 1000; "${s / 60}:${String.format(java.util.Locale.ROOT, "%02d", s % 60)}" } ?: "-"
            listOf(
                "미션 성공" to "${summary.successes} / ${summary.alarmDays}",
                "보너스 세트" to "${summary.bonusSets}",
                "평균 미션 시간" to avg,
            ).forEachIndexed { i, (t, v) ->
                tiles.addView(vbox {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(Palette.SURFACE, dpf(18), Palette.OUTLINE, dp(1))
                    addView(label(t, 12f, Palette.TEXT_DIM))
                    addView(label(v, 22f, Palette.TEXT, Fonts.MEDIUM), lp().margins(t = dp(2)))
                }, lp(0, WRAP, 1f).margins(l = if (i == 0) 0 else dp(8)))
            }
            body.addView(tiles, lp().margins(t = dp(12)))

            // 월간 달력
            val cal = card { }
            val headerRow = hbox()
            listOf("월", "화", "수", "목", "금", "토", "일").forEach {
                headerRow.addView(label(it, 12f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
            }
            cal.addView(headerRow, lp().margins(t = dp(4), b = dp(6)))
            val first = month.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val total = lead + month.lengthOfMonth()
            val rows = (total + 6) / 7
            for (r in 0 until rows) {
                val row = hbox()
                for (c in 0 until 7) {
                    val idx = r * 7 + c
                    val dayNum = idx - lead + 1
                    val cell = if (dayNum in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayNum)
                        dayCell(date, days[date]?.result ?: DayResult.NONE, days[date]?.bonus == true, today)
                    } else View(this)
                    row.addView(cell, lp(0, dp(44), 1f))
                }
                cal.addView(row, lp())
            }
            val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            legend.addView(legendItem(oval(Palette.ACCENT), "성공"))
            legend.addView(legendItem(oval(Palette.WARM), "보너스", 6))
            legend.addView(legendItem(oval(Palette.SKY_SOFT, Palette.ON_SKY, dp(1)), "공휴일"))
            legend.addView(legendItem(dashedRing(), "놓침"))
            cal.addView(legend, lp().margins(t = dp(6), b = dp(6)))
            body.addView(cal, lp().margins(t = dp(12)))

            // 운동량
            val reps = card("이번 달 운동량") { }
            val lines = ArrayList<Triple<String, Int, Boolean>>()
            summary.missionReps.forEach { (t, n) -> lines.add(Triple(short(t), n, false)) }
            summary.bonusReps.forEach { (t, n) -> lines.add(Triple(short(t) + " (보너스)", n, true)) }
            if (lines.isEmpty()) {
                reps.addView(label("아직 기록이 없어요. 운동 미션을 완료하면 여기에 쌓여요.", 14f, Palette.TEXT_DIM), lp().margins(t = dp(6), b = dp(8)))
            } else {
                for ((name, n, bonus) in lines) {
                    reps.addView(hbox {
                        setPadding(0, dp(7), 0, dp(7))
                        addView(label(name, 15f), lp(0, WRAP, 1f))
                        addView(label("${n}회", 15f, if (bonus) Palette.WARM else Palette.TEXT, Fonts.BOLD))
                    }, lp())
                }
            }
            body.addView(reps, lp().margins(t = dp(12)))
        }
    }

    private fun short(t: ExerciseType) = t.title.substringBefore(" (")

    private fun dashedRing(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(dp(1.5f), Palette.WARM, ctx.dpf(3), ctx.dpf(2))
    }

    private fun dayCell(date: LocalDate, result: DayResult, bonus: Boolean, today: LocalDate): View = with(ctx) {
        val holiday = HolidayRepository.holidayName(this, date) != null
        val circle = label(date.dayOfMonth.toString(), 14f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }
        when {
            result == DayResult.SUCCESS -> {
                circle.background = oval(Palette.ACCENT)
                circle.setTextColor(Palette.ON_ACCENT)
                circle.typeface = Fonts.BOLD
            }
            result == DayResult.MISSED -> {
                circle.background = dashedRing()
                circle.setTextColor(Palette.ON_WARM)
            }
            date == today -> {
                circle.background = oval(android.graphics.Color.TRANSPARENT, Palette.ACCENT, dp(2))
                circle.setTextColor(Palette.ACCENT)
                circle.typeface = Fonts.BOLD
            }
            holiday -> {
                circle.background = oval(Palette.SKY_SOFT)
                circle.setTextColor(Palette.ON_SKY)
            }
        }
        vbox {
            gravity = Gravity.CENTER
            addView(circle, lp(dp(32), dp(32)))
            val dot = View(this@with).apply {
                background = oval(Palette.WARM)
                visibility = if (bonus) View.VISIBLE else View.INVISIBLE
            }
            addView(dot, lp(dp(6), dp(6)).margins(t = dp(2)))
        }
    }

    private fun legendItem(swatch: GradientDrawable, text: String, sizeDp: Int = 10): View = with(ctx) {
        hbox {
            addView(View(this@with).apply { background = swatch }, lp(dp(sizeDp), dp(sizeDp)).margins(r = dp(4)))
            addView(label(text, 11.5f, Palette.TEXT_DIM))
            setPadding(0, 0, dp(14), 0)
        }
    }
}

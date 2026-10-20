package com.fitalarm.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.fitalarm.app.R
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.RecordStats
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.data.HolidayRepository
import com.fitalarm.app.data.RecordRepository
import com.fitalarm.app.ui.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 홈 화면 위젯: 다음 알람, 오늘의 운동, 연속 성공 일수 */
class AlarmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds)
    }

    companion object {
        /** 알람이 바뀌거나 기록이 쌓일 때마다 호출 (위젯이 없으면 아무것도 안 함) */
        fun refresh(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(ctx, AlarmWidget::class.java))
                if (ids.isNotEmpty()) render(ctx, manager, ids)
            } catch (e: Exception) {
                // 위젯 갱신 실패는 무시
            }
        }

        private fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_next_alarm)
            val now = ZonedDateTime.now()
            val next: Pair<Alarm, Long>? = AlarmRepository.getAll(ctx)
                .mapNotNull { a -> AlarmMath.nextTriggerMillis(a, now, HolidayRepository.skipRuleFor(ctx, a))?.let { a to it } }
                .minByOrNull { it.second }
            val streak = RecordStats.currentStreak(RecordRepository.all(ctx), LocalDate.now())
            val streakText = if (streak > 0) " · ${streak}일 연속" else ""

            if (next == null) {
                views.setTextViewText(R.id.widget_label, "다음 알람")
                views.setTextViewText(R.id.widget_time, "켜진 알람 없음")
                views.setTextViewText(R.id.widget_detail, "탭해서 알람을 추가하세요$streakText")
            } else {
                val (alarm, at) = next
                val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                val day = AlarmMath.relativeDay(t.toLocalDate(), now.toLocalDate())
                views.setTextViewText(R.id.widget_label, "다음 알람 · $day")
                views.setTextViewText(R.id.widget_time, AlarmMath.formatTime(t.hour, t.minute, DateFormat.is24HourFormat(ctx)))
                val detail = when {
                    alarm.hasMission -> alarm.routine.joinToString(" → ") { "${it.type.title.substringBefore(" (")} ${it.reps}" }
                    alarm.label.isNotBlank() -> alarm.label
                    else -> "운동 미션 없음"
                }
                views.setTextViewText(R.id.widget_detail, detail + streakText)
            }
            val open = PendingIntent.getActivity(
                ctx, 900, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}

package com.fitalarm.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fitalarm.app.R
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.KoreanSpeech
import com.fitalarm.app.core.RoutineStep
import com.fitalarm.app.core.VoiceStyle
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.data.BackupManager
import com.fitalarm.app.data.CalibrationRepository
import com.fitalarm.app.data.HolidayRepository
import com.fitalarm.app.data.SettingsRepository
import com.fitalarm.app.holiday.HolidaySync
import com.fitalarm.app.monetize.AdMob
import com.fitalarm.app.monetize.AdPlacement
import com.fitalarm.app.monetize.Monetization
import com.fitalarm.app.voice.Speaker
import com.fitalarm.app.widget.AlarmWidget
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 설정 탭: 운동, 음성, 공휴일, 백업, 위젯 */
class SettingsPage(host: MainActivity) : Page(host, AdPlacement.SETTINGS_BOTTOM) {
    private val ctx = host
    private lateinit var body: LinearLayout
    private var preview: Speaker? = null
    private var versionTaps = 0
    private var versionTapAt = 0L

    override val root: View = build()

    private fun dp(v: Number) = ctx.dp(v)

    private fun build(): View = with(ctx) {
        val content = vbox { setPadding(dp(20), dp(20), dp(20), dp(28)) }
        content.addView(label("설정", 30f, Palette.TEXT, Fonts.BOLD), lp())
        body = vbox()
        content.addView(body, lp().margins(t = dp(12)))
        ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    fun onPause() {
        preview?.shutdown()
        preview = null
    }

    override fun refresh() {
        body.removeAllViews()
        val s = SettingsRepository.get(ctx)
        with(ctx) {
            // ------------------------------------------------ 운동
            body.addView(card("운동") {
                addView(switchRow(
                    "보너스 운동 제안",
                    "알람을 끈 뒤 한 번만, 원할 때만 · 현재 강도 +${s.bonusLevel * 5}%",
                    s.bonusEnabled,
                ) { v -> SettingsRepository.update(ctx) { it.copy(bonusEnabled = v) } })
                val learned = CalibrationRepository.learnedCount(ctx)
                addView(switchRow(
                    "개인 보정",
                    "내 동작 범위에 맞춰 판정 기준을 조정해요. 최소 기준은 항상 유지" +
                        if (learned > 0) " · ${learned}개 동작 학습됨" else "",
                    s.calibration,
                ) { v -> SettingsRepository.update(ctx) { it.copy(calibration = v) } })
                val (resetRow, resetValue) = valueRow("보정 초기화") { confirmResetCalibration() }
                resetValue.text = if (learned > 0) "초기화" else "학습 전"
                addView(resetRow)
                val (practiceRow, practiceValue) = valueRow("운동 연습", "알람 없이 인식이 잘 되는지 확인") {
                    host.alarmsPage.showPracticePicker()
                }
                practiceValue.text = "시작"
                addView(practiceRow)
            }, lp())

            // ------------------------------------------------ 음성
            body.addView(card("음성") {
                addView(switchRow("오늘의 운동 음성 안내", "알람 소리를 줄이고 날짜와 오늘의 운동을 읽어줘요", s.voiceAnnounce) { v ->
                    SettingsRepository.update(ctx) { it.copy(voiceAnnounce = v) }
                })
                val (voiceRow, voiceValue) = valueRow("목소리") { chooseVoice() }
                voiceValue.text = s.voiceStyle.title.substringBefore(" 목소리")
                addView(voiceRow)
                addView(switchRow("1분마다 다시 안내", "알람을 끌 때까지 1분마다 한 번 더 읽어줘요", s.announceRepeat) { v ->
                    SettingsRepository.update(ctx) { it.copy(announceRepeat = v) }
                })
                addView(switchRow("운동 중 음성 카운트", "\"열하나, 열둘… 세 개 남았어요\"", s.voiceCount) { v ->
                    SettingsRepository.update(ctx) { it.copy(voiceCount = v) }
                })
            }, lp().margins(t = dp(12)))

            // ------------------------------------------------ 공휴일
            val snap = HolidayRepository.snapshot(ctx)
            body.addView(card("공휴일") {
                val remoteText = when {
                    !s.holidayAutoUpdate -> "끄면 앱 내장 규칙과 폰 달력만 사용해요"
                    snap.remoteAt == 0L -> "아직 받아오지 않았어요"
                    snap.remoteStatus == "error" -> "마지막 시도 실패 · ${whenText(snap.remoteAt)} 목록 사용 중"
                    else -> "구글 달력 · ${whenText(snap.remoteAt)} 업데이트 · 임시공휴일·선거일 반영"
                }
                addView(switchRow("공휴일 자동 업데이트", remoteText, s.holidayAutoUpdate) { v ->
                    SettingsRepository.update(ctx) { it.copy(holidayAutoUpdate = v) }
                    if (v) syncNow()
                })
                val hasPerm = HolidaySync.hasCalendarPermission(ctx)
                val deviceText = when {
                    !s.useDeviceCalendar -> "폰 기본 달력의 공휴일 달력을 함께 확인해요"
                    !hasPerm -> "캘린더 권한이 필요해요 (공휴일 달력만 읽고 개인 일정은 보지 않아요)"
                    snap.deviceSources.isEmpty() -> "이 폰에서 다른 앱이 읽을 수 있는 공휴일 달력을 찾지 못했어요"
                    else -> "감지된 공휴일 달력: " + snap.deviceSources.joinToString(", ")
                }
                addView(switchRow("폰 달력의 공휴일 사용", deviceText, s.useDeviceCalendar) { v ->
                    SettingsRepository.update(ctx) { it.copy(useDeviceCalendar = v) }
                    if (v && !HolidaySync.hasCalendarPermission(ctx)) {
                        ctx.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), MainActivity.REQ_CALENDAR)
                    } else if (v) syncNow()
                })
                val (syncRow, syncValue) = valueRow("지금 업데이트") { syncNow() }
                syncValue.text = "업데이트"
                addView(syncRow)
                val (daysRow, daysValue) = valueRow("내 휴무일 · 다가오는 공휴일") {
                    ctx.startActivity(Intent(ctx, DaysOffActivity::class.java))
                }
                daysValue.text = "${snap.daysOff.keys.count { !it.isBefore(LocalDate.now()) }}일"
                addView(daysRow)
            }, lp().margins(t = dp(12)))

            // ------------------------------------------------ 백업
            body.addView(card("백업") {
                addView(label("알람, 설정, 내 휴무일, 개인 보정, 기록을 파일 하나로 저장해요. 폰을 바꿀 때 사용하세요.", 13f, Palette.TEXT_DIM).apply {
                    setLineSpacing(0f, 1.2f)
                }, lp().margins(t = dp(4), b = dp(6)))
                addView(label("백업 파일은 암호화되지 않아요. 다른 사람에게 보내거나 공개된 곳에 올리지 마세요.", 12.5f, Palette.WARN).apply {
                    setLineSpacing(0f, 1.2f)
                }, lp().margins(b = dp(10)))
                addView(hbox {
                    addView(pillButton("백업 내보내기", filled = false, heightDp = 46) { exportBackup() }, lp(0, WRAP, 1f).margins(r = dp(5)))
                    addView(pillButton("백업에서 복원", filled = false, heightDp = 46) { importBackup() }, lp(0, WRAP, 1f).margins(l = dp(5)))
                }, lp().margins(b = dp(8)))
            }, lp().margins(t = dp(12)))

            // ------------------------------------------------ 위젯
            body.addView(card("홈 화면 위젯") {
                addView(hbox {
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Palette.ACCENT_SOFT, Palette.SKY_SOFT),
                    ).apply { cornerRadius = dpf(20) }
                    addView(android.widget.FrameLayout(this@with).apply {
                        background = rounded(Palette.ACCENT, dpf(14))
                        addView(icon(R.drawable.ic_alarm, Palette.ON_ACCENT, 22), android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                    }, lp(dp(42), dp(42)).margins(r = dp(12)))
                    addView(vbox {
                        addView(label("다음 알람", 12f, Palette.ON_ACCENT_SOFT, Fonts.MEDIUM))
                        addView(label(nextAlarmText(), 22f, Palette.TEXT, Fonts.LIGHT))
                    }, lp(0, WRAP, 1f))
                }, lp().margins(t = dp(6), b = dp(10)))
                addView(pillButton("홈 화면에 위젯 추가", filled = true, heightDp = 46) { pinWidget() }, lp().margins(b = dp(8)))
            }, lp().margins(t = dp(12)))

            // ------------------------------------------------ 응원(후원) (켜기 전에는 보이지 않음)
            if (Monetization.supportVisible(this)) {
                body.addView(host.supportCard { host.refreshCurrent() }, lp().margins(t = dp(12)))
            }

            // ------------------------------------------------ 정보
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                ""
            }
            body.addView(card("정보") {
                val (policyRow, policyValue) = valueRow(
                    "개인정보처리방침",
                    "카메라 영상과 기록은 기기 안에서만 처리돼요",
                ) { openPrivacyPolicy() }
                policyValue.text = "보기"
                addView(policyRow)
                val (termsRow, termsValue) = valueRow("이용약관", "운동 안전과 알람의 한계 안내 포함") { openUrl(OnboardingActivity.TERMS_URL) }
                termsValue.text = "보기"
                addView(termsRow)
                // 유럽 등 동의가 필요한 지역에서만 보임 (Google 동의 관리 요건)
                if (AdMob.privacyOptionsRequired(host)) {
                    val (adRow, adValue) = valueRow("광고 개인정보 설정", "맞춤 광고 동의를 바꿀 수 있어요") { AdMob.showPrivacyOptions(host) }
                    adValue.text = "열기"
                    addView(adRow)
                }
                val (versionRow, versionValue) = valueRow("버전") { onVersionTapped() }
                versionValue.text = version ?: ""
                versionValue.setTextColor(Palette.TEXT_DIM)
                addView(versionRow)
            }, lp().margins(t = dp(12)))

            if (Monetization.isPreview(this)) {
                body.addView(host.monetizePreviewCard { host.refreshCurrent() }, lp().margins(t = dp(12)))
            }

            // ------------------------------------------------ 광고 자리: 설정 화면 맨 아래, 버튼이 있는 카드와 간격을 둠
            body.addView(adSlot, lp().margins(t = dp(20)))
            adSlot.refresh()
        }
    }

    /** 개발자용: 버전을 3초 안에 7번 누르면 수익화 미리보기 켜고 끄기 */
    private fun onVersionTapped() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - versionTapAt > 3_000L) versionTaps = 0
        versionTapAt = now
        versionTaps++
        if (versionTaps >= 7) {
            versionTaps = 0
            val on = !Monetization.isPreview(ctx)
            Monetization.setPreview(ctx, on)
            ctx.toast(if (on) "수익화 미리보기를 켰어요 (개발자용)" else "수익화 미리보기를 껐어요")
            refresh()
        }
    }

    private fun openPrivacyPolicy() = openUrl(PRIVACY_POLICY_URL)

    private fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            ctx.toast("브라우저를 열 수 없어요. $url")
        }
    }

    private fun nextAlarmText(): String {
        val scheduler = AlarmScheduler(ctx)
        val at = AlarmRepository.getAll(ctx).mapNotNull { scheduler.nextTrigger(it) }.minOrNull() ?: return "켜진 알람 없음"
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return AlarmMath.relativeDay(t.toLocalDate(), LocalDate.now()) + " " +
            AlarmMath.formatTime(t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(ctx))
    }

    private fun whenText(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val day = AlarmMath.relativeDay(t.toLocalDate(), LocalDate.now())
        return "$day " + AlarmMath.formatTime(t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(ctx))
    }

    private fun syncNow() {
        ctx.toast("공휴일 목록을 확인하는 중…")
        HolidaySync.syncAsync(ctx) { r ->
            val parts = ArrayList<String>()
            r.remoteCount?.let { parts.add("구글 달력 ${it}일") }
            r.deviceCount?.let { parts.add("폰 달력 ${it}일") }
            ctx.toast(if (parts.isEmpty()) r.message.ifBlank { "업데이트할 출처가 없어요" } else "업데이트 완료 · " + parts.joinToString(", "))
            refresh()
        }
    }

    private fun confirmResetCalibration() {
        AlertDialog.Builder(ctx)
            .setTitle("개인 보정 초기화")
            .setMessage("학습한 동작 범위를 지우고 기본 기준으로 돌아갈까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("초기화") { _, _ ->
                CalibrationRepository.clear(ctx)
                ctx.toast("기본 기준으로 돌아갔어요")
                refresh()
            }
            .show()
    }

    private fun chooseVoice() {
        val styles = VoiceStyle.entries
        val current = SettingsRepository.get(ctx).voiceStyle
        AlertDialog.Builder(ctx)
            .setTitle("목소리")
            .setSingleChoiceItems(styles.map { it.title }.toTypedArray(), styles.indexOf(current)) { d, which ->
                val chosen = styles[which]
                SettingsRepository.update(ctx) { it.copy(voiceStyle = chosen) }
                d.dismiss()
                speakSample(chosen)
                refresh()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 고른 목소리로 예시 안내를 들려줌 */
    private fun speakSample(style: VoiceStyle) {
        preview?.shutdown()
        val sample = KoreanSpeech.announcement(
            LocalDateTime.now().withHour(6).withMinute(30), "",
            listOf(RoutineStep(ExerciseType.SQUAT, 15), RoutineStep(ExerciseType.JUMPING_JACK, 20)),
            hasMission = true, isSnooze = false,
        )
        var sp: Speaker? = null
        sp = Speaker(ctx, AudioAttributes.USAGE_MEDIA, style) { ok ->
            val s = sp ?: return@Speaker
            if (!ok) {
                ctx.toast("이 폰에서 한국어 음성을 찾지 못했어요. 설정 > 텍스트 음성 변환에서 한국어를 받아주세요")
                return@Speaker
            }
            if (style.wantMale && !s.matchedStyle) {
                ctx.toast("남성 음성이 없어 기본 음성의 음을 낮췄어요. 음성 엔진 설정에서 남성 음성을 받을 수 있어요")
            }
            s.speak(sample)
        }
        preview = sp
    }

    // ------------------------------------------------------------------ 백업

    /** 내보내기 전에 파일에 담기는 내용과 주의점을 알려줌 */
    private fun exportBackup() {
        AlertDialog.Builder(ctx)
            .setTitle("백업 파일 내보내기")
            .setMessage(
                "백업 파일에는 알람 설정, 휴무일 메모, 날짜별 운동 기록이 암호화 없이 그대로 담겨요.\n\n" +
                    "• 내 기기나 내 클라우드처럼 나만 볼 수 있는 곳에 저장하세요\n" +
                    "• 다른 사람에게 보내거나 공개된 곳에 올리지 마세요\n" +
                    "• 필요 없어진 백업 파일은 지워 주세요"
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("저장 위치 고르기") { _, _ -> launchExport() }
            .show()
    }

    private fun launchExport() {
        val name = "fitalarm-backup-${LocalDate.now()}.json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            ctx.startActivityForResult(intent, MainActivity.REQ_BACKUP_EXPORT)
        } catch (e: Exception) {
            ctx.toast("파일 저장 화면을 열 수 없습니다")
        }
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        try {
            ctx.startActivityForResult(intent, MainActivity.REQ_BACKUP_IMPORT)
        } catch (e: Exception) {
            ctx.toast("파일 선택 화면을 열 수 없습니다")
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            MainActivity.REQ_BACKUP_EXPORT -> try {
                val s = BackupManager.export(ctx, uri)
                ctx.toast("백업 완료 · 알람 ${s.alarms}개, 기록 ${s.records}개")
            } catch (e: Exception) {
                ctx.toast("백업에 실패했어요")
            }
            MainActivity.REQ_BACKUP_IMPORT -> try {
                val (root, s) = BackupManager.inspect(ctx, uri)
                AlertDialog.Builder(ctx)
                    .setTitle("백업에서 복원")
                    .setMessage("알람 ${s.alarms}개, 기록 ${s.records}개, 휴무일 ${s.daysOff}일로 바꿀까요?\n지금 있는 데이터는 지워져요.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("복원") { _, _ ->
                        val scheduler = AlarmScheduler(ctx)
                        AlarmRepository.getAll(ctx).forEach { scheduler.cancel(it.id) }
                        BackupManager.restore(ctx, root)
                        scheduler.rescheduleAll()
                        ctx.toast("복원했어요")
                        host.refreshCurrent()
                    }
                    .show()
            } catch (e: Exception) {
                ctx.toast(e.message ?: "백업 파일을 읽을 수 없어요")
            }
        }
    }

    // ------------------------------------------------------------------ 위젯

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(ctx)
        if (manager != null && manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(ctx, AlarmWidget::class.java), null, null)
        } else {
            ctx.toast("홈 화면을 길게 누르고 위젯 > 운동 알람을 선택하세요")
        }
    }

    companion object {
        /** Play Console 에 입력하는 개인정보처리방침 주소와 같아야 함 */
        const val PRIVACY_POLICY_URL = "https://github.com/StateBasement/FitAlarm/blob/main/docs/privacy-policy.md"
    }
}

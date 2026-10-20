package com.fitalarm.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.alarm.AlarmService
import com.fitalarm.app.alarm.RingState
import com.fitalarm.app.camera.PoseCamera
import com.fitalarm.app.core.BonusPlan
import com.fitalarm.app.core.BonusPlanner
import com.fitalarm.app.core.CalibrationBook
import com.fitalarm.app.core.KoreanSpeech
import com.fitalarm.app.core.MissionSession
import com.fitalarm.app.core.MissionUpdate
import com.fitalarm.app.core.PoseFrame
import com.fitalarm.app.core.RecordEntry
import com.fitalarm.app.core.RecordKind
import com.fitalarm.app.core.RoutineCodec
import com.fitalarm.app.core.RoutineStep
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.data.CalibrationRepository
import com.fitalarm.app.data.RecordRepository
import com.fitalarm.app.data.SettingsRepository
import com.fitalarm.app.defaultVibrator
import com.fitalarm.app.voice.Speaker
import com.fitalarm.app.widget.AlarmWidget
import java.time.LocalDate

/**
 * 카메라로 운동 동작을 인식해 횟수를 세는 화면.
 *  - ALARM: 루틴을 끝내면 알람이 꺼짐. 미션 중에는 알람 소리가 작아지고 진동이 멈춤
 *  - PRACTICE: 알람과 무관하게 인식 확인
 *  - BONUS: 알람을 끈 뒤 원할 때만 하는 추가 운동 (언제든 그만둘 수 있음)
 */
class MissionActivity : Activity(), PoseCamera.Listener {

    private enum class Mode { ALARM, PRACTICE, BONUS }

    private var mode = Mode.ALARM
    private var alarmId = -1
    private lateinit var routine: List<RoutineStep>
    private lateinit var session: MissionSession
    private lateinit var book: CalibrationBook
    private var camera: PoseCamera? = null
    private var speaker: Speaker? = null
    private var tone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    private var finished = false
    private var permissionAsked = false
    private var bannerText = ""
    private var bannerUntil = 0L
    private var shownStepIndex = -1
    private var startedAt = 0L
    private var bonusPlan: BonusPlan? = null
    private var bonusRecorded = false

    // ---- 뷰 ----
    private lateinit var previewFrame: AspectRatioFrameLayout
    private lateinit var textureView: TextureView
    private lateinit var overlay: SkeletonOverlayView
    private lateinit var stepText: TextView
    private lateinit var calibChip: TextView
    private lateinit var countText: TextView
    private lateinit var targetText: TextView
    private lateinit var voiceBubble: TextView
    private lateinit var progressBar: ProgressBarView
    private lateinit var totalText: TextView
    private lateinit var hintText: TextView
    private lateinit var guideText: TextView
    private lateinit var voiceSwitch: Switch
    private lateinit var fallbackButton: TextView
    private lateinit var exitButton: TextView
    private lateinit var doneOverlay: LinearLayout
    private lateinit var doneTitle: TextView
    private lateinit var doneSub: TextView
    private lateinit var doneClose: TextView
    private lateinit var bonusLayer: FrameLayout

    private val ringListener: () -> Unit = {
        // 알람이 시간 초과·다시 알림 등으로 이미 끝났으면 미션 화면도 닫음
        if (mode == Mode.ALARM && !finished && RingState.ringingAlarmId != alarmId) finish()
    }
    private val holdToDismiss = Runnable { completeAlarmMission(viaFallback = true) }
    private val bonusAutoClose = Runnable { closeBonusOffer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        val practiceRoutine = intent.getStringExtra(EXTRA_PRACTICE_ROUTINE)
        mode = if (practiceRoutine != null) Mode.PRACTICE else Mode.ALARM
        alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)

        if (mode == Mode.PRACTICE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else showOverLockScreen()
        setupEdgeToEdge(lightBars = false)

        routine = if (mode == Mode.PRACTICE) RoutineCodec.decode(practiceRoutine)
        else AlarmRepository.get(this, alarmId)?.routine.orEmpty()
        if (routine.isEmpty()) {
            // 미션 정보가 없으면 사용자가 갇히지 않도록 알람을 끔
            if (mode == Mode.ALARM && alarmId > 0) startService(AlarmService.dismissAfterMissionIntent(this, alarmId))
            finish()
            return
        }
        book = CalibrationRepository.load(this)
        session = MissionSession(routine, book)
        startedAt = SystemClock.elapsedRealtime()

        setContentView(buildUi())
        camera = PoseCamera(this, textureView, this)
        val usage = if (mode == Mode.PRACTICE) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        speaker = Speaker(this, usage, SettingsRepository.get(this).voiceStyle)
        tone = try {
            ToneGenerator(if (mode == Mode.PRACTICE) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM, 70)
        } catch (e: RuntimeException) {
            null
        }
        render(session.snapshot(routine.first().type.setupGuide))
    }

    override fun onResume() {
        super.onResume()
        if (!::session.isInitialized || finished) return
        if (mode == Mode.ALARM) {
            if (RingState.ringingAlarmId != alarmId) {
                finish()
                return
            }
            RingState.addListener(ringListener)
            startService(AlarmService.missionStateIntent(this, true))
        }
        startCameraOrAsk()
    }

    override fun onPause() {
        camera?.stop()
        handler.removeCallbacks(holdToDismiss)
        if (::book.isInitialized) CalibrationRepository.save(this, book)
        if (mode == Mode.ALARM && ::session.isInitialized) {
            RingState.removeListener(ringListener)
            if (!finished) startService(AlarmService.missionStateIntent(this, false))
        }
        super.onPause()
    }

    override fun onDestroy() {
        // 보너스 운동을 하다가 뒤로 가기 등으로 나가면 "그만둠"으로 기록 (다음 제안 강도 한 단계 낮춤)
        if (mode == Mode.BONUS && !finished) recordBonusQuit()
        camera?.release()
        camera = null
        speaker?.shutdown()
        speaker = null
        tone?.release()
        tone = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startCameraOrAsk() {
        when {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> camera?.start()
            !permissionAsked -> {
                permissionAsked = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
            else -> showCameraUnavailable("카메라 권한이 없습니다")
        }
    }

    // ------------------------------------------------------------------ 카메라 콜백

    override fun onCameraReady(uprightWidth: Int, uprightHeight: Int, mirrored: Boolean) {
        previewFrame.setAspect(uprightWidth, uprightHeight)
        fallbackButton.visibility = View.GONE
    }

    override fun onPose(frame: PoseFrame?, uprightWidth: Int, uprightHeight: Int, mirrored: Boolean) {
        if (finished) return
        val u = session.update(frame, SystemClock.elapsedRealtime())
        overlay.setPose(frame, uprightWidth, uprightHeight, mirrored, u.tracking)

        if (u.counted) {
            countFeedback(u)
            if (mode == Mode.ALARM) startService(AlarmService.missionProgressIntent(this))
        }
        if (u.allCompleted) {
            render(u)
            when (mode) {
                Mode.ALARM -> completeAlarmMission(viaFallback = false)
                Mode.PRACTICE -> completePractice()
                Mode.BONUS -> completeBonus()
            }
            return
        }
        if (u.stepCompleted) {
            val next = session.routine[session.stepIndex]
            showBanner("✓ ${u.step.type.title} 완료!  다음은 ${next.type.title} ${next.reps}회")
            say(KoreanSpeech.stepDone(u.step.type, next))
            render(session.snapshot(next.type.setupGuide))
        } else {
            render(u)
        }
    }

    override fun onCameraError(message: String) {
        showCameraUnavailable(message)
    }

    // ------------------------------------------------------------------ 화면

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF1F2A25.toInt()) }

        previewFrame = AspectRatioFrameLayout(this)
        textureView = TextureView(this)
        overlay = SkeletonOverlayView(this)
        previewFrame.addView(textureView, FrameLayout.LayoutParams(MATCH, MATCH))
        previewFrame.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(previewFrame, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        val stack = FrameLayout(this)
        root.addView(stack, FrameLayout.LayoutParams(MATCH, MATCH))

        // 위: 진행 카드
        val top = vbox {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = rounded(Palette.SURFACE, dpf(28))
            elevation = dpf(6)
        }
        top.addView(hbox {
            stepText = label("", 14f, Palette.TEXT_DIM, Fonts.MEDIUM)
            addView(stepText, lp(0, WRAP, 1f))
            calibChip = chip("내 동작 범위에 맞춤", Palette.ACCENT_SOFT, Palette.ON_ACCENT_SOFT, 12f)
            addView(calibChip)
        }, lp())
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            countText = label("0", 76f, Palette.ACCENT, Fonts.BOLD)
            targetText = label("", 26f, Palette.TEXT_DIM, Fonts.LIGHT)
            addView(countText, lp(WRAP, WRAP))
            addView(targetText, lp(WRAP, WRAP).margins(l = dp(8), b = dp(10)))
            addView(spacer())
            voiceBubble = chip("", Palette.WARM_SOFT, Palette.ON_WARM, 16f).apply {
                visibility = View.INVISIBLE
                compoundDrawablePadding = dp(6)
                getDrawable(R.drawable.ic_speaker)?.mutate()?.let { d ->
                    d.setTint(Palette.WARM)
                    d.setBounds(0, 0, dp(18), dp(18))
                    setCompoundDrawables(d, null, null, null)
                }
            }
            addView(voiceBubble, lp(WRAP, WRAP).margins(b = dp(12)))
        }, lp().margins(t = dp(2)))
        progressBar = ProgressBarView(this).apply { trackColor = Palette.SURFACE_HI }
        top.addView(progressBar, lp(MATCH, dp(10)).margins(t = dp(4)))
        totalText = label("", 12.5f, Palette.TEXT_DIM)
        top.addView(totalText, lp().margins(t = dp(6)))

        val topWrap = vbox {
            setPadding(dp(14), dp(14), dp(14), 0)
            addView(top, lp())
            hintText = label("", 17f, Palette.TEXT, Fonts.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = rounded(if (Palette.isDark) 0xE616241F.toInt() else 0xF0FFFFFF.toInt(), dpf(18))
                setLineSpacing(0f, 1.15f)
            }
            addView(hintText, lp(WRAP, WRAP).margins(t = dp(12)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        stack.addView(topWrap, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // 아래: 안내 시트
        val bottom = vbox {
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawableTop(Palette.SURFACE, dpf(28))
        }
        bottom.addView(hbox {
            gravity = Gravity.TOP
            addView(icon(R.drawable.ic_phone, Palette.ACCENT, 20), lp(dp(20), dp(20)).margins(r = dp(10), t = dp(2)))
            guideText = label("", 14f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.3f) }
            addView(guideText, lp(0, WRAP, 1f))
        }, lp())
        voiceSwitch = Switch(this).apply {
            isChecked = SettingsRepository.get(this@MissionActivity).voiceCount
            setOnCheckedChangeListener { _, c ->
                SettingsRepository.update(this@MissionActivity) { it.copy(voiceCount = c) }
                if (!c) speaker?.stop()
            }
        }
        bottom.addView(hbox {
            setPadding(0, dp(6), 0, dp(6))
            addView(label("음성 카운트", 15f), lp(0, WRAP, 1f))
            addView(voiceSwitch)
        }, lp().margins(t = dp(8)))
        fallbackButton = buildFallbackButton()
        bottom.addView(fallbackButton, lp().margins(t = dp(6)))
        exitButton = pillButton(exitLabel(), filled = false, heightDp = 52) { onExit() }
        bottom.addView(exitButton, lp().margins(t = dp(8)))
        stack.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // 완료 화면
        doneOverlay = vbox {
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            setBackgroundColor(Palette.BG)
            visibility = View.GONE
            isClickable = true
        }
        doneOverlay.addView(icon(R.drawable.ic_check_circle, Palette.ACCENT, 72))
        doneTitle = label("미션 완료!", 34f, Palette.ACCENT, Fonts.BOLD).apply { gravity = Gravity.CENTER }
        doneSub = label("", 17f, Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        doneClose = pillButton("닫기", filled = true) { finish() }
        doneOverlay.addView(doneTitle, lp(WRAP, WRAP).margins(t = dp(12)))
        doneOverlay.addView(doneSub, lp(WRAP, WRAP).margins(t = dp(10), b = dp(32)))
        doneOverlay.addView(doneClose, lp())
        stack.addView(doneOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // 보너스 제안 (필요할 때 채움)
        bonusLayer = FrameLayout(this).apply { visibility = View.GONE }
        stack.addView(bonusLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        stack.applySystemBarPadding()
        return root
    }

    @Suppress("FunctionName")
    private fun GradientDrawableTop(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    private fun exitLabel() = when (mode) {
        Mode.ALARM -> "알람 화면으로"
        Mode.PRACTICE -> "연습 그만하기"
        Mode.BONUS -> "그만하기"
    }

    private fun onExit() {
        if (mode == Mode.BONUS && !finished) {
            finished = true
            recordBonusQuit()
        }
        finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFallbackButton(): TextView =
        pillButton("카메라를 쓸 수 없어요 · 3초간 누르고 있으면 알람이 꺼져요", filled = false, heightDp = 52) {
            toast("버튼을 3초간 누르고 있으세요")
        }.apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(Palette.WARN)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        this@MissionActivity.handler.postDelayed(holdToDismiss, HOLD_TO_DISMISS_MS)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        this@MissionActivity.handler.removeCallbacks(holdToDismiss)
                        if (ev.actionMasked == MotionEvent.ACTION_UP && !finished) v.performClick()
                        true
                    }
                    else -> false
                }
            }
        }

    private fun render(u: MissionUpdate) {
        val total = session.routine.size
        val prefix = if (mode == Mode.BONUS) "보너스 · " else ""
        stepText.text = prefix + if (total > 1) "${u.stepIndex + 1} / ${total}단계 · ${u.step.type.title}" else u.step.type.title
        countText.text = u.stepCount.toString()
        targetText.text = "/ ${u.step.reps}회"
        progressBar.progress = if (u.totalReps == 0) 0f else u.doneReps.toFloat() / u.totalReps
        totalText.text = "전체 ${u.totalReps}회 중 ${u.doneReps}회"
        calibChip.visibility = if (book.enabled) View.VISIBLE else View.GONE
        val now = SystemClock.elapsedRealtime()
        hintText.text = if (now < bannerUntil) bannerText else u.hint
        hintText.setTextColor(
            when {
                now < bannerUntil -> Palette.ACCENT
                u.tracking -> Palette.TEXT
                else -> Palette.WARN
            }
        )
        if (shownStepIndex != u.stepIndex) {
            shownStepIndex = u.stepIndex
            guideText.text = "${u.step.type.setupGuide}\n1회 기준: ${u.step.type.motionGuide}"
        }
    }

    private fun showBanner(text: String) {
        bannerText = text
        bannerUntil = SystemClock.elapsedRealtime() + BANNER_MS
    }

    private fun voiceOn() = ::voiceSwitch.isInitialized && voiceSwitch.isChecked

    private fun say(text: String) {
        if (voiceOn()) speaker?.speak(text)
    }

    private fun countFeedback(u: MissionUpdate) {
        countText.animate().cancel()
        countText.scaleX = 1.25f
        countText.scaleY = 1.25f
        countText.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        val v = defaultVibrator()
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

        if (voiceOn() && speaker?.ready == true) {
            val phrase = KoreanSpeech.countPhrase(u.stepCount, u.step.reps)
            voiceBubble.text = "“${phrase.substringBefore(",")}!”"
            voiceBubble.visibility = View.VISIBLE
            if (!u.stepCompleted) speaker?.speak(phrase)
        } else {
            voiceBubble.visibility = View.INVISIBLE
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        }
    }

    private fun showCameraUnavailable(message: String) {
        hintText.text = message
        hintText.setTextColor(Palette.WARN)
        // 알람 모드에서 카메라를 쓸 수 없으면 갇히지 않도록 길게 눌러 끄는 대비책 제공
        if (mode == Mode.ALARM && !finished) fallbackButton.visibility = View.VISIBLE
    }

    private fun showDone(title: String, sub: String, closeButton: Boolean) {
        doneTitle.text = title
        doneSub.text = sub
        doneClose.visibility = if (closeButton) View.VISIBLE else View.GONE
        doneOverlay.visibility = View.VISIBLE
        doneOverlay.alpha = 0f
        doneOverlay.animate().alpha(1f).setDuration(250).start()
    }

    // ------------------------------------------------------------------ 완료 처리

    private fun completeAlarmMission(viaFallback: Boolean) {
        if (finished) return
        finished = true
        camera?.stop()
        handler.removeCallbacks(holdToDismiss)
        RingState.removeListener(ringListener)

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val entry = if (viaFallback) {
            RecordEntry(LocalDate.now(), RecordKind.DISMISS, alarmId = alarmId, atMillis = System.currentTimeMillis())
        } else {
            RecordEntry(
                LocalDate.now(), RecordKind.MISSION,
                routine.associate { it.type to it.reps }, elapsed, alarmId, System.currentTimeMillis(),
            )
        }
        RecordRepository.append(this, entry)
        AlarmWidget.refresh(this)
        startService(AlarmService.dismissAfterMissionIntent(this, alarmId))

        if (viaFallback) {
            showDone("알람 해제", "카메라 없이 알람을 껐어요", closeButton = false)
            handler.postDelayed({ finish() }, DONE_SCREEN_MS)
            return
        }
        say("미션 완료! 좋은 하루 보내세요.")
        val settings = SettingsRepository.get(this)
        val plan = if (settings.bonusEnabled) BonusPlanner.plan(routine, settings.bonusLevel, settings.lastBonus) else null
        if (plan == null) {
            showDone("미션 완료!", "좋은 아침이에요.\n오늘도 힘차게 시작해요!", closeButton = false)
            handler.postDelayed({ finish() }, DONE_SCREEN_MS)
        } else {
            showDone("미션 완료!", "좋은 아침이에요.", closeButton = false)
            showBonusOffer(plan)
        }
    }

    private fun completePractice() {
        if (finished) return
        finished = true
        camera?.stop()
        CalibrationRepository.save(this, book)
        say("연습 완료!")
        showDone("연습 완료!", "인식이 잘 되었다면 이 자리 그대로\n알람 때 사용하면 돼요", closeButton = true)
    }

    // ------------------------------------------------------------------ 보너스 운동

    private fun showBonusOffer(plan: BonusPlan) {
        bonusPlan = plan
        bonusLayer.removeAllViews()
        bonusLayer.setBackgroundColor(0x7310231A)
        bonusLayer.isClickable = true
        bonusLayer.setOnClickListener { closeBonusOffer() }   // 바깥을 누르면 닫힘

        val sheet = vbox {
            setPadding(dp(22), dp(12), dp(22), dp(22))
            background = GradientDrawableTop(Palette.SURFACE, dpf(30))
            isClickable = true                                     // 시트 안 터치는 닫지 않음
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) restartBonusTimer()
                false
            }
        }
        sheet.addView(View(this).apply { background = rounded(Palette.OUTLINE, dpf(3)) },
            lp(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sheet.addView(label("보너스 운동 · 원할 때만", 13f, Palette.WARM, Fonts.BOLD), lp().margins(t = dp(14)))
        sheet.addView(label("한 세트 더 해볼까요?", 24f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(6)))
        sheet.addView(label(plan.headline, 15f, Palette.TEXT_DIM), lp().margins(t = dp(4)))

        val name = plan.step.type.title.substringBefore(" (")
        sheet.addView(hbox {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Palette.BG, dpf(20), Palette.OUTLINE, dp(1))
            addView(FrameLayout(this@MissionActivity).apply {
                background = rounded(Palette.ACCENT_SOFT, dpf(16))
                addView(icon(R.drawable.ic_flame, Palette.ACCENT, 28), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            }, lp(dp(52), dp(52)).margins(r = dp(14)))
            addView(vbox {
                addView(label("$name ${plan.step.reps}회", 19f, Palette.TEXT, Fonts.BOLD))
                addView(label("약 ${plan.minutes}분 · 방금 한 운동과 비슷한 강도", 13f, Palette.TEXT_DIM), lp().margins(t = dp(2)))
            }, lp(0, WRAP, 1f))
        }, lp().margins(t = dp(16)))

        val slider = SlideToStartView(this, "밀어서 시작") { startBonus() }.apply {
            onTouchStart = { handler.removeCallbacks(bonusAutoClose) }
        }
        sheet.addView(slider, lp(MATCH, dp(64)).margins(t = dp(16)))
        sheet.addView(pillButton("오늘은 괜찮아요", filled = false, heightDp = 58) { closeBonusOffer() }.apply {
            textSize = 17f
        }, lp().margins(t = dp(12)))
        sheet.addView(label("바깥을 눌러도 닫혀요 · 15초 뒤 저절로 닫혀요", 13f, Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        }, lp().margins(t = dp(12)))

        bonusLayer.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        bonusLayer.visibility = View.VISIBLE
        sheet.translationY = dpf(400)
        sheet.animate().translationY(0f).setDuration(280).start()
        restartBonusTimer()
    }

    private fun restartBonusTimer() {
        handler.removeCallbacks(bonusAutoClose)
        handler.postDelayed(bonusAutoClose, BONUS_AUTO_CLOSE_MS)
    }

    private fun closeBonusOffer() {
        handler.removeCallbacks(bonusAutoClose)
        finish()
    }

    private fun startBonus() {
        val plan = bonusPlan ?: return
        handler.removeCallbacks(bonusAutoClose)
        SettingsRepository.update(this) { it.copy(lastBonus = plan.step.type) }
        mode = Mode.BONUS
        routine = listOf(plan.step)
        session = MissionSession(routine, book)
        startedAt = SystemClock.elapsedRealtime()
        finished = false
        bonusRecorded = false
        shownStepIndex = -1
        bonusLayer.visibility = View.GONE
        doneOverlay.visibility = View.GONE
        fallbackButton.visibility = View.GONE
        exitButton.text = exitLabel()
        say("보너스 운동을 시작해요. ${plan.step.type.title.substringBefore(" (")} ${plan.step.reps}회예요.")
        render(session.snapshot(plan.step.type.setupGuide))
        camera?.start()
    }

    private fun completeBonus() {
        if (finished) return
        finished = true
        camera?.stop()
        val plan = bonusPlan
        if (plan != null && !bonusRecorded) {
            bonusRecorded = true
            RecordRepository.append(
                this,
                RecordEntry(
                    LocalDate.now(), RecordKind.BONUS, mapOf(plan.step.type to plan.step.reps),
                    SystemClock.elapsedRealtime() - startedAt, alarmId, System.currentTimeMillis(),
                ),
            )
            SettingsRepository.update(this) { it.copy(bonusLevel = BonusPlanner.nextLevel(it.bonusLevel, completed = true)) }
        }
        CalibrationRepository.save(this, book)
        say("보너스 완료! 정말 멋져요.")
        showDone("보너스 완료!", "오늘 운동 끝!\n좋은 하루 보내세요", closeButton = true)
        handler.postDelayed({ if (!isFinishing) finish() }, DONE_SCREEN_MS * 2)
    }

    private fun recordBonusQuit() {
        if (bonusRecorded) return
        bonusRecorded = true
        val plan = bonusPlan ?: return
        RecordRepository.append(
            this,
            RecordEntry(LocalDate.now(), RecordKind.BONUS_QUIT, mapOf(plan.step.type to 0), alarmId = alarmId, atMillis = System.currentTimeMillis()),
        )
        SettingsRepository.update(this) { it.copy(bonusLevel = BonusPlanner.nextLevel(it.bonusLevel, completed = false)) }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_PRACTICE_ROUTINE = "practice_routine"
        private const val REQ_CAMERA = 31
        private const val BANNER_MS = 2_500L
        private const val DONE_SCREEN_MS = 2_000L
        private const val HOLD_TO_DISMISS_MS = 3_000L
        private const val BONUS_AUTO_CLOSE_MS = 15_000L
    }
}

package com.fitalarm.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import com.fitalarm.app.alarm.AlarmService
import com.fitalarm.app.alarm.RingState
import com.fitalarm.app.camera.PoseCamera
import com.fitalarm.app.core.MissionSession
import com.fitalarm.app.core.MissionUpdate
import com.fitalarm.app.core.PoseFrame
import com.fitalarm.app.core.RoutineCodec
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.defaultVibrator

/**
 * 카메라로 운동 동작을 인식해 횟수를 세는 미션 화면.
 * - 알람 모드: 루틴을 끝내면 알람이 꺼짐. 미션 중에는 알람 소리가 작아지고 진동이 멈춤
 *   (45초 동안 진행이 없으면 다시 원래 크기로)
 * - 연습 모드: 알람과 무관하게 인식 정확도와 자리 잡기를 미리 확인
 */
class MissionActivity : Activity(), PoseCamera.Listener {

    private var alarmId = -1
    private var practice = false
    private lateinit var session: MissionSession
    private var camera: PoseCamera? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var permissionAsked = false
    private var bannerText = ""
    private var bannerUntil = 0L
    private var shownStepIndex = -1
    private var tone: ToneGenerator? = null

    private lateinit var previewFrame: AspectRatioFrameLayout
    private lateinit var textureView: TextureView
    private lateinit var overlay: SkeletonOverlayView
    private lateinit var stepText: TextView
    private lateinit var countText: TextView
    private lateinit var targetText: TextView
    private lateinit var progressBar: ProgressBarView
    private lateinit var hintText: TextView
    private lateinit var guideText: TextView
    private lateinit var fallbackButton: TextView
    private lateinit var doneOverlay: LinearLayout
    private lateinit var doneTitle: TextView
    private lateinit var doneSub: TextView
    private lateinit var doneClose: TextView

    private val ringListener: () -> Unit = {
        // 알람이 시간 초과·다시 알림 등으로 이미 끝났으면 미션 화면도 닫음
        if (!practice && !finished && RingState.ringingAlarmId != alarmId) finish()
    }
    private val holdToDismiss = Runnable { completeMission(viaFallback = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val practiceRoutine = intent.getStringExtra(EXTRA_PRACTICE_ROUTINE)
        practice = practiceRoutine != null
        alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)

        if (practice) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else showOverLockScreen()
        setupEdgeToEdge()

        val routine = if (practice) RoutineCodec.decode(practiceRoutine)
        else AlarmRepository.get(this, alarmId)?.routine.orEmpty()
        if (routine.isEmpty()) {
            // 미션 정보가 없으면 사용자가 갇히지 않도록 알람을 끔
            if (!practice && alarmId > 0) startService(AlarmService.dismissAfterMissionIntent(this, alarmId))
            finish()
            return
        }
        session = MissionSession(routine)
        setContentView(buildUi())
        camera = PoseCamera(this, textureView, this)
        tone = try {
            ToneGenerator(if (practice) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM, 70)
        } catch (e: RuntimeException) {
            null
        }
        render(session.snapshot(routine.first().type.setupGuide))
    }

    override fun onResume() {
        super.onResume()
        if (finished || !::session.isInitialized) return
        if (!practice) {
            if (RingState.ringingAlarmId != alarmId) {
                finish()
                return
            }
            RingState.addListener(ringListener)
            startService(AlarmService.missionStateIntent(this, true))
        }
        when {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> camera?.start()
            !permissionAsked -> {
                permissionAsked = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
            else -> showCameraUnavailable("카메라 권한이 없습니다")
        }
    }

    override fun onPause() {
        camera?.stop()
        handler.removeCallbacks(holdToDismiss)
        if (!practice && ::session.isInitialized) {
            RingState.removeListener(ringListener)
            if (!finished) startService(AlarmService.missionStateIntent(this, false))
        }
        super.onPause()
    }

    override fun onDestroy() {
        camera?.release()
        camera = null
        tone?.release()
        tone = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
            countFeedback()
            if (!practice) startService(AlarmService.missionProgressIntent(this))
        }
        if (u.allCompleted) {
            render(u)
            completeMission(viaFallback = false)
            return
        }
        if (u.stepCompleted) {
            val next = session.routine[session.stepIndex]
            showBanner("✓ ${u.step.type.title} 완료!  다음은 ${next.type.title} ${next.reps}회")
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewFrame = AspectRatioFrameLayout(this)
        textureView = TextureView(this)
        overlay = SkeletonOverlayView(this)
        previewFrame.addView(textureView, FrameLayout.LayoutParams(MATCH, MATCH))
        previewFrame.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(previewFrame, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        // 위쪽: 단계, 횟수, 진행 막대, 안내
        val top = vbox {
            setPadding(dp(20), dp(16), dp(20), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000),
            )
        }
        stepText = label("", 15f, Palette.TEXT_DIM, Fonts.MEDIUM)
        top.addView(stepText, lp())
        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        countText = label("0", 76f, Palette.TEXT, Fonts.BOLD)
        targetText = label("", 28f, Palette.TEXT_DIM, Fonts.LIGHT)
        countRow.addView(countText, lp(WRAP, WRAP))
        countRow.addView(targetText, lp(WRAP, WRAP).margins(l = dp(8), b = dp(10)))
        top.addView(countRow, lp().margins(t = dp(2)))
        progressBar = ProgressBarView(this)
        top.addView(progressBar, lp(MATCH, dp(8)).margins(t = dp(6), b = dp(12)))
        hintText = label("", 18f, Palette.TEXT, Fonts.MEDIUM).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0x66000000, dpf(14))
            setLineSpacing(0f, 1.15f)
        }
        top.addView(hintText, lp(WRAP, WRAP))
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // 아래쪽: 자리 잡기 안내, 대비 버튼, 나가기
        val bottom = vbox {
            setPadding(dp(20), dp(36), dp(20), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000),
            )
        }
        guideText = label("", 13.5f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.2f) }
        bottom.addView(guideText, lp().margins(b = dp(12)))
        fallbackButton = buildFallbackButton()
        bottom.addView(fallbackButton, lp().margins(b = dp(10)))
        val exit = pillButton(if (practice) "연습 그만하기" else "알람 화면으로", filled = false, heightDp = 48) { finish() }
        bottom.addView(exit, lp())
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // 완료 화면
        doneOverlay = vbox {
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            setBackgroundColor(0xF0120E1C.toInt())
            visibility = View.GONE
            isClickable = true
        }
        doneTitle = label("미션 완료!", 38f, Palette.ACCENT, Fonts.BOLD).apply { gravity = Gravity.CENTER }
        doneSub = label("", 18f, Palette.TEXT).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        doneClose = pillButton("닫기", filled = true) { finish() }
        doneOverlay.addView(doneTitle, lp(WRAP, WRAP))
        doneOverlay.addView(doneSub, lp(WRAP, WRAP).margins(t = dp(12), b = dp(32)))
        doneOverlay.addView(doneClose, lp())
        root.addView(doneOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        root.applySystemBarPadding()
        return root
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
        stepText.text = if (total > 1) "${u.stepIndex + 1} / ${total}단계 · ${u.step.type.title}" else u.step.type.title
        countText.text = u.stepCount.toString()
        targetText.text = "/ ${u.step.reps}회"
        progressBar.progress = if (u.totalReps == 0) 0f else u.doneReps.toFloat() / u.totalReps
        val now = SystemClock.elapsedRealtime()
        hintText.text = if (now < bannerUntil) bannerText else u.hint
        hintText.setTextColor(
            when {
                now < bannerUntil -> Palette.GOOD
                u.tracking -> Palette.TEXT
                else -> Palette.WARN
            }
        )
        if (shownStepIndex != u.stepIndex) {
            shownStepIndex = u.stepIndex
            guideText.text = "자리 잡기: ${u.step.type.setupGuide}\n1회 기준: ${u.step.type.motionGuide}"
        }
    }

    private fun showBanner(text: String) {
        bannerText = text
        bannerUntil = SystemClock.elapsedRealtime() + BANNER_MS
    }

    private fun countFeedback() {
        countText.animate().cancel()
        countText.scaleX = 1.25f
        countText.scaleY = 1.25f
        countText.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        val v = defaultVibrator()
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showCameraUnavailable(message: String) {
        hintText.text = message
        hintText.setTextColor(Palette.WARN)
        // 알람 모드에서 카메라를 쓸 수 없으면 갇히지 않도록 길게 눌러 끄는 대비책 제공
        if (!practice && !finished) fallbackButton.visibility = View.VISIBLE
    }

    private fun completeMission(viaFallback: Boolean) {
        if (finished) return
        finished = true
        camera?.stop()
        handler.removeCallbacks(holdToDismiss)
        doneOverlay.visibility = View.VISIBLE
        doneOverlay.alpha = 0f
        doneOverlay.animate().alpha(1f).setDuration(250).start()

        if (practice) {
            doneTitle.text = "연습 완료!"
            doneSub.text = "인식이 잘 되었다면 이 자리 그대로\n알람 때 사용하면 돼요"
            doneClose.visibility = View.VISIBLE
        } else {
            doneTitle.text = if (viaFallback) "알람 해제" else "미션 완료!"
            doneSub.text = if (viaFallback) "카메라 없이 알람을 껐어요" else "좋은 아침이에요.\n오늘도 힘차게 시작해요!"
            doneClose.visibility = View.GONE
            startService(AlarmService.dismissAfterMissionIntent(this, alarmId))
            handler.postDelayed({ finish() }, DONE_SCREEN_MS)
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_PRACTICE_ROUTINE = "practice_routine"
        private const val REQ_CAMERA = 31
        private const val BANNER_MS = 2_500L
        private const val DONE_SCREEN_MS = 2_000L
        private const val HOLD_TO_DISMISS_MS = 3_000L
    }
}

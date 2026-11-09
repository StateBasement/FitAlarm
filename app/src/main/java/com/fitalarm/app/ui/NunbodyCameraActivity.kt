package com.fitalarm.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fitalarm.app.R
import com.fitalarm.app.camera.StillCamera
import com.fitalarm.app.core.PhotoQuality
import com.fitalarm.app.core.PhotoSource
import com.fitalarm.app.core.QualityOption
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.data.SettingsRepository
import com.fitalarm.app.nunbody.NunbodyFileProvider
import com.fitalarm.app.nunbody.NunbodyImages
import com.fitalarm.app.voice.Speaker
import java.io.File
import java.time.LocalDateTime

/**
 * 눈바디 촬영.
 *  - 알람핏 카메라: 폰이 지원하는 화질 중에서 골라 찍기, 전면·후면, 지난 사진 겹쳐 보기, "셋, 둘, 하나" 음성 타이머
 *  - 폰 기본 카메라: 카메라 앱의 화질·보정 그대로. 찍은 사진은 갤러리를 거치지 않고 알람핏으로 바로 들어옴
 * 찍힌 사진은 화면에 보이던 모습 그대로 저장 (따로 뒤집지 않음).
 */
class NunbodyCameraActivity : NunbodyBaseActivity(), StillCamera.Listener {
    override val darkUi = true

    private var camera: StillCamera? = null
    private lateinit var texture: TextureView
    private lateinit var previewBox: AspectRatioFrameLayout
    private lateinit var ghost: ImageView
    private lateinit var countdownText: TextView
    private lateinit var hint: TextView
    private lateinit var ghostChip: TextView
    private lateinit var qualityChip: TextView
    private lateinit var lensLabel: TextView
    private lateinit var lensButton: View
    private lateinit var shutterIcon: ImageView
    private lateinit var thumb: ImageView
    private val timerViews = ArrayList<TextView>()

    private var options: List<QualityOption> = emptyList()
    private var chosen: QualityOption? = null
    private var inApp = false
    private var waitingSystem = false

    private val handler = Handler(Looper.getMainLooper())
    private var countdown: Runnable? = null
    private var speaker: Speaker? = null
    private val shutterSound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    override fun onUnlocked() {
        if (!hasCameraPermission()) {
            goingAway()   // 권한 창을 다녀오는 동안 다시 잠그지 않음
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        begin()
    }

    private fun begin() {
        if (nb.useSystemCamera && !intent.getBooleanExtra(EXTRA_IN_APP, false)) {
            setBody(View(this))
            launchSystemCamera()
        } else openInApp()
    }

    private fun openInApp() {
        inApp = true
        setBody(build())
        speaker = Speaker(this, AudioAttributes.USAGE_MEDIA, SettingsRepository.get(this).voiceStyle)
        camera = StillCamera(this, texture, this).also {
            it.front = nb.front && it.hasLens(true)
            it.tier = nb.quality
            it.start()
        }
        renderControls()
        loadLastPhoto()
    }

    override fun onRefresh() {
        if (inApp && hasCameraPermission()) camera?.start()
        if (inApp) loadLastPhoto()
    }

    override fun onPause() {
        cancelCountdown()
        camera?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        camera?.release()
        speaker?.shutdown()
        try {
            shutterSound.release()
        } catch (e: Exception) {
            // 무시
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 태블릿·폴드에서 화면이 돌면 미리보기 방향을 다시 맞춤
        if (inApp) camera?.restart()
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
        else {
            toast("카메라 권한이 있어야 눈바디를 찍을 수 있어요")
            finish()
        }
    }

    // ------------------------------------------------------------------ 화면

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(CameraColors.BG) }

        previewBox = AspectRatioFrameLayout(this)
        texture = TextureView(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        ghost = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.33f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewBox.addView(ghost, FrameLayout.LayoutParams(MATCH, MATCH))
        countdownText = label("", 96f, 0xFFFFFFFF.toInt(), Fonts.BOLD).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dpf(12), 0f, 0f, 0x99000000.toInt())
            visibility = View.GONE
        }
        previewBox.addView(countdownText, FrameLayout.LayoutParams(MATCH, MATCH))
        hint = label("흐릿하게 겹친 지난 사진에 몸을 맞춰 보세요", 13f, 0xFFFFFFFF.toInt()).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(0xB30E1A16.toInt(), dpf(20))
        }
        previewBox.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(68) })
        root.addView(previewBox, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // 위쪽: 닫기, 겹쳐 보기, 화질
        val top = hbox {
            setPadding(dp(12), dp(12), dp(12), dp(8))
            addView(FrameLayout(this@NunbodyCameraActivity).apply {
                background = ripple(oval(0x8C0E1A16.toInt()), oval(0x8C0E1A16.toInt()))
                isClickable = true
                contentDescription = "닫기"
                setOnClickListener { finish() }
                addView(icon(R.drawable.ic_close, 0xFFFFFFFF.toInt(), 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, lp(dp(44), dp(44)))
            addView(spacer())
            ghostChip = darkChip("겹쳐 보기", R.drawable.ic_layers) {
                NunbodyRepository.update(this@NunbodyCameraActivity) { it.copy(ghost = !it.ghost) }
                renderControls()
            }
            addView(ghostChip, lp(WRAP, dp(40)).margins(r = dp(8)))
            qualityChip = darkChip("화질", R.drawable.ic_chevron_down, iconRight = true) { chooseQuality() }
            addView(qualityChip, lp(WRAP, dp(40)))
        }
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // 아래쪽 판: 타이머, 셔터, 전면·후면, 기본 카메라
        val panel = vbox {
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = rounded(CameraColors.PANEL, dpf(28)).apply {
                cornerRadii = floatArrayOf(dpf(28), dpf(28), dpf(28), dpf(28), 0f, 0f, 0f, 0f)
            }
            isClickable = true
            val timers = hbox {
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = rounded(CameraColors.CHIP, dpf(99))
            }
            NunbodyRepository.TIMERS.forEach { sec ->
                val t = label(if (sec == 0) "끄기" else "${sec}초", 14f, CameraColors.DIM).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        NunbodyRepository.update(this@NunbodyCameraActivity) { it.copy(timerSec = sec) }
                        renderControls()
                    }
                }
                timerViews.add(t)
                timers.addView(t, lp(0, dp(40), 1f))
            }
            addView(timers, lp())

            val row = hbox {
                thumb = ImageView(this@NunbodyCameraActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipRounded(CameraColors.CHIP, dpf(14))
                    contentDescription = "지난 사진 보기"
                    isClickable = true
                    setOnClickListener { finish() }
                }
                addView(thumb, lp(dp(52), dp(52)))
                addView(spacer())
                val shutter = FrameLayout(this@NunbodyCameraActivity).apply {
                    background = oval(0x00000000, 0xFFFFFFFF.toInt(), dp(5))
                    isClickable = true
                    contentDescription = "촬영"
                    setOnClickListener { onShutter() }
                    val inner = FrameLayout(this@NunbodyCameraActivity).apply { background = oval(0xFFFFFFFF.toInt()) }
                    shutterIcon = icon(R.drawable.ic_timer, 0xFF10231A.toInt(), 26)
                    inner.addView(shutterIcon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                    addView(inner, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                }
                addView(shutter, lp(dp(84), dp(84)))
                addView(spacer())
                addView(vbox {
                    gravity = Gravity.CENTER_HORIZONTAL
                    lensButton = FrameLayout(this@NunbodyCameraActivity).apply {
                        background = ripple(oval(CameraColors.CHIP), oval(CameraColors.CHIP))
                        isClickable = true
                        setOnClickListener { switchLens() }
                        addView(icon(R.drawable.ic_camera_switch, 0xFFFFFFFF.toInt(), 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                    }
                    addView(lensButton, lp(dp(52), dp(52)))
                    lensLabel = label("후면", 12f, CameraColors.DIM).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    addView(lensLabel, lp(WRAP, WRAP).margins(t = dp(4)))
                }, lp(dp(52), WRAP))
            }
            addView(row, lp().margins(t = dp(14)))

            addView(hbox {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(rounded(0x00000000, dpf(23), CameraColors.LINE, dp(1.5f)), rounded(0xFFFFFFFF.toInt(), dpf(23)))
                isClickable = true
                setOnClickListener { launchSystemCamera() }
                addView(icon(R.drawable.ic_phone, CameraColors.TEXT, 18), lp(dp(18), dp(18)).margins(r = dp(8)))
                addView(label("폰 기본 카메라로 찍기", 14.5f, CameraColors.TEXT, Fonts.MEDIUM))
                addView(icon(R.drawable.ic_external, CameraColors.TEXT, 14), lp(dp(14), dp(14)).margins(l = dp(6)))
            }, lp(MATCH, dp(46)).margins(t = dp(14)))
            addView(label("거울 보고 찍어도 돼요. 화면에 보이는 모습 그대로 저장돼요.", 12.5f, CameraColors.DIM).apply {
                gravity = Gravity.CENTER
            }, lp().margins(t = dp(10)))
        }
        root.addView(centeredColumn(panel, 520), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        root.applySystemBarPadding()
        return root
    }

    private fun darkChip(text: String, iconRes: Int, iconRight: Boolean = false, onClick: () -> Unit): TextView =
        label(text, 13f, 0xFFFFFFFF.toInt(), Fonts.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(12), 0)
            background = ripple(rounded(0x990E1A16.toInt(), dpf(20)), rounded(0x990E1A16.toInt(), dpf(20)))
            isClickable = true
            compoundDrawablePadding = dp(6)
            val d = getDrawable(iconRes)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }
            if (iconRight) setCompoundDrawablesRelative(null, null, d, null) else setCompoundDrawablesRelative(d, null, null, null)
            setOnClickListener { onClick() }
        }

    private fun renderControls() {
        val s = nb
        ghost.visibility = if (s.ghost) View.VISIBLE else View.GONE
        hint.visibility = if (s.ghost && ghost.drawable != null) View.VISIBLE else View.GONE
        ghostChip.background = ripple(
            rounded(if (s.ghost) 0xF2DCF5E5.toInt() else 0x990E1A16.toInt(), dpf(20)),
            rounded(0xFFFFFFFF.toInt(), dpf(20)),
        )
        ghostChip.setTextColor(if (s.ghost) 0xFF14532D.toInt() else 0xFFFFFFFF.toInt())
        ghostChip.compoundDrawablesRelative[0]?.setTint(if (s.ghost) 0xFF14532D.toInt() else 0xFFFFFFFF.toInt())
        ghostChip.contentDescription = if (s.ghost) "지난 사진 겹쳐 보기 켜짐" else "지난 사진 겹쳐 보기 꺼짐"
        qualityChip.compoundDrawablesRelative[2]?.setTint(0xFFFFFFFF.toInt())
        qualityChip.text = chosen?.let { "화질 " + PhotoQuality.megapixelLabel(it.size) } ?: "화질"
        NunbodyRepository.TIMERS.forEachIndexed { i, sec ->
            val on = sec == s.timerSec
            timerViews[i].background = if (on) rounded(CameraColors.ACCENT, dpf(99)) else null
            timerViews[i].setTextColor(if (on) CameraColors.ON_ACCENT else CameraColors.DIM)
            timerViews[i].typeface = if (on) Fonts.BOLD else null
            timerViews[i].contentDescription = (if (sec == 0) "타이머 끄기" else "타이머 ${sec}초") + if (on) ", 선택됨" else ""
        }
        val front = camera?.front == true
        lensLabel.text = if (front) "전면" else "후면"
        lensButton.contentDescription = "전면·후면 카메라 바꾸기, 지금 ${lensLabel.text}"
        lensButton.visibility = if (camera?.hasLens(!front) == true) View.VISIBLE else View.INVISIBLE
        shutterIcon.visibility = if (s.timerSec > 0) View.VISIBLE else View.GONE
    }

    private fun loadLastPhoto() {
        val last = NunbodyRepository.photos(this).firstOrNull()
        if (last == null) {
            ghost.setImageDrawable(null)
            thumb.setImageDrawable(null)
            hint.visibility = View.GONE
            return
        }
        val f = NunbodyRepository.file(this, last)
        NunbodyImages.load(ghost, f, 1080, blur = false)
        NunbodyImages.load(thumb, f, 160, blur = nb.blur)
        hint.visibility = if (nb.ghost) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ 카메라

    override fun onCameraReady(uprightWidth: Int, uprightHeight: Int, front: Boolean, options: List<QualityOption>, chosen: QualityOption) {
        this.options = options
        this.chosen = chosen
        previewBox.setAspect(uprightWidth, uprightHeight)
        // 전면 카메라 미리보기는 거울처럼 보임. 지난 사진도 저장된 모습 그대로라 같은 방향으로 겹쳐짐
        renderControls()
    }

    override fun onCameraError(message: String) {
        toast(message)
    }

    private fun switchLens() {
        val c = camera ?: return
        cancelCountdown()
        c.front = !c.front
        NunbodyRepository.update(this) { it.copy(front = c.front) }
        c.restart()
        renderControls()
    }

    private fun chooseQuality() {
        if (options.isEmpty()) return
        val body = vbox {
            addView(label("화질", 20f, 0xFFFFFFFF.toInt(), Fonts.BOLD))
            addView(label("이 폰의 ${if (camera?.front == true) "전면" else "후면"} 카메라가 지원하는 화질만 보여요", 13f, CameraColors.DIM),
                lp().margins(t = dp(4), b = dp(10)))
        }
        val sheet = bottomSheet(body, dark = true)
        options.forEach { o ->
            val on = o == chosen
            body.addView(hbox {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ripple(
                    rounded(if (on) CameraColors.CHIP else 0x00000000, dpf(16), if (on) CameraColors.ACCENT else 0x00000000, dp(1.5f)),
                    rounded(0xFFFFFFFF.toInt(), dpf(16)),
                )
                isClickable = true
                contentDescription = "${o.tier.title} ${PhotoQuality.megapixelLabel(o.size)} 화소" + if (on) ", 선택됨" else ""
                addView(View(this@NunbodyCameraActivity).apply {
                    background = if (on) oval(CameraColors.ACCENT) else oval(0x00000000, 0xFF5C7268.toInt(), dp(2))
                }, lp(dp(18), dp(18)).margins(r = dp(14)))
                addView(vbox {
                    addView(label("${o.tier.title} · ${PhotoQuality.megapixelLabel(o.size)} 화소", 15.5f, 0xFFFFFFFF.toInt(), if (on) Fonts.BOLD else Fonts.MEDIUM))
                    addView(label("${o.size.width}×${o.size.height} · 장당 약 ${com.fitalarm.app.core.formatBytes(PhotoQuality.estimatedBytes(o.size))}", 12.5f, CameraColors.DIM),
                        lp().margins(t = dp(3)))
                }, lp(0, WRAP, 1f))
                setOnClickListener {
                    NunbodyRepository.update(this@NunbodyCameraActivity) { it.copy(quality = o.tier) }
                    camera?.tier = o.tier
                    camera?.restart()
                    sheet.dismiss()
                }
            }, lp().margins(t = dp(4)))
        }
        body.addView(noteBox(R.drawable.ic_check_circle, "매번 같은 화질로 찍으면 지난 사진과 비교하기 좋아요.", CameraColors.CHIP, CameraColors.DIM),
            lp().margins(t = dp(10)))
        sheet.show()
    }

    // ------------------------------------------------------------------ 찍기

    private fun onShutter() {
        val c = camera ?: return
        if (countdown != null) {
            cancelCountdown()
            return
        }
        if (c.isCapturing) return
        val sec = nb.timerSec
        if (sec <= 0) {
            takeNow()
            return
        }
        var left = sec
        val tick = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    countdownText.visibility = View.GONE
                    countdown = null
                    takeNow()
                    return
                }
                countdownText.text = left.toString()
                countdownText.visibility = View.VISIBLE
                when (left) {
                    3 -> speaker?.speak("셋")
                    2 -> speaker?.speak("둘")
                    1 -> speaker?.speak("하나")
                }
                left--
                handler.postDelayed(this, 1000L)
            }
        }
        countdown = tick
        handler.post(tick)
    }

    private fun cancelCountdown() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
        if (::countdownText.isInitialized) countdownText.visibility = View.GONE
    }

    private fun takeNow() {
        val c = camera ?: return
        val out = NunbodyRepository.captureFile(this)
        out.delete()
        if (c.capture(out)) {
            try {
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                // 소리 없이 찍음
            }
        } else toast("카메라가 아직 준비되지 않았어요")
    }

    override fun onCaptured(file: File, front: Boolean) {
        val takenAt = LocalDateTime.now().withNano(0)
        // 전면 카메라: 미리보기에서 보던 모습 그대로 (방향값만 바꿔 화질 손실 없음)
        NunbodyImages.finalizeCapture(file, takenAt, mirror = front)
        openSave(if (front) PhotoSource.APP_FRONT else PhotoSource.APP_BACK, takenAt)
    }

    override fun onCaptureFailed(message: String) = toast(message)

    private fun openSave(source: PhotoSource, takenAt: LocalDateTime) {
        startActivityForResult(
            Intent(this, NunbodySaveActivity::class.java)
                .putExtra(NunbodySaveActivity.EXTRA_SOURCE, source.key)
                .putExtra(NunbodySaveActivity.EXTRA_TAKEN_AT, takenAt.toString()),
            REQ_SAVE,
        )
    }

    // ------------------------------------------------------------------ 폰 기본 카메라

    private fun launchSystemCamera() {
        cancelCountdown()
        val f = NunbodyRepository.captureFile(this)
        f.delete()
        val uri = NunbodyFileProvider.captureUri(this)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        if (nb.front) {
            // 카메라 앱마다 다른 '전면으로 열기' 신호 (모르는 앱은 무시하고 마지막에 쓴 렌즈로 열림)
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        goingAway()
        waitingSystem = true
        try {
            startActivityForResult(i, REQ_SYSTEM)
        } catch (e: ActivityNotFoundException) {
            waitingSystem = false
            toast("기본 카메라 앱을 열 수 없어요. 알람핏 카메라로 찍어 주세요")
            if (!inApp) openInApp()
        } catch (e: SecurityException) {
            waitingSystem = false
            toast("카메라 권한이 필요해요")
            if (!inApp) finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_SAVE -> if (resultCode == RESULT_OK) {
                setResult(RESULT_OK)
                finish()
            } else if (!inApp && nb.useSystemCamera) {
                // 기본 카메라로 찍은 사진을 저장하지 않고 '다시 찍기'
                launchSystemCamera()
            }
            REQ_SYSTEM -> {
                waitingSystem = false
                val f = NunbodyRepository.captureFile(this)
                if (resultCode == RESULT_OK && f.exists() && f.length() > 0) {
                    val takenAt = (NunbodyImages.takenAt(f) ?: LocalDateTime.now()).withNano(0)
                    NunbodyImages.finalizeCapture(f, takenAt, mirror = false)   // 위치 정보 삭제 (카메라 앱 설정에 따라 들어 있을 수 있음)
                    openSave(PhotoSource.SYSTEM, takenAt)
                } else if (!inApp) finish()
            }
        }
    }

    companion object {
        /** 설정과 상관없이 알람핏 카메라로 */
        const val EXTRA_IN_APP = "in_app"
        private const val REQ_CAMERA = 61
        private const val REQ_SAVE = 62
        private const val REQ_SYSTEM = 63
    }
}

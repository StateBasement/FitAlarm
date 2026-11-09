package com.fitalarm.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fitalarm.app.R
import com.fitalarm.app.camera.StillCamera
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.PhotoQuality
import com.fitalarm.app.core.ReminderPeriod
import com.fitalarm.app.core.formatBytes
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.nunbody.NunbodyAuth
import com.fitalarm.app.nunbody.NunbodyImages
import com.fitalarm.app.nunbody.NunbodyReminder
import java.time.LocalDate

/** 눈바디 설정: 보호(잠금·캡처 차단·사진 가리기), 촬영, 촬영 알림, 저장 공간 + 처음 한 번 안내 */
class NunbodySettingsActivity : NunbodyBaseActivity() {
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var protectCard: View? = null
    private var shootCard: View? = null
    private var reminderCard: View? = null
    private var storageCard: View? = null
    private var guideStarted = false

    override fun onUnlocked() {
        setBody(build())
        render()
        if (intent.getBooleanExtra(EXTRA_GUIDE, false) || !GuideOverlay.seen(this, GuideOverlay.NUNBODY_SETTINGS)) startGuide()
    }

    override fun onRefresh() = render()

    private fun build(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        root.addView(centeredColumn(nbHeader("눈바디 설정").first), lp())
        body = vbox { setPadding(dp(20), dp(4), dp(20), dp(28)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(centeredColumn(body))
        }
        root.addView(scroll, lp(MATCH, 0, 1f))
        root.applySystemBarPadding()
        return root
    }

    private fun render() {
        body.removeAllViews()
        val s = nb

        // ------------------------------------------------ 보호
        protectCard = card("보호") {
            val (lockRow, lockSwitch, _) = switchRowWithRefs("잠금", "눈바디를 열 때 지문이나 휴대폰 비밀번호를 물어봐요. 끌 때도 본인 확인이 필요해요.", s.lock) { on ->
                if (on) turnLockOn() else {
                    // 경고와 본인 확인을 거친 뒤에만 끔 (그 전에는 스위치를 되돌림)
                    render()
                    askTurnLockOff()
                }
            }
            lockSwitch.contentDescription = "잠금"
            addView(lockRow)
            addView(divider())
            addView(switchRow("캡처 차단", "화면 캡처·녹화와 최근 앱 미리보기에서 사진을 숨겨요", s.captureBlock) { on ->
                NunbodyRepository.update(this@NunbodySettingsActivity) { it.copy(captureBlock = on) }
                applySecure()
            })
            addView(divider())
            addView(switchRow("사진 가리기", "목록에서는 흐리게 보이고, 누르면 선명해져요", s.blur) { on ->
                NunbodyRepository.update(this@NunbodySettingsActivity) { it.copy(blur = on) }
                NunbodyImages.clearCache()
            })
        }
        body.addView(protectCard, lp())

        // ------------------------------------------------ 촬영
        shootCard = card("촬영") {
            val (camRow, camValue) = valueRow("찍을 때 쓸 카메라") { chooseCameraApp() }
            camValue.text = if (s.useSystemCamera) "폰 기본 카메라" else "알람핏 카메라"
            addView(camRow)
            addView(divider())
            val options = StillCamera.options(this@NunbodySettingsActivity, front = false)
            val (qRow, qValue) = valueRow("화질") { chooseQuality() }
            qValue.text = PhotoQuality.pick(options, s.quality)?.let { "${it.tier.title} (${PhotoQuality.megapixelLabel(it.size)})" } ?: s.quality.title
            addView(qRow)
            addView(label(
                if (s.useSystemCamera) "'사진 찍기'를 누르면 폰 카메라 앱이 바로 열려요. 화질·전면/후면은 카메라 앱 설정을 따라요."
                else "알람핏 카메라는 지난 사진 겹쳐 보기와 \"셋, 둘, 하나\" 음성 타이머가 있어요. 화질은 이 폰이 지원하는 것 중에서 골라요.",
                12.5f, Palette.TEXT_DIM,
            ).apply { setLineSpacing(0f, 1.3f) }, lp().margins(t = dp(2), b = dp(8)))
        }
        body.addView(shootCard, lp().margins(t = dp(12)))

        // ------------------------------------------------ 촬영 알림
        reminderCard = card("촬영 알림") {
            addView(switchRow("알림 받기", "정한 날 정한 시각에 알려 드려요", s.reminderOn) { on -> setReminder(on) })
            if (s.reminderOn) {
                addView(divider())
                val (pRow, pValue) = valueRow("주기") { choosePeriod() }
                pValue.text = s.reminderPeriod.title
                addView(pRow)
                addView(divider())
                val (tRow, tValue) = valueRow("시각") { chooseTime() }
                tValue.text = AlarmMath.formatTime(s.reminderHour, s.reminderMinute, android.text.format.DateFormat.is24HourFormat(this@NunbodySettingsActivity))
                addView(tRow)
                NunbodyReminder.nextAt(this@NunbodySettingsActivity)?.let { n ->
                    val time = AlarmMath.formatTime(n.hour, n.minute, android.text.format.DateFormat.is24HourFormat(this@NunbodySettingsActivity))
                    addView(label("다음 알림 · ${n.monthValue}월 ${n.dayOfMonth}일 $time", 12.5f, Palette.TEXT_DIM), lp().margins(t = dp(2), b = dp(8)))
                }
            } else {
                addView(label("매주 월요일 · 2주마다 · 매월 1일 중에서 고를 수 있어요", 12.5f, Palette.TEXT_DIM), lp().margins(t = dp(2), b = dp(8)))
            }
        }
        body.addView(reminderCard, lp().margins(t = dp(12)))

        // ------------------------------------------------ 저장 공간
        val photos = NunbodyRepository.photos(this)
        storageCard = card("저장 공간") {
            addView(hbox {
                gravity = Gravity.BOTTOM
                addView(label("사진 ${photos.size}장", 17f, Palette.TEXT, Fonts.BOLD))
                addView(label(formatBytes(NunbodyRepository.totalBytes(this@NunbodySettingsActivity)), 14f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(l = dp(8)))
            }, lp().margins(t = dp(6)))
            addView(noteBox(R.drawable.ic_warning, "앱을 지우면 사진도 함께 지워져요. 남기고 싶은 사진은 미리 내보내 두세요.", Palette.WARM_SOFT, Palette.ON_WARM),
                lp().margins(t = dp(10), b = dp(4)))
            if (photos.isNotEmpty()) {
                val (delRow, delValue) = valueRow("사진 모두 지우기") { confirmDeleteAll() }
                delValue.text = "지우기"
                delValue.setTextColor(Palette.DANGER)
                addView(delRow)
            }
        }
        body.addView(storageCard, lp().margins(t = dp(12)))
    }

    // ------------------------------------------------------------------ 잠금

    private fun turnLockOn() {
        NunbodyRepository.update(this) { it.copy(lock = true) }
        if (!NunbodyAuth.deviceSecure(this)) toast("휴대폰 화면 잠금을 먼저 켜야 눈바디 잠금이 작동해요")
        render()
    }

    /** 끄기 전에 왜 잠금이 있는지 알려 주고, 본인 확인을 거쳐야 꺼짐 */
    private fun askTurnLockOff() {
        val content = vbox {
            setPadding(dp(24), dp(22), dp(24), dp(4))
            addView(FrameLayout(this@NunbodySettingsActivity).apply {
                background = oval(Palette.WARM_SOFT)
                addView(icon(R.drawable.ic_lock_open, Palette.WARN, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }, lp(dp(52), dp(52)))
            addView(label("눈바디 잠금을 끌까요?", 20f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(14)))
            addView(label("잠금을 끄면 휴대폰 잠금이 풀려 있을 때 누구나 알람핏에서 몸 사진과 체중 기록을 볼 수 있어요.", 15f, Palette.TEXT).apply {
                setLineSpacing(0f, 1.3f)
            }, lp().margins(t = dp(10)))
            addView(label("• 사진을 보여주려고 폰을 건넸을 때\n• 가족이나 아이가 폰을 쓸 때\n• 잠금이 풀린 채로 폰을 두고 자리를 비웠을 때", 14f, Palette.TEXT_DIM).apply {
                setLineSpacing(0f, 1.45f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Palette.BG, dpf(16))
            }, lp().margins(t = dp(12)))
            addView(label("끄려면 본인 확인이 필요해요. 설정에서 언제든 다시 켤 수 있어요.", 13f, Palette.TEXT_DIM), lp().margins(t = dp(12)))
        }
        val d = AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("잠금 유지", null)
            .setNegativeButton("본인 확인하고 끄기") { _, _ -> confirmAndTurnOff() }
            .show()
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Palette.DANGER)
    }

    private fun confirmAndTurnOff() {
        NunbodyAuth.authenticate(this, "본인 확인", "눈바디 잠금을 끄려면 인증하세요") { ok ->
            if (isFinishing || isDestroyed) return@authenticate
            if (ok) {
                NunbodyRepository.update(this) { it.copy(lock = false) }
                toast("눈바디 잠금을 껐어요. 설정에서 언제든 다시 켤 수 있어요")
            }
            render()
        }
    }

    // ------------------------------------------------------------------ 촬영

    private fun chooseCameraApp() {
        val items = arrayOf("알람핏 카메라\n지난 사진 겹쳐 보기 · 음성 타이머 · 화질 선택", "폰 기본 카메라\n카메라 앱의 화질·보정 그대로")
        AlertDialog.Builder(this)
            .setTitle("찍을 때 쓸 카메라")
            .setSingleChoiceItems(items, if (nb.useSystemCamera) 1 else 0) { dlg, which ->
                NunbodyRepository.update(this) { it.copy(useSystemCamera = which == 1) }
                dlg.dismiss()
                render()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun chooseQuality() {
        val options = StillCamera.options(this, front = false)
        if (options.isEmpty()) {
            toast("이 폰의 카메라 정보를 읽을 수 없어요")
            return
        }
        val current = PhotoQuality.pick(options, nb.quality)
        val labels = options.map {
            "${it.tier.title} · ${PhotoQuality.megapixelLabel(it.size)} 화소\n${it.size.width}×${it.size.height} · 장당 약 ${formatBytes(PhotoQuality.estimatedBytes(it.size))}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("화질 (후면 카메라 기준)")
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dlg, which ->
                NunbodyRepository.update(this) { it.copy(quality = options[which].tier) }
                dlg.dismiss()
                render()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------------ 알림

    private fun setReminder(on: Boolean) {
        NunbodyRepository.update(this) { it.copy(reminderOn = on, reminderAnchor = if (on) LocalDate.now() else it.reminderAnchor) }
        NunbodyReminder.schedule(this)
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            goingAway()   // 권한 창을 다녀오는 동안 다시 잠그지 않음
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        render()
    }

    private fun choosePeriod() {
        val periods = ReminderPeriod.entries
        AlertDialog.Builder(this)
            .setTitle("주기")
            .setSingleChoiceItems(periods.map { it.title }.toTypedArray(), periods.indexOf(nb.reminderPeriod)) { dlg, which ->
                NunbodyRepository.update(this) { it.copy(reminderPeriod = periods[which], reminderAnchor = LocalDate.now()) }
                NunbodyReminder.schedule(this)
                dlg.dismiss()
                render()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun chooseTime() {
        val s = nb
        TimePickerDialog(this, { _, h, m ->
            NunbodyRepository.update(this) { it.copy(reminderHour = h, reminderMinute = m) }
            NunbodyReminder.schedule(this)
            render()
        }, s.reminderHour, s.reminderMinute, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("알림 권한이 없으면 촬영 알림이 오지 않아요")
        }
    }

    // ------------------------------------------------------------------ 저장 공간

    private fun confirmDeleteAll() {
        val n = NunbodyRepository.photos(this).size
        AlertDialog.Builder(this)
            .setTitle("사진 모두 지우기")
            .setMessage("눈바디 사진 ${n}장과 체중·키·메모 기록을 모두 지울까요? 되돌릴 수 없어요.\n(이미 갤러리에 내보낸 사진은 지워지지 않아요)")
            .setNegativeButton("취소", null)
            .setPositiveButton("모두 지우기") { _, _ ->
                NunbodyAuth.authenticate(this, "본인 확인", "눈바디 사진을 모두 지우려면 인증하세요") { ok ->
                    if (!ok || isFinishing) return@authenticate
                    NunbodyRepository.deleteAll(this)
                    NunbodyImages.clearCache()
                    toast("모두 지웠어요")
                    render()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ 처음 안내

    private fun startGuide() {
        if (guideStarted) return
        guideStarted = true
        GuideOverlay.markSeen(this, GuideOverlay.NUNBODY_SETTINGS)
        GuideOverlay.show(this, listOf(
            GuideStep("보호", "내 사진을 지키는 기능이에요",
                "잠금 · 눈바디를 열 때 지문이나 휴대폰 비밀번호를 물어봐요\n" +
                    "캡처 차단 · 캡처·녹화, 최근 앱 화면에 사진이 찍히지 않아요\n" +
                    "사진 가리기 · 목록에서는 흐리게, 누르면 선명하게 보여요") { listOfNotNull(protectCard) },
            GuideStep("촬영", "찍는 방법을 골라요",
                "알람핏 카메라는 지난 사진을 흐릿하게 겹쳐 보여 주고 \"셋, 둘, 하나\" 음성 타이머가 있어요. " +
                    "폰 기본 카메라를 고르면 카메라 앱의 화질·보정 그대로 찍어요.") { listOfNotNull(shootCard) },
            GuideStep("촬영 알림", "찍을 날을 알려 드려요",
                "매주·2주마다·매월 중에서 정한 날, 정한 시각에 '눈바디 찍는 날'이라고 알려 드려요. 처음엔 꺼져 있어요.") { listOfNotNull(reminderCard) },
            GuideStep("저장 공간", "사진은 이 폰 안에만",
                "갤러리나 클라우드에 올라가지 않아요. 앱을 지우면 사진도 함께 지워지니, 남기고 싶은 사진은 미리 내보내 두세요.") { listOfNotNull(storageCard) },
        ))
    }

    companion object {
        const val EXTRA_GUIDE = "guide"
        private const val REQ_NOTIF = 81
    }
}

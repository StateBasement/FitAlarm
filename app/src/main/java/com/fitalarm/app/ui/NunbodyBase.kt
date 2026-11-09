package com.fitalarm.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import com.fitalarm.app.R
import com.fitalarm.app.core.NoLockWarning
import com.fitalarm.app.data.NunbodyRepository
import com.fitalarm.app.data.NunbodySettings
import com.fitalarm.app.nunbody.NunbodyAuth
import com.fitalarm.app.nunbody.NunbodyLock
import java.util.Collections
import java.util.WeakHashMap

/**
 * 눈바디 화면 공통:
 *  - 잠금: 화면이 보이기 전에 본인 확인 (풀리기 전에는 내용을 그리지 않음). 화면 잠금이 없는 폰이면 경고 후 보여 줌
 *  - 캡처 차단: 화면 캡처·녹화와 최근 앱 미리보기에 사진이 찍히지 않게 (FLAG_SECURE)
 *  - 눈바디를 끈 상태면 바로 닫힘
 */
abstract class NunbodyBaseActivity : Activity() {
    protected lateinit var frame: FrameLayout
        private set
    private var unlockedOnce = false
    private var authInProgress = false
    private var warnShown = false

    protected val nb: NunbodySettings get() = NunbodyRepository.settings(this)

    /** 카메라 화면처럼 항상 어두운 화면 */
    protected open val darkUi: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        setupEdgeToEdge(lightBars = !darkUi && !Palette.isDark)
        lockPortraitOnPhones()
        applySecure()
        frame = FrameLayout(this).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(if (darkUi) CameraColors.BG else Palette.BG)
        }
        setContentView(frame)
        OPEN.add(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lockPortraitOnPhones()
    }

    protected fun setBody(v: View) {
        frame.removeAllViews()
        frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    override fun onResume() {
        super.onResume()
        applySecure()
        if (!NunbodyRepository.isEnabled(this)) {
            finish()
            return
        }
        if (NunbodyLock.resume()) reveal() else {
            frame.visibility = View.INVISIBLE
            requestUnlock()
        }
    }

    override fun onPause() {
        NunbodyLock.paused()
        super.onPause()
    }

    override fun onDestroy() {
        OPEN.remove(this)
        super.onDestroy()
    }

    private fun reveal() {
        frame.visibility = View.VISIBLE
        if (!unlockedOnce) {
            unlockedOnce = true
            onUnlocked()
        } else onRefresh()
    }

    /** 처음 잠금이 풀렸을 때 (화면 만들기) */
    protected abstract fun onUnlocked()

    /** 다시 보일 때 (내용 새로 고침) */
    protected open fun onRefresh() {}

    fun applySecure() {
        if (nb.captureBlock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** 카메라 앱·공유 창·설정처럼 눈바디에서 직접 연 곳을 다녀오는 동안 다시 묻지 않음 */
    protected fun goingAway() = NunbodyLock.goingAway()

    private fun requestUnlock() {
        if (authInProgress || warnShown) return
        val s = nb
        if (!s.lock) {
            NunbodyLock.markUnlocked()
            reveal()
            return
        }
        if (!NunbodyAuth.deviceSecure(this)) {
            showNoLockWarning()
            return
        }
        authInProgress = true
        NunbodyAuth.authenticate(this, "본인 확인", "눈바디를 열려면 인증하세요") { ok ->
            authInProgress = false
            if (isFinishing || isDestroyed) return@authenticate
            if (ok) {
                NunbodyLock.markUnlocked()
                reveal()
            } else closeAll()
        }
    }

    /** 휴대폰에 화면 잠금이 없으면 눈바디 잠금도 작동하지 않음: 막지는 않고 알려 줌 */
    private fun showNoLockWarning() {
        if (!NoLockWarning.due(System.currentTimeMillis(), nb.noLockWarnUntil)) {
            NunbodyLock.markUnlocked()
            reveal()
            return
        }
        warnShown = true
        val check = CheckBox(this).apply {
            text = "일주일간 보지 않기"
            textSize = 15f
            setTextColor(Palette.TEXT)
        }
        val body = vbox {
            setPadding(dp(24), dp(22), dp(24), dp(4))
            addView(android.widget.FrameLayout(this@NunbodyBaseActivity).apply {
                background = oval(Palette.WARM_SOFT)
                addView(icon(R.drawable.ic_lock_open, Palette.WARN, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }, lp(dp(52), dp(52)))
            addView(label("휴대폰에 화면 잠금이 없어요", 20f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(14)))
            addView(label("그래서 눈바디 잠금도 작동하지 않아요. 다른 사람이 언제든 사진과 기록을 볼 수 있어요.", 15f, Palette.TEXT).apply {
                setLineSpacing(0f, 1.3f)
            }, lp().margins(t = dp(12)))
            addView(label("가능하면 화면 잠금을 설정해 주세요.", 15f, Palette.WARN, Fonts.BOLD), lp().margins(t = dp(8)))
            addView(check, lp().margins(t = dp(10)))
        }
        fun remember() {
            if (check.isChecked) NunbodyRepository.update(this) { it.copy(noLockWarnUntil = NoLockWarning.snoozeUntil(System.currentTimeMillis())) }
        }
        AlertDialog.Builder(this)
            .setView(body)
            .setCancelable(false)
            .setPositiveButton("화면 잠금 설정하기") { _, _ ->
                remember()
                warnShown = false
                openSecuritySettings()
            }
            .setNegativeButton("그냥 보기") { _, _ ->
                remember()
                warnShown = false
                NunbodyLock.markUnlocked()
                reveal()
            }
            .show()
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("휴대폰 설정 > 잠금 화면에서 화면 잠금을 켜 주세요")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == NunbodyAuth.REQ_CONFIRM) NunbodyAuth.onConfirmResult(resultCode == RESULT_OK)
    }

    companion object {
        private val OPEN: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

        /** 본인 확인을 취소하면 열려 있는 눈바디 화면을 모두 닫음 */
        fun closeAll() {
            OPEN.toList().forEach { it.finish() }
        }
    }
}

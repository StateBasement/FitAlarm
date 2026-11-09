package com.fitalarm.app.nunbody

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import com.fitalarm.app.systemService

/**
 * 눈바디 잠금 상태. 한 번 풀면 눈바디 화면끼리 오가는 동안은 다시 묻지 않고,
 * 화면을 끄거나 다른 앱으로 나가 잠시(15초) 지나면 다시 잠김.
 * 카메라 앱·공유 창처럼 사용자가 눈바디에서 직접 연 곳을 다녀올 때는 3분까지 기다림.
 */
object NunbodyLock {
    private const val GRACE_MS = 15_000L
    private const val AWAY_MS = 3 * 60_000L

    private var unlocked = false
    private var validUntil = 0L
    private var away = false

    private fun now() = SystemClock.elapsedRealtime()

    fun markUnlocked() {
        unlocked = true
        validUntil = Long.MAX_VALUE
    }

    /** 눈바디 화면이 다시 보일 때: 아직 풀려 있으면 true */
    fun resume(): Boolean {
        if (unlocked && now() < validUntil) {
            validUntil = Long.MAX_VALUE
            away = false
            return true
        }
        unlocked = false
        return false
    }

    /** 눈바디 화면이 가려질 때 */
    fun paused() {
        if (!unlocked) return
        validUntil = now() + if (away) AWAY_MS else GRACE_MS
        away = false
    }

    /** 눈바디에서 직접 다른 앱(카메라·공유·설정)을 열기 직전 */
    fun goingAway() {
        away = true
    }

    fun lockNow() {
        unlocked = false
        validUntil = 0L
    }
}

/** 휴대폰 잠금(지문·얼굴·PIN·패턴)으로 본인 확인. 지문 정보는 안드로이드가 처리하고 앱은 성공/실패만 받음 */
object NunbodyAuth {
    const val REQ_CONFIRM = 7301
    private var pending: ((Boolean) -> Unit)? = null

    fun deviceSecure(ctx: Context): Boolean = ctx.systemService<KeyguardManager>().isDeviceSecure

    fun authenticate(activity: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!deviceSecure(activity)) {
            onResult(true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) biometric(activity, title, subtitle, onResult)
        else keyguard(activity, title, subtitle, onResult)
    }

    private fun biometric(activity: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return keyguard(activity, title, subtitle, onResult)
        val prompt = try {
            val b = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                b.setDeviceCredentialAllowed(true)
            }
            b.build()
        } catch (e: Exception) {
            return keyguard(activity, title, subtitle, onResult)
        }
        var done = false
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (done) return
                done = true
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (done) return
                done = true
                if (activity.isFinishing || activity.isDestroyed) return
                if (errorCode in CANCELLED) onResult(false)
                else keyguard(activity, title, subtitle, onResult)   // 지문 센서 문제 등: 휴대폰 비밀번호로
            }
        }
        try {
            prompt.authenticate(CancellationSignal(), activity.mainExecutor, callback)
        } catch (e: Exception) {
            if (!done) {
                done = true
                keyguard(activity, title, subtitle, onResult)
            }
        }
    }

    /** Android 9 이하 또는 생체 인증을 쓸 수 없을 때: 휴대폰 잠금 확인 화면 */
    private fun keyguard(activity: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val km = activity.systemService<KeyguardManager>()
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent == null) {
            onResult(!km.isDeviceSecure)
            return
        }
        pending = onResult
        try {
            activity.startActivityForResult(intent, REQ_CONFIRM)
        } catch (e: Exception) {
            pending = null
            onResult(false)
        }
    }

    /** 휴대폰 잠금 확인 화면의 결과 (Activity.onActivityResult 에서 전달) */
    fun onConfirmResult(ok: Boolean) {
        val p = pending
        pending = null
        p?.invoke(ok)
    }

    /** 사용자가 취소한 경우 (다시 열지 않고 닫음) */
    private val CANCELLED = setOf(
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON,
    )
}

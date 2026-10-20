package com.fitalarm.app

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager

/** 시스템 서비스 조회. 최신 SDK 에서 반환형이 nullable 로 표시되므로 한 곳에서 처리 */
inline fun <reified T : Any> Context.systemService(): T =
    requireNotNull(getSystemService(T::class.java)) { "${T::class.java.simpleName} is not available" }

/** 기본 진동 모터 (Android 12 부터 VibratorManager 경유) */
fun Context.defaultVibrator(): Vibrator =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        systemService<VibratorManager>().defaultVibrator
    } else {
        systemService<Vibrator>()
    }

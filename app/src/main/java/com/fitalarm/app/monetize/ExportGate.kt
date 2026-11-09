package com.fitalarm.app.monetize

import android.app.Activity

/**
 * 눈바디 사진 내보내기(갤러리 저장·보내기) 앞의 관문.
 * 지금은 무료라 바로 진행. 나중에 사용자가 많아지면 AD_REQUIRED 를 켜고 보상형 광고 단위를 연결:
 *  - 내보내기 한 번에 광고 한 편 (기간 내보내기로 여러 장을 받아도 한 번만)
 *  - 응원(후원)한 사람은 광고 없이
 *  - 광고를 불러오지 못하면 사용자를 막지 않고 그냥 내보냄
 */
object ExportGate {
    const val AD_REQUIRED = false

    fun run(activity: Activity, proceed: () -> Unit) {
        if (!AD_REQUIRED || Monetization.isSupporter(activity)) {
            proceed()
            return
        }
        // 보상형 광고는 아직 연결하지 않음: 켜더라도 사용자를 막지 않도록 바로 진행
        proceed()
    }
}

package com.fitalarm.app.monetize

import com.fitalarm.app.BuildConfig

/**
 * AdMob 앱 ID 와 광고 단위 ID. 앱 안에 그대로 들어가는 공개 값이라 비밀이 아닙니다.
 *
 * 출시(release) 빌드만 실제 광고 단위를 쓰고, 폰에 직접 설치하는 테스트 빌드(debug·qa)는
 * Google 공식 테스트 광고 단위를 씁니다. 개발자가 자기 앱의 실제 광고를 보거나 누르면
 * 무효 트래픽으로 AdMob 계정이 정지될 수 있기 때문입니다.
 */
object AdMobIds {
    const val APP_ID = "ca-app-pub-9101880128165365~5622811936"
    const val PUBLISHER_ID = "pub-9101880128165365"

    /** Google 공식 테스트 배너 광고 단위 (항상 "Test Ad" 표시가 있는 테스트 광고만 나옴) */
    const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"

    fun banner(placement: AdPlacement, testMode: Boolean = BuildConfig.ADS_TEST_MODE): String =
        if (testMode) TEST_BANNER else when (placement) {
            AdPlacement.ALARM_LIST_BOTTOM -> "ca-app-pub-9101880128165365/8573116741"
            AdPlacement.RECORDS_BOTTOM -> "ca-app-pub-9101880128165365/1923947916"
            AdPlacement.SETTINGS_BOTTOM -> "ca-app-pub-9101880128165365/5755381718"
        }
}

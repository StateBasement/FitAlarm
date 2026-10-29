package com.fitalarm.app.monetize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 광고 ID 가 뒤바뀌거나, 테스트 빌드에 실제 광고가 나가는 실수를 막는 검사 */
class AdMobIdsTest {

    @Test
    fun testModeNeverUsesRealAdUnits() {
        for (p in AdPlacement.values()) {
            assertEquals(AdMobIds.TEST_BANNER, AdMobIds.banner(p, testMode = true))
        }
    }

    @Test
    fun releaseUsesOwnAdUnitPerPlacement() {
        val ids = AdPlacement.values().map { AdMobIds.banner(it, testMode = false) }
        assertEquals("위치마다 다른 광고 단위", ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id, id.startsWith("ca-app-${AdMobIds.PUBLISHER_ID}/"))
            assertTrue(id, !id.contains("3940256099942544"))   // Google 테스트 계정 아님
        }
        assertEquals("ca-app-pub-9101880128165365/8573116741", AdMobIds.banner(AdPlacement.ALARM_LIST_BOTTOM, false))
        assertEquals("ca-app-pub-9101880128165365/1923947916", AdMobIds.banner(AdPlacement.RECORDS_BOTTOM, false))
        assertEquals("ca-app-pub-9101880128165365/5755381718", AdMobIds.banner(AdPlacement.SETTINGS_BOTTOM, false))
    }

    @Test
    fun manifestAppIdMatchesCode() {
        assertTrue(AdMobIds.APP_ID.startsWith("ca-app-${AdMobIds.PUBLISHER_ID}~"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val m = Regex("""com\.google\.android\.gms\.ads\.APPLICATION_ID"\s+android:value="([^"]+)"""").find(manifest)
        assertEquals(AdMobIds.APP_ID, m?.groupValues?.get(1))
    }
}

package com.fitalarm.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlipGestureTest {
    /** 50ms 간격으로 같은 z 값을 durationMs 동안 넣고, 감지된 횟수를 돌려줌 */
    private fun FlipGesture.feed(z: Float, fromMs: Long, durationMs: Long): Int {
        var hits = 0
        var t = fromMs
        while (t <= fromMs + durationMs) {
            if (onSample(z, t)) hits++
            t += 50
        }
        return hits
    }

    private val up = 9.8f
    private val down = -9.8f

    @Test
    fun flipFromFaceUpQuietsOnce() {
        val g = FlipGesture()
        assertEquals(0, g.feed(up, 0, 500))
        assertEquals(1, g.feed(down, 550, 1500))
        assertEquals(0, g.feed(up, 2100, 500) + g.feed(down, 2650, 1500))   // 한 번만
    }

    @Test
    fun alreadyFaceDownWhenRingingDoesNotQuiet() {
        // 엎어 둔 채로 자다가 알람이 울려도 소리가 줄면 안 됨
        val g = FlipGesture()
        assertEquals(0, g.feed(down, 0, 60_000))
        assertFalse(g.fired)
        // 집어 들었다가 다시 엎으면 그때 감지
        assertEquals(0, g.feed(up, 60_050, 400))
        assertEquals(1, g.feed(down, 60_500, 1000))
    }

    @Test
    fun briefBumpIsIgnored() {
        val g = FlipGesture()
        g.feed(up, 0, 500)
        assertEquals(0, g.feed(down, 550, 300))   // 0.3초만 엎어짐
        assertEquals(0, g.feed(up, 900, 300))
        assertFalse(g.fired)
    }

    @Test
    fun uprightOrSidewaysArmsToo() {
        // 세워 둠(z≈0) → 엎음
        val g = FlipGesture()
        g.feed(0.5f, 0, 500)
        assertEquals(1, g.feed(down, 550, 1000))
    }

    @Test
    fun tiltedFaceDownBeyondThresholdDoesNotCount() {
        // 비스듬히 기울인 정도(z≈-5)는 엎은 것으로 보지 않음
        val g = FlipGesture()
        g.feed(up, 0, 500)
        assertEquals(0, g.feed(-5f, 550, 3000))
    }
}

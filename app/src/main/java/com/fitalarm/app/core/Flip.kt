package com.fitalarm.app.core

/**
 * "휴대폰을 뒤집었다" 동작 판정 (센서와 분리한 순수 로직이라 단위 테스트 가능).
 *
 * z: 중력의 화면 수직 성분(m/s²). 화면이 하늘을 보면 약 +9.8, 바닥을 보면 약 -9.8.
 *
 * 처음부터 엎어 둔 채로 알람이 울리면 소리가 줄면 안 되므로(잠을 못 깸),
 * 엎어지지 않은 상태를 ARM_MS 동안 먼저 본 뒤에, 엎어진 상태가 HOLD_MS 이어질 때만 한 번 감지합니다.
 * 잠깐 엎어졌다 바로 돌아오는 흔들림은 무시합니다.
 */
class FlipGesture(
    private val armMs: Long = ARM_MS,
    private val holdMs: Long = HOLD_MS,
) {
    private var notDownSince = NONE
    private var armed = false
    private var downSince = NONE

    var fired = false
        private set

    /** 샘플 하나를 넣고, 이번 샘플로 뒤집기가 확정되면 true (한 번만) */
    fun onSample(z: Float, timeMs: Long): Boolean {
        if (fired) return false
        if (!armed) {
            if (z > NOT_DOWN_Z) {
                if (notDownSince == NONE) notDownSince = timeMs
                if (timeMs - notDownSince >= armMs) armed = true
            } else {
                notDownSince = NONE
            }
            return false
        }
        if (z < FACE_DOWN_Z) {
            if (downSince == NONE) downSince = timeMs
            if (timeMs - downSince >= holdMs) {
                fired = true
                return true
            }
        } else {
            downSince = NONE
        }
        return false
    }

    companion object {
        /** 이보다 작으면 엎어짐 (바닥과 약 40도 이내로 엎어진 상태) */
        const val FACE_DOWN_Z = -7.5f
        /** 이보다 크면 엎어지지 않음 (화면이 위, 세움, 옆으로 눕힘 모두) */
        const val NOT_DOWN_Z = -2f
        const val ARM_MS = 300L
        const val HOLD_MS = 600L
        private const val NONE = Long.MIN_VALUE
    }
}

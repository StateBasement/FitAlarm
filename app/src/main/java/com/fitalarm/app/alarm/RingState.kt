package com.fitalarm.app.alarm

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 지금 울리는 알람이 무엇인지 앱 프로세스 안에서 공유.
 * 서비스가 알람을 끄거나 다시 알림하면 열려 있는 알람/미션 화면이 이를 보고 닫힙니다.
 */
object RingState {
    @Volatile
    var ringingAlarmId: Int = NONE
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val isRinging: Boolean get() = ringingAlarmId != NONE

    fun set(alarmId: Int) {
        ringingAlarmId = alarmId
        notifyListeners()
    }

    fun clear() {
        ringingAlarmId = NONE
        notifyListeners()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }

    const val NONE = -1
}

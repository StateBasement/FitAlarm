package com.fitalarm.app.alarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.fitalarm.app.core.FlipGesture

/**
 * 알람이 울리는 동안만 휴대폰 방향을 확인해 "뒤집기"를 알려줍니다.
 * 센서 값은 판정에만 쓰고 저장하거나 전송하지 않습니다. 권한이 필요 없는 일반 센서입니다.
 */
class FlipSensor(context: Context, private val onFlipped: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // 중력 센서가 있으면 흔들림이 걸러진 값을 쓰고, 없으면 가속도 센서 값을 부드럽게 해서 씀
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor = gravity ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gesture = FlipGesture()
    private var smoothZ = Float.NaN
    private var running = false

    /** 센서가 없으면 false (이 기능 없이 알람은 그대로 동작) */
    fun start(): Boolean {
        val m = manager ?: return false
        val s = sensor ?: return false
        running = m.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        return running
    }

    fun stop() {
        if (running) manager?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val z = event.values.getOrNull(2) ?: return
        smoothZ = if (gravity != null || smoothZ.isNaN()) z else smoothZ + LOW_PASS * (z - smoothZ)
        if (gesture.onSample(smoothZ, event.timestamp / 1_000_000L)) {
            stop()
            onFlipped()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val LOW_PASS = 0.2f
    }
}

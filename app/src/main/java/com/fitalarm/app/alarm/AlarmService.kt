package com.fitalarm.app.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.KoreanSpeech
import com.fitalarm.app.core.RecordEntry
import com.fitalarm.app.core.RecordKind
import com.fitalarm.app.data.RecordRepository
import com.fitalarm.app.data.SettingsRepository
import com.fitalarm.app.voice.Speaker
import java.time.LocalDate
import java.time.LocalDateTime
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.defaultVibrator
import com.fitalarm.app.systemService
import kotlin.math.ceil

/**
 * 알람이 울리는 동안 실행되는 포그라운드 서비스.
 * 소리(점점 크게), 진동, 울림 시간 제한, 다시 알림, 미션 중 볼륨 낮춤을 담당합니다.
 *
 * 상태 전이:
 *   IDLE ──START──▶ RINGING ──SNOOZE / 시간초과(남은 횟수 있음)──▶ IDLE (다시 알림 예약)
 *                      │──DISMISS(미션 알람은 미션 완료 시에만)────▶ IDLE (다음 반복 예약)
 *                      └──시간초과(남은 횟수 없음)────────────────▶ IDLE (놓친 알람 알림)
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var current: Alarm? = null

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var savedAlarmVolume = -1
    private var vibrating = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var crescendoStart = 0L
    private var missionActive = false
    private var lastMissionProgress = 0L
    private var isForeground = false

    // 음성 안내
    private var speaker: Speaker? = null
    private var ducked = false
    private var currentIsSnooze = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 포그라운드 알림에 쓸 채널이 반드시 있어야 하므로 (앱을 한 번도 안 연 상태 등) 여기서도 보장
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_START -> handleStart(id)
            ACTION_SNOOZE -> handleSnooze(id)
            ACTION_DISMISS -> handleDismiss(
                id,
                missionDone = intent.getBooleanExtra(EXTRA_MISSION_DONE, false),
                skipMission = intent.getBooleanExtra(EXTRA_SKIP_MISSION, false),
            )
            ACTION_MISSION_STATE -> {
                setMissionActive(intent.getBooleanExtra(EXTRA_MISSION_ACTIVE, false))
                stopIfIdle()
            }
            ACTION_MISSION_PROGRESS -> {
                onMissionProgress()
                stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        if (RingState.isRinging) RingState.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 시작

    private fun handleStart(alarmId: Int) {
        val alarm = AlarmRepository.get(this, alarmId)
        if (alarm == null || !alarm.enabled) {
            // 삭제되었거나 그 사이 꺼진 알람: startForegroundService 계약상 startForeground 는 반드시 호출한 뒤 종료
            enterForeground(Notifications.buildRinging(this, alarm ?: Alarm(id = alarmId)))
            if (current == null) stopSelfCompletely()
            else enterForeground(Notifications.buildRinging(this, current!!))  // 울리던 알람의 알림 복원
            return
        }

        // 다른 알람이 이미 울리고 있으면 그 알람은 종료 처리 후 새 알람으로 교체
        current?.let { prev -> if (prev.id != alarm.id) finishRinging(prev, stopService = false) }

        val now = System.currentTimeMillis()
        val isSnoozeFire = alarm.snoozedUntil != 0L && alarm.snoozedUntil <= now + 60_000L
        var updated = alarm.copy(snoozedUntil = 0L)
        if (!alarm.isRepeating) updated = updated.copy(enabled = false)  // 1회성: 울린 시점에 소모
        if (!isSnoozeFire) updated = updated.copy(snoozeCount = 0)        // 새 회차는 다시 알림 횟수 초기화
        AlarmRepository.upsert(this, updated)
        AlarmScheduler(this).schedule(updated)                            // 반복 알람: 다음 회차 예약

        current = updated
        enterForeground(Notifications.buildRinging(this, updated))
        Notifications.cancelSnoozed(this, updated.id)

        acquireWakeLock()
        missionActive = false
        currentIsSnooze = isSnoozeFire
        startSound(updated)
        startVibration()
        armRingTimeout(updated)
        RingState.set(updated.id)
        startAnnouncements(updated)
    }

    // ------------------------------------------------------------------ 다시 알림 / 끄기

    private fun handleSnooze(alarmId: Int) {
        val a = current
        if (a == null || a.id != alarmId) {
            stopIfIdle()
            return
        }
        if (!a.canSnooze()) return
        snooze(a)
    }

    private fun snooze(a: Alarm) {
        val until = System.currentTimeMillis() + a.snoozeMinutes * 60_000L
        val latest = (AlarmRepository.get(this, a.id) ?: a).copy(
            enabled = true,
            snoozedUntil = until,
            snoozeCount = a.snoozeCount + 1,
        )
        AlarmRepository.upsert(this, latest)
        AlarmScheduler(this).schedule(latest)
        Notifications.showSnoozed(this, latest, until)
        current = null
        stopRinging()
        RingState.clear()
        stopSelfCompletely()
    }

    private fun handleDismiss(alarmId: Int, missionDone: Boolean, skipMission: Boolean) {
        val a = current
        if (a == null || a.id != alarmId) {
            // 울리는 중이 아님: 알림의 "지금 끄기"로 대기 중인 다시 알림을 취소한 경우
            AlarmRepository.get(this, alarmId)?.let { stored ->
                if (stored.snoozedUntil > 0L && !stored.hasMission) {
                    val upd = stored.copy(snoozedUntil = 0L, snoozeCount = 0, enabled = stored.enabled && stored.isRepeating)
                    AlarmRepository.upsert(this, upd)
                    AlarmScheduler(this).schedule(upd)
                }
            }
            Notifications.cancelSnoozed(this, alarmId)
            stopIfIdle()
            return
        }
        // 미션 알람은 미션 완료 또는 "운동 없이 끄기"(3초 누르기)로만 끌 수 있음
        if (a.hasMission && !missionDone && !skipMission) return
        when {
            // 미션 완료의 기록은 미션 화면이 운동 횟수와 함께 남김
            a.hasMission && skipMission && !missionDone -> record(a, RecordKind.SKIP)
            !a.hasMission -> record(a, RecordKind.DISMISS)
        }
        finishRinging(a, stopService = true)
    }

    /** 알람 종료: 다시 알림 상태 초기화, 1회성이면 꺼짐 유지, 반복이면 다음 회차 예약 */
    private fun finishRinging(a: Alarm, stopService: Boolean) {
        val stored = AlarmRepository.get(this, a.id)
        if (stored != null) {
            val upd = stored.copy(
                snoozedUntil = 0L,
                snoozeCount = 0,
                enabled = stored.isRepeating && stored.enabled,
            )
            AlarmRepository.upsert(this, upd)
            AlarmScheduler(this).schedule(upd)
        }
        current = null
        stopRinging()
        RingState.clear()
        if (stopService) stopSelfCompletely()
    }

    // ------------------------------------------------------------------ 울림 시간 제한

    private val ringTimeout = Runnable { onRingTimeout() }

    private fun armRingTimeout(a: Alarm) {
        handler.removeCallbacks(ringTimeout)
        if (a.ringMinutes != Alarm.RING_UNTIL_DISMISSED) {
            handler.postDelayed(ringTimeout, a.ringMinutes * 60_000L)
        }
    }

    private fun onRingTimeout() {
        val a = current ?: return
        if (isMissionQuiet()) {
            // 미션을 진행 중이면 끊지 않고 1분 뒤 다시 확인
            handler.postDelayed(ringTimeout, 60_000L)
            return
        }
        if (a.canSnooze()) {
            snooze(a)
        } else {
            Notifications.showMissed(this, a)
            record(a, RecordKind.MISSED)
            finishRinging(a, stopService = true)
        }
    }

    // ------------------------------------------------------------------ 미션 연동

    private val missionIdleCheck = Runnable { applyLevels() }

    private fun setMissionActive(active: Boolean) {
        if (current == null) return
        missionActive = active
        if (active) stopAnnouncements()
        lastMissionProgress = SystemClock.elapsedRealtime()
        applyLevels()
        handler.removeCallbacks(missionIdleCheck)
        if (active) handler.postDelayed(missionIdleCheck, MISSION_IDLE_MS + 100)
    }

    private fun onMissionProgress() {
        if (current == null) return
        lastMissionProgress = SystemClock.elapsedRealtime()
        applyLevels()
        handler.removeCallbacks(missionIdleCheck)
        handler.postDelayed(missionIdleCheck, MISSION_IDLE_MS + 100)
    }

    /** 미션 중이고 최근 MISSION_IDLE_MS 안에 진행이 있으면 소리를 줄이고 진동을 멈춤 */
    private fun isMissionQuiet(): Boolean =
        missionActive && SystemClock.elapsedRealtime() - lastMissionProgress < MISSION_IDLE_MS

    private fun applyLevels() {
        if (isMissionQuiet()) stopVibration() else startVibration()
        updatePlayerVolume()
    }

    // ------------------------------------------------------------------ 소리

    private fun startSound(a: Alarm) {
        stopSound()
        if (a.silent || a.volume <= 0) return
        requestAudioFocus()
        setAlarmStreamVolume(a.volume)

        val candidates = listOfNotNull(
            a.soundUri?.let { Uri.parse(it) },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        // 선택한 파일이 지워졌거나 권한이 사라졌으면 다음 후보로 대체
        player = candidates.asSequence().mapNotNull { createPlayer(it) }.firstOrNull()
        crescendoStart = SystemClock.elapsedRealtime()
        updatePlayerVolume()
        player?.start()
        handler.removeCallbacks(volumeRamp)
        if (a.crescendo) handler.postDelayed(volumeRamp, RAMP_STEP_MS)
    }

    private fun createPlayer(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(ALARM_ATTRIBUTES)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.prepare()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    private val volumeRamp = object : Runnable {
        override fun run() {
            updatePlayerVolume()
            if (SystemClock.elapsedRealtime() - crescendoStart < CRESCENDO_MS) {
                handler.postDelayed(this, RAMP_STEP_MS)
            }
        }
    }

    private fun updatePlayerVolume() {
        val p = player ?: return
        val a = current
        val ramp = if (a != null && a.crescendo) {
            val progress = ((SystemClock.elapsedRealtime() - crescendoStart).toFloat() / CRESCENDO_MS).coerceIn(0f, 1f)
            CRESCENDO_START + (1f - CRESCENDO_START) * progress
        } else 1f
        val level = ramp * (if (isMissionQuiet()) MISSION_VOLUME else 1f) * (if (ducked) DUCK_VOLUME else 1f)
        try {
            p.setVolume(level, level)
        } catch (e: IllegalStateException) {
            // 이미 해제된 경우 무시
        }
    }

    private fun setAlarmStreamVolume(percent: Int) {
        val am = systemService<AudioManager>()
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = ceil(max * percent / 100.0).toInt().coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: SecurityException) {
            savedAlarmVolume = -1   // 방해 금지 모드 정책 등으로 변경 불가
        }
    }

    private fun restoreAlarmStreamVolume() {
        if (savedAlarmVolume < 0) return
        try {
            systemService<AudioManager>()
                .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        } catch (e: SecurityException) {
            // 무시
        }
        savedAlarmVolume = -1
    }

    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ALARM_ATTRIBUTES)
            .setOnAudioFocusChangeListener { /* 알람은 포커스를 잃어도 계속 울림 */ }
            .build()
        systemService<AudioManager>().requestAudioFocus(req)
        focusRequest = req
    }

    private fun stopSound() {
        handler.removeCallbacks(volumeRamp)
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // 무시
            }
            it.release()
        }
        player = null
        focusRequest?.let { systemService<AudioManager>().abandonAudioFocusRequest(it) }
        focusRequest = null
        restoreAlarmStreamVolume()
    }

    // ------------------------------------------------------------------ 진동

    private fun vibrator(): Vibrator = defaultVibrator()

    private fun startVibration() {
        val a = current ?: return
        if (!a.vibrate || vibrating) return
        val v = vibrator()
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(a.vibrationPattern.timings, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, ALARM_ATTRIBUTES)
        }
        vibrating = true
    }

    private fun stopVibration() {
        if (!vibrating) return
        vibrator().cancel()
        vibrating = false
    }

    // ------------------------------------------------------------------ 오늘의 운동 음성 안내

    private val announceRunnable = Runnable { announce() }

    private fun startAnnouncements(a: Alarm) {
        val settings = SettingsRepository.get(this)
        if (!settings.voiceAnnounce || !a.announce) return
        speaker?.shutdown()
        speaker = Speaker(this, AudioAttributes.USAGE_ALARM, settings.voiceStyle) { ok ->
            if (ok && current?.id == a.id) handler.postDelayed(announceRunnable, FIRST_ANNOUNCE_DELAY_MS)
        }
    }

    /** 알람 소리를 줄이고 안내를 읽은 뒤 원래 크기로. 설정에 따라 1분 뒤 반복 */
    private fun announce() {
        val a = current ?: return
        val sp = speaker ?: return
        if (missionActive) return
        val text = KoreanSpeech.announcement(LocalDateTime.now(), a.label, a.routine, a.hasMission, currentIsSnooze)
        ducked = true
        updatePlayerVolume()
        RingState.setAnnouncing(text)
        sp.speak(text) {
            ducked = false
            updatePlayerVolume()
            RingState.setAnnouncing(null)
            val stillRinging = current?.id == a.id && !missionActive
            if (stillRinging && SettingsRepository.get(this).announceRepeat) {
                handler.postDelayed(announceRunnable, REPEAT_ANNOUNCE_MS)
            }
        }
    }

    private fun stopAnnouncements() {
        handler.removeCallbacks(announceRunnable)
        speaker?.stop()
        if (ducked) {
            ducked = false
            updatePlayerVolume()
        }
        if (RingState.announcing != null) RingState.setAnnouncing(null)
    }

    private fun record(a: Alarm, kind: RecordKind) {
        RecordRepository.append(
            this,
            RecordEntry(LocalDate.now(), kind, alarmId = a.id, atMillis = System.currentTimeMillis()),
        )
    }

    // ------------------------------------------------------------------ 수명 관리

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifications.ID_RINGING, notification)
        }
        isForeground = true
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = systemService<PowerManager>()
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitAlarm:ringing")
            .apply { acquire(WAKE_LOCK_MAX_MS) }
    }

    private fun stopRinging() {
        handler.removeCallbacks(ringTimeout)
        handler.removeCallbacks(missionIdleCheck)
        stopAnnouncements()
        speaker?.shutdown()
        speaker = null
        stopSound()
        stopVibration()
        missionActive = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopIfIdle() {
        if (current == null) stopSelfCompletely()
    }

    private fun stopSelfCompletely() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.fitalarm.app.action.START"
        const val ACTION_SNOOZE = "com.fitalarm.app.action.SNOOZE"
        const val ACTION_DISMISS = "com.fitalarm.app.action.DISMISS"
        const val ACTION_MISSION_STATE = "com.fitalarm.app.action.MISSION_STATE"
        const val ACTION_MISSION_PROGRESS = "com.fitalarm.app.action.MISSION_PROGRESS"

        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_MISSION_ACTIVE = "mission_active"
        const val EXTRA_MISSION_DONE = "mission_done"
        const val EXTRA_SKIP_MISSION = "skip_mission"

        private const val CRESCENDO_MS = 30_000L
        private const val CRESCENDO_START = 0.1f
        private const val RAMP_STEP_MS = 500L
        private const val MISSION_VOLUME = 0.25f
        private const val MISSION_IDLE_MS = 45_000L
        private const val WAKE_LOCK_MAX_MS = 60 * 60_000L
        private const val DUCK_VOLUME = 0.2f
        private const val FIRST_ANNOUNCE_DELAY_MS = 2_500L
        private const val REPEAT_ANNOUNCE_MS = 60_000L

        private val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun startIntent(ctx: Context, alarmId: Int): Intent =
            Intent(ctx, AlarmService::class.java).setAction(ACTION_START).putExtra(EXTRA_ALARM_ID, alarmId)

        fun actionIntent(ctx: Context, action: String, alarmId: Int): Intent =
            Intent(ctx, AlarmService::class.java).setAction(action).putExtra(EXTRA_ALARM_ID, alarmId)

        fun missionStateIntent(ctx: Context, active: Boolean): Intent =
            Intent(ctx, AlarmService::class.java).setAction(ACTION_MISSION_STATE).putExtra(EXTRA_MISSION_ACTIVE, active)

        fun missionProgressIntent(ctx: Context): Intent =
            Intent(ctx, AlarmService::class.java).setAction(ACTION_MISSION_PROGRESS)

        fun dismissAfterMissionIntent(ctx: Context, alarmId: Int): Intent =
            actionIntent(ctx, ACTION_DISMISS, alarmId).putExtra(EXTRA_MISSION_DONE, true)

        /** 운동 미션 알람을 운동 없이 끔 (기록: 건너뜀, 연속 기록 끊김) */
        fun skipMissionIntent(ctx: Context, alarmId: Int): Intent =
            actionIntent(ctx, ACTION_DISMISS, alarmId).putExtra(EXTRA_SKIP_MISSION, true)
    }
}

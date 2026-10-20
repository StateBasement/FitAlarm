package com.fitalarm.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.fitalarm.app.core.VoiceStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 폰에 내장된 TTS 엔진(삼성/구글)으로 한국어를 읽음. 인터넷 불필요.
 * 목소리 톤: 여성/남성 음성을 고르고 높낮이·속도를 조절. 남성 음성이 없으면 기본 음성의 음을 낮춤.
 * 모든 콜백은 메인 스레드에서 호출됩니다.
 */
class Speaker(
    context: Context,
    private val usage: Int,
    private val style: VoiceStyle,
    private val onReady: ((Boolean) -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var seq = 0

    var ready = false
        private set

    /** 실제로 고른 음성이 스타일과 맞는지 (설정 화면 안내용) */
    var matchedStyle = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            onReady?.invoke(false)
            return
        }
        val lang = t.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            onReady?.invoke(false)
            return
        }
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val voice = pickVoice(t, style.wantMale)
        if (voice != null) {
            try {
                t.voice = voice
            } catch (e: Exception) {
                // 일부 엔진은 음성 변경을 지원하지 않음
            }
        }
        matchedStyle = voice != null && isMale(voice) == style.wantMale
        // 남성 음성이 없으면 기본(여성) 음성의 음을 더 낮춰 구분되게
        t.setPitch(if (style.wantMale && !matchedStyle) 0.72f else style.pitch)
        t.setSpeechRate(style.rate)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = fire(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = fire(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = fire(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = fire(utteranceId)
        })
        ready = true
        onReady?.invoke(true)
    }

    /** 읽기. 준비 안 됐거나 실패하면 onDone 을 바로 호출 */
    fun speak(text: String, flush: Boolean = true, onDone: (() -> Unit)? = null): Boolean {
        val t = tts
        if (!ready || t == null) {
            onDone?.let { main.post(it) }
            return false
        }
        val id = "u${++seq}"
        if (onDone != null) callbacks[id] = onDone
        val r = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) {
            fire(id)
            return false
        }
        return true
    }

    fun stop() {
        tts?.stop()
        val pending = callbacks.values.toList()
        callbacks.clear()
        pending.forEach { main.post(it) }
    }

    fun shutdown() {
        callbacks.clear()
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ready = false
    }

    private fun fire(id: String) {
        callbacks.remove(id)?.let { main.post(it) }
    }

    companion object {
        // 엔진별 음성 이름 관례: 구글 ko-kr-x-ism/kob(여), koc/kod(남), 삼성 ...SMTf/SMTm..., 그 외 female/male
        private val FEMALE = Regex("female|smtf|-f\\d|_f\\d|x-ism|x-kob|#f", RegexOption.IGNORE_CASE)
        private val MALE = Regex("\\bmale|smtm|-m\\d|_m\\d|x-koc|x-kod|#m", RegexOption.IGNORE_CASE)

        fun isMale(v: Voice): Boolean = MALE.containsMatchIn(v.name) && !FEMALE.containsMatchIn(v.name)

        private fun pickVoice(t: TextToSpeech, wantMale: Boolean): Voice? {
            val voices = try {
                t.voices
            } catch (e: Exception) {
                null
            } ?: return null
            val korean = voices.filter { it.locale.language == "ko" }
            val installed = korean.filter {
                !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.ifEmpty { korean }
            val matching = installed.filter { isMale(it) == wantMale }
            return matching.maxByOrNull { it.quality }
        }
    }
}

package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Foreground navigation guidance: Android Turkish TTS with audio focus,
 * no fabricated GPS, no system volume mutation and retryable failed cues.
 * Thresholds and route-distance projection are owned by ManeuverGuidancePolicy.
 */
class NativeVoiceGuide(context: Context, private val audioSignal: AppAudioDucking) {
    private val logTag = "EV-Cockpit-TTS"

    companion object {
        private val TURKISH = Locale.forLanguageTag("tr-TR")

        fun availableTurkishVoices(engine: TextToSpeech): List<Voice> =
            runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                .filter {
                    it.locale.language.equals("tr", true) &&
                        it.locale.country.equals("TR", true) &&
                        !it.isNetworkConnectionRequired
                }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })

        fun selectTurkishGreetingVoice(
            engine: TextToSpeech,
            preferredName: String? = null
        ): Boolean {
            if (engine.setLanguage(TURKISH) < TextToSpeech.LANG_AVAILABLE) return false
            val choices = availableTurkishVoices(engine)
            val selected = choices.firstOrNull { it.name == preferredName }
                ?: choices.firstOrNull { voice ->
                    val n = voice.name.lowercase(Locale.ROOT)
                    listOf("female", "woman", "kadın", "kadin").any(n::contains)
                }
            if (selected != null && runCatching {
                    engine.setVoice(selected) == TextToSpeech.SUCCESS
                }.getOrDefault(false)) return true
            return engine.setLanguage(TURKISH) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    enum class Status { LOADING, READY, TURKISH_VOICE_MISSING, INIT_FAILED, AUDIO_FOCUS_DENIED }

    var status: Status by mutableStateOf(Status.LOADING)
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val ducking = AudioDuckingManager(context) { stop() }
    private val attributes: AudioAttributes = ducking.attributes
    private var activeUtterance: String? = null
    private var startedDuckOwner: String? = null
    private var onStarted: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null
    private var sequence = 0L
    private var closed = false
    private var startWatchdog: Runnable? = null
    private var tts: TextToSpeech? = null
    private var chosenVoiceName: String? = null
    private var chosenSpeechRate: Float = 1.0f

    /** The user chooses among actually installed offline tr-TR voices. */
    fun configureNavigationVoice(name: String?, speechRate: Float) {
        chosenVoiceName = name?.takeIf(String::isNotBlank)
        chosenSpeechRate = speechRate.takeIf { it.isFinite() }?.coerceIn(0.7f, 1.4f)
            ?: 1.0f
        main.post {
            if (!closed && status != Status.LOADING) {
                tts?.let { configureTurkishVoice(it) }
            }
        }
    }

    private fun configureTurkishVoice(engine: TextToSpeech): Boolean {
        if (engine.setLanguage(TURKISH) < TextToSpeech.LANG_AVAILABLE) {
            status = Status.TURKISH_VOICE_MISSING
            lastError = "Türkçe ses paketi yüklü değil."
            return false
        }
        val requested = chosenVoiceName
        if (requested != null) {
            val selected = availableTurkishVoices(engine).firstOrNull { it.name == requested }
            if (selected == null || engine.setVoice(selected) != TextToSpeech.SUCCESS) {
                status = Status.TURKISH_VOICE_MISSING
                lastError = "Seçilen Türkçe ses yüklü değil; Ayarlar bölümünden ses seçin."
                return false
            }
        }
        if (engine.setSpeechRate(chosenSpeechRate) != TextToSpeech.SUCCESS) {
            status = Status.INIT_FAILED
            lastError = "Konuşma hızı ayarlanamadı."
            return false
        }
        status = Status.READY
        lastError = null
        return true
    }


    init {
        tts = try {
            TextToSpeech(context.applicationContext) { result ->
                main.post {
                    if (closed) return@post
                    val engine = tts
                    if (result != TextToSpeech.SUCCESS || engine == null) {
                        status = Status.INIT_FAILED
                        lastError = "Türkçe konuşma motoru başlatılamadı"
                        Log.e(logTag, "TTS init failed: $result")
                        return@post
                    }
                    val available = TtsDebugLogger.languageAvailability(engine)
                    val selected = if (available >= TextToSpeech.LANG_AVAILABLE)
                        engine.setLanguage(TURKISH) else TextToSpeech.LANG_NOT_SUPPORTED
                    TtsDebugLogger.selectedLanguage(selected)
                    if (selected < TextToSpeech.LANG_AVAILABLE) {
                        status = Status.TURKISH_VOICE_MISSING
                        lastError = "Türkçe TTS ses verisi yüklü değil"
                        Log.e(logTag, "Install tr-TR TTS voice data on the phone")
                        return@post
                    }
                    runCatching {
                        engine.setAudioAttributes(attributes)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String) {
                                main.post {
                                    if (!closed && activeUtterance == id) {
                                        clearWatchdog()
                                        startedDuckOwner?.let(audioSignal::end)
                                        startedDuckOwner = "navigation:$id"
                                        startedDuckOwner?.let(audioSignal::begin)
                                        Log.i(logTag, "Maneuver TTS started")
                                        onStarted?.invoke()
                                        onStarted = null
                                        // After onStart this stage has been spoken.
                                        onFailed = null
                                    }
                                }
                            }
                            override fun onDone(id: String) = finish(id)
                            override fun onError(id: String) = synthesisError(id, "TTS error")
                            override fun onError(id: String, errorCode: Int) {
                                TtsDebugLogger.utteranceError(errorCode)
                                synthesisError(id, "TTS error code=$errorCode")
                            }
                            override fun onStop(id: String, interrupted: Boolean) = finish(id)
                        })
                    }.onFailure {
                        status = Status.INIT_FAILED
                        lastError = "Sesli yönlendirme başlatılamadı"
                        Log.e(logTag, "TTS configuration failed", it)
                        return@post
                    }
                    if (!configureTurkishVoice(engine)) return@post
                    Log.i(logTag, "Turkish TTS ready")
                }
            }
        } catch (error: RuntimeException) {
            status = Status.INIT_FAILED
            lastError = "Telefonun TTS motoruna erişilemiyor"
            Log.e(logTag, "TTS constructor failed", error)
            null
        }
    }

    /** Accepted is not the same as audible: caller must confirm the onStart callback. */
    fun speak(
        text: String,
        volume: Float,
        onUtteranceStarted: (() -> Unit)? = null,
        onUtteranceFailed: (() -> Unit)? = null
    ): Boolean {
        if (closed || status !in setOf(Status.READY, Status.AUDIO_FOCUS_DENIED) ||
            text.isBlank() || text.length > 190) {
            Log.w(logTag, "TTS skipped: status=$status")
            return false
        }
        val engine = tts ?: return false
        stopCurrent(notifyFailure = false)
        val focusGranted = runCatching { ducking.acquire() }.getOrDefault(false)
        if (focusGranted) {
            status = Status.READY
            lastError = null
        } else {
            status = Status.AUDIO_FOCUS_DENIED
            lastError = "Ses odağı alınamadı; konuşma başlatılmadı."
            Log.w(logTag, "Audio focus not granted")
            return false
        }
        val id = "evc-voice-${++sequence}"
        activeUtterance = id
        onStarted = onUtteranceStarted
        onFailed = onUtteranceFailed
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(.15f, 1f))
        }
        val accepted = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!accepted) {
            lastError = "TTS isteği kabul edilmedi"
            Log.e(logTag, "TTS speak() rejected")
            stopCurrent(notifyFailure = true)
            return false
        }
        // Some ROM/TTS combinations accept speak() but never emit onStart.
        // Release the pending maneuver so the next actual GPS fix can retry it.
        val watchdog = Runnable {
            if (!closed && activeUtterance == id && onStarted != null) {
                lastError = "TTS başladı yanıtı alınamadı"
                Log.w(logTag, "TTS start timeout: $id")
                stopCurrent(notifyFailure = true)
            }
        }
        startWatchdog = watchdog
        main.postDelayed(watchdog, 4_000L)
        return true
    }

    /** Optional weather announcements never interrupt an active navigation cue. */
    fun speakIfIdle(text: String, volume: Float): Boolean {
        if (activeUtterance != null) return false
        return speak(text, volume)
    }

    fun stop() = stopCurrent(notifyFailure = true)

    private fun clearWatchdog() {
        startWatchdog?.let(main::removeCallbacks)
        startWatchdog = null
    }

    private fun stopCurrent(notifyFailure: Boolean) {
        clearWatchdog()
        runCatching { tts?.stop() }
        activeUtterance = null
        onStarted = null
        val failed = onFailed
        onFailed = null
        if (notifyFailure) failed?.invoke()
        releaseFocus()
    }

    private fun synthesisError(id: String, reason: String) {
        main.post {
            if (!closed && activeUtterance == id) {
                lastError = reason
                Log.e(logTag, "Utterance synthesis failed: $reason")
                stopCurrent(notifyFailure = true)
            }
        }
    }

    private fun finish(id: String) {
        main.post {
            if (id == activeUtterance) {
                clearWatchdog()
                activeUtterance = null
                onStarted = null
                onFailed = null
                releaseFocus()
                Log.d(logTag, "TTS finished")
            }
        }
    }

    private fun releaseFocus() {
        startedDuckOwner?.let(audioSignal::end)
        startedDuckOwner = null
        ducking.release()
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        runCatching { tts?.shutdown() }
        tts = null
        ducking.close()
    }
}
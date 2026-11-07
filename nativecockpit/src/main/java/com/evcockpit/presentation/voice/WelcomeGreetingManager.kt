package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.evcockpit.data.settings.UserSettings
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.car.CarNavigationOwnership
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Turkish welcome greeting, once per actual OBD connection and once per
 * Android Auto application process. It is NOT an ignition detector.
 * No audio file is necessary: speech is synthesized by installed Android TTS.
 */
@Singleton
class WelcomeGreetingManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: UserSettingsRepository,
    private val audioDucking: AppAudioDucking
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val voicePrefs = VoicePreference(appContext)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()
    fun dismissWarning() { _warning.value = null }

    private var obdClaimed = false
    private var carClaimed = false
    private var active = false
    private var initializing = false
    private var focusGranted = false
    private var engine: TextToSpeech? = null
    private var userSettings = UserSettings()
    private var generation = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) main.post { closeSession() }
    }
    private val focusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    ).setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()

    private val initializationTimeout = Runnable {
        if (initializing) {
            Log.w(TAG, "Turkish TTS engine did not initialize")
            _warning.value = "Türkçe TTS motoru başlatılamadı. Telefonun metin okuma ayarlarını kontrol edin."
            closeSession()
        }
    }
    private val speechTimeout = Runnable {
        if (active) {
            Log.w(TAG, "Welcome speech timed out")
            closeSession()
        }
    }

    /** Called only on a real OBD DISCONNECTED -> CONNECTED transition. */
    fun launchObdGreeting() = requestGreeting(isObd = true)

    /** Used by CarAppService for a genuine Android Auto session. */
    fun launchWelcomeGreeting() = requestGreeting(isObd = false)

    /** Re-arm when a real OBD session ends. Safe on repeated DISCONNECTED states. */
    fun onObdDisconnected() {
        main.post { obdClaimed = false }
    }

    /** Host navigation is higher-priority than optional startup speech. */
    fun stopForNavigation() {
        main.post { closeSession() }
    }

    private fun navigationOwnsSpeech(): Boolean =
        CarNavigationOwnership.navigating.value || audioDucking.hasNavigationSpeech()

    private fun requestGreeting(isObd: Boolean) {
        main.post {
            if (isObd) {
                if (obdClaimed) return@post
                obdClaimed = true
            } else {
                if (carClaimed) return@post
                carClaimed = true
            }
            scope.launch {
                val current = try { settings.settings.first() }
                    catch (error: Exception) {
                        Log.w(TAG, "Welcome settings unavailable", error)
                        return@launch
                    }
                if (navigationOwnsSpeech()) {
                    Log.i(TAG, "Welcome suppressed: active navigation has priority")
                    return@launch
                }
                if (!current.isWelcomeGreetingEnabled) {
                    Log.i(TAG, "Welcome disabled by user")
                    return@launch
                }
                userSettings = current
                if (!active) startSession()
            }
        }
    }

    private fun startSession() {
        active = true
        initializing = true
        val id = ++generation
        main.postDelayed(initializationTimeout, 8_000L)
        try {
            engine = TextToSpeech(appContext) { result ->
                main.post {
                    if (!active || !initializing || id != generation) return@post
                    initializing = false
                    main.removeCallbacks(initializationTimeout)
                    val tts = engine
                    val ready = result == TextToSpeech.SUCCESS && tts != null &&
                        NativeVoiceGuide.selectTurkishGreetingVoice(
                            tts, userSettings.ttsVoiceName
                        )
                    if (!ready) {
                        _warning.value = "Türkçe ses paketi bulunamadı. Telefonunuzun metin okuma (TTS) ayarlarından Türkçe sesi yükleyin."
                        Log.e(TAG, "Turkish TTS voice missing or unavailable")
                        closeSession()
                        return@post
                    }
                    val configured = runCatching {
                        tts!!.setAudioAttributes(attrs)
                        tts.setSpeechRate(userSettings.ttsSpeechRate)
                        tts.setOnUtteranceProgressListener(
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String) {
                                    if (utteranceId == UTTERANCE_ID) main.post {
                                        if (active && id == generation) {
                                            audioDucking.begin("welcome")
                                            Log.i(TAG, "Turkish greeting started")
                                        }
                                    }
                                }
                                override fun onDone(utteranceId: String) = finish(utteranceId)
                                override fun onError(utteranceId: String) = finish(utteranceId)
                                override fun onError(utteranceId: String, errorCode: Int) {
                                    Log.e(TAG, "Welcome synthesis error: $errorCode")
                                    finish(utteranceId)
                                }
                                override fun onStop(utteranceId: String, interrupted: Boolean) =
                                    finish(utteranceId)
                            }
                        )
                    }.isSuccess
                    if (!configured) {
                        _warning.value = "Türkçe ses motoru hazırlanamadı."
                        closeSession()
                        return@post
                    }
                    // TTS engine initialization may complete before audio routing settles.
                    main.postDelayed({ if (active && id == generation) speakGreeting() }, 350L)
                }
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Welcome TTS creation failed", error)
            _warning.value = "Türkçe ses motoruna erişilemedi."
            closeSession()
        }
    }

    private fun speakGreeting() {
        if (navigationOwnsSpeech()) {
            Log.i(TAG, "Welcome skipped: navigation is active")
            closeSession()
            return
        }
        val tts = engine ?: return closeSession()
        focusGranted = runCatching {
            audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (!focusGranted) {
            Log.w(TAG, "Welcome audio focus denied; greeting not spoken")
            closeSession()
            return
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, voicePrefs.volume.coerceIn(0.15f, 1f))
        }
        val accepted = runCatching {
            tts.speak(
                "Merhaba yol arkadaşım. İyi yolculuklar.",
                TextToSpeech.QUEUE_FLUSH,
                params,
                UTTERANCE_ID
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!accepted) {
            Log.e(TAG, "Welcome speech could not be queued")
            closeSession()
            return
        }
        main.postDelayed(speechTimeout, 15_000L)
    }

    private fun finish(id: String) {
        if (id == UTTERANCE_ID) main.post { closeSession() }
    }

    private fun closeSession() {
        if (!active) return
        active = false
        initializing = false
        ++generation // Reject late callbacks from the previous TTS instance.
        main.removeCallbacks(initializationTimeout)
        main.removeCallbacks(speechTimeout)
        audioDucking.end("welcome")
        val old = engine
        engine = null
        runCatching { old?.stop() }
        runCatching { old?.shutdown() }
        if (focusGranted) {
            focusGranted = false
            runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        }
    }

    companion object {
        private const val TAG = "EV-Cockpit-Welcome"
        private const val UTTERANCE_ID = "ev_cockpit_welcome"
    }
}
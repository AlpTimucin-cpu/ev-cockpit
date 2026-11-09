# Sesli karşılama, kişisel ayarlar, motor profilleri — tam kod

Bu kaynak paketi `feature/audio-settings-active-sound` geliştirme dalındaki **eksiksiz dosya içeriklerini** barındırır. Uygulama/OBD mimarisi değiştirilmedi; kodu tek tek telefondan kopyalamak yerine GitHub PR tercih edilebilir. WAV dosyaları Gradle ile derleme sırasında özgün olarak üretilir.

## 1. `nativecockpit/src/main/java/com/evcockpit/data/settings/UserSettingsRepository.kt`

````kotlin
package com.evcockpit.data.settings

import android.content.Context
import java.io.IOException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.evAudioSettings by preferencesDataStore(name = "ev_audio_settings_v1")

data class UserSettings(
    val isWelcomeGreetingEnabled: Boolean = true,
    val ttsVoiceName: String? = null,
    val ttsSpeechRate: Float = 1.0f,
    val isNightModeEnabled: Boolean = false,
    val isEngineSoundEnabled: Boolean = false,
    val engineSoundProfile: String = "V8",
    val engineVolume: Float = 1.0f
)

@Singleton
class UserSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.applicationContext.evAudioSettings

    private object Keys {
        val WELCOME = booleanPreferencesKey("welcome")
        val VOICE = stringPreferencesKey("voice")
        val RATE = floatPreferencesKey("speech_rate")
        val NIGHT = booleanPreferencesKey("night")
        val ENGINE = booleanPreferencesKey("engine")
        val PROFILE = stringPreferencesKey("engine_profile")
        val VOLUME = floatPreferencesKey("engine_volume")
    }

    val settings: Flow<UserSettings> = store.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { p ->
            UserSettings(
                isWelcomeGreetingEnabled = p[Keys.WELCOME] ?: true,
                ttsVoiceName = p[Keys.VOICE]?.takeIf(String::isNotBlank),
                ttsSpeechRate = (p[Keys.RATE] ?: 1f).coerceIn(.7f, 1.4f),
                isNightModeEnabled = p[Keys.NIGHT] ?: false,
                isEngineSoundEnabled = p[Keys.ENGINE] ?: false,
                engineSoundProfile = (p[Keys.PROFILE] ?: "V8")
                    .takeIf { it in ENGINE_PROFILES } ?: "V8",
                engineVolume = (p[Keys.VOLUME] ?: 1f).coerceIn(0f, 1.5f)
            )
        }

    suspend fun setWelcomeGreetingEnabled(value: Boolean) =
        store.edit { it[Keys.WELCOME] = value }

    suspend fun setTtsVoiceName(value: String?) =
        store.edit { p ->
            if (value.isNullOrBlank()) p.remove(Keys.VOICE)
            else p[Keys.VOICE] = value.take(150)
        }

    suspend fun setTtsSpeechRate(value: Float) =
        store.edit { it[Keys.RATE] = value.coerceIn(.7f, 1.4f) }

    suspend fun setNightModeEnabled(value: Boolean) =
        store.edit { it[Keys.NIGHT] = value }

    suspend fun setEngineSoundEnabled(value: Boolean) =
        store.edit { it[Keys.ENGINE] = value }

    suspend fun setEngineSoundProfile(value: String) =
        store.edit { it[Keys.PROFILE] = value.takeIf { s -> s in ENGINE_PROFILES } ?: "V8" }

    suspend fun setEngineVolume(value: Float) =
        store.edit { it[Keys.VOLUME] = value.coerceIn(0f, 1.5f) }

    companion object {
        val ENGINE_PROFILES = listOf("V8", "F1", "Futuristic", "Turbine", "Stealth")
    }
}
````

## 2. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/AppAudioDucking.kt`

````kotlin
package com.evcockpit.presentation.voice

import androidx.compose.runtime.staticCompositionLocalOf
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app speech arbitration. Does not modify the system audio stream or
 * other applications; Android AudioFocus handles cooperative external apps.
 */
@Singleton
class AppAudioDucking @Inject constructor() {
    private val activeSpeakers = mutableSetOf<String>()
    private val state = MutableStateFlow(false)
    val ducked: StateFlow<Boolean> = state.asStateFlow()

    @Synchronized fun begin(owner: String) {
        activeSpeakers.add(owner)
        state.value = activeSpeakers.isNotEmpty()
    }

    @Synchronized fun end(owner: String) {
        activeSpeakers.remove(owner)
        state.value = activeSpeakers.isNotEmpty()
    }
}

/** Shared by the phone radio without adding DI to a Composable. */
val LocalAppAudioDucking = staticCompositionLocalOf<AppAudioDucking?> { null }
````

## 3. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/NativeVoiceGuide.kt`

````kotlin
package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Activity-scoped TTS: foregound-only, focus-safe, no stream-volume changes.
 * Media ducking is requested from Android, not implemented by changing
 * another app's volume. An unavailable en-US voice fails closed.
 */
class NativeVoiceGuide(context: Context, private val audioSignal: AppAudioDucking) {
    private val logTag = "EYA-Voice"

    companion object {
        private val GREETING_LOCALE = Locale.forLanguageTag("tr-TR")

        /** Voice has no guaranteed gender property: prefer explicit engine labels only. */
        fun availableTurkishVoices(engine: TextToSpeech): List<Voice> =
            runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                .filter { v ->
                    v.locale.language.equals("tr", true) &&
                        v.locale.country.equals("TR", true) &&
                        !v.isNetworkConnectionRequired
                }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })

        fun selectTurkishGreetingVoice(
            engine: TextToSpeech,
            preferredName: String? = null
        ): Boolean {
            if (engine.setLanguage(GREETING_LOCALE) < TextToSpeech.LANG_AVAILABLE) return false
            val installed = availableTurkishVoices(engine)
            val preferred = installed.firstOrNull { it.name == preferredName }
            val labelledFemale = installed.firstOrNull { v ->
                val n = v.name.lowercase(Locale.ROOT)
                listOf("female", "woman", "kadın", "kadin").any(n::contains)
            }
            // When no labelled female is installed use the system Turkish default.
            val selection = preferred ?: labelledFemale
            if (selection != null &&
                runCatching { engine.setVoice(selection) == TextToSpeech.SUCCESS }
                    .getOrDefault(false)
            ) return true
            return engine.setLanguage(GREETING_LOCALE) >= TextToSpeech.LANG_AVAILABLE
        }

    }

    enum class Status { LOADING, READY, TURKISH_VOICE_MISSING, INIT_FAILED, AUDIO_FOCUS_DENIED }

    var status: Status by mutableStateOf(Status.LOADING)
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val ducking = AudioDuckingManager(context) { stop() }
    private val attrs: AudioAttributes = ducking.attributes
    private var activeUtterance: String? = null
    private var startedDuckOwner: String? = null
    private var onStarted: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null
    private var sequence = 0L
    private var closed = false

    private var tts: TextToSpeech? = null
    init {
        tts = TextToSpeech(context.applicationContext) { result ->
        main.post {
                if (closed) return@post
                val engine = tts
                if (result != TextToSpeech.SUCCESS || engine == null) {
                    status = Status.INIT_FAILED
                    lastError = "Could not initialize the TTS engine"
                    Log.e(logTag, "TextToSpeech initialization failed: result=$result")
                    return@post
                }
                val locale = Locale("en", "US")
                val supported = TtsDebugLogger.languageAvailability(engine)
                val selected = if (supported >= TextToSpeech.LANG_AVAILABLE)
                    engine.setLanguage(locale) else TextToSpeech.LANG_NOT_SUPPORTED
                TtsDebugLogger.selectedLanguage(selected)
                if (selected < TextToSpeech.LANG_AVAILABLE) {
                    status = Status.TURKISH_VOICE_MISSING
                    lastError = "English TTS Data Missing"
                    Log.e(logTag, "en-US voice unavailable: supported=$supported selected=$selected")
                } else {
                    engine.setAudioAttributes(attrs)
                    engine.setSpeechRate(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) {
                            main.post {
                                if (!closed && activeUtterance == utteranceId) {
                                    startedDuckOwner?.let(audioSignal::end)
                                    startedDuckOwner = "navigation:" + utteranceId
                                    audioSignal.begin(startedDuckOwner!!)
                                    Log.d(logTag, "Utterance started")
                                    TtsDebugLogger.event("utterance onStart")
                                    onStarted?.invoke()
                                    onStarted = null
                                    // After onStart, a future onError is observable
                                    // but must not mark this already-spoken stage retryable.
                                    onFailed = null
                                }
                            }
                        }
                        override fun onDone(utteranceId: String) { finish(utteranceId) }
                        override fun onError(utteranceId: String) {
                            synthesisError(utteranceId, "TTS speech failed")
                        }
                        override fun onError(utteranceId: String, errorCode: Int) {
                            TtsDebugLogger.utteranceError(errorCode)
                            synthesisError(utteranceId, "TTS speech error: $errorCode")
                        }
                        override fun onStop(utteranceId: String, interrupted: Boolean) {
                            finish(utteranceId)
                        }
                    })
                    status = Status.READY
                }
            }
        }
    }

    /** Returns false rather than speaking without a valid audio-focus grant. */
    fun speak(
        text: String,
        volume: Float,
        onUtteranceStarted: (() -> Unit)? = null,
        onUtteranceFailed: (() -> Unit)? = null
    ): Boolean {
        if (closed || (status != Status.READY && status != Status.AUDIO_FOCUS_DENIED) ||
            text.isBlank() || text.length > 190)
            return false
        val engine = tts ?: return false
        stopCurrent(notifyFailure = false) // Replace old utterance without canceling the NEW cue.
        if (!ducking.acquire()) {
            status = Status.AUDIO_FOCUS_DENIED
            lastError = "Could not obtain audio focus"
            Log.w(logTag, "Navigation audio focus was not granted")
            return false
        }
        status = Status.READY
        lastError = null
        val id = "eya-voice-${++sequence}"
        activeUtterance = id
        onStarted = onUtteranceStarted
        onFailed = onUtteranceFailed
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume.coerceIn(.15f,1f))
        }
        val accepted = try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            lastError = "Could not queue the TTS announcement"
            Log.e(logTag, "TextToSpeech.speak() rejected the utterance")
            stop()
            return false
        }
        return true
    }

    fun stop() = stopCurrent(notifyFailure = true)

    private fun stopCurrent(notifyFailure: Boolean) {
        tts?.stop()
        activeUtterance = null
        onStarted = null
        val fail = onFailed
        onFailed = null
        // A replacement maneuver may already be pending: its policy state
        // must not be cleared by the PREVIOUS utterance's callback.
        if (notifyFailure) fail?.invoke()
        releaseFocus()
    }

    // The engine can accept speak() and fail later. Surface that failure.
    private fun synthesisError(id: String, reason: String) {
        main.post {
            if (!closed && activeUtterance == id) {
                lastError = reason
                Log.e(logTag, "Utterance synthesis failed id=$id: $reason")
                activeUtterance = null
                onStarted = null
                val fail = onFailed
                onFailed = null
                fail?.invoke()
                releaseFocus()
            }
        }
    }

    private fun finish(id: String) {
        main.post {
            if (id == activeUtterance) {
                Log.d(logTag, "Utterance finished")
                TtsDebugLogger.event("utterance finished, audio focus released")
                activeUtterance = null
                onStarted = null
                val fail = onFailed
                onFailed = null
                fail?.invoke()
                releaseFocus()
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
        closed = true
        stop()
        tts?.shutdown()
        tts = null
        ducking.close()
    }
}
````

## 4. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/WelcomeGreetingManager.kt`

````kotlin
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One brief Turkish greeting after a real user-initiated OBD connection or
 * Android Auto Session creation. Android TTS / AudioFocus only: no sound assets.
 *
 * The singleton is shared across Activity and CarAppService within one process.
 * It is NOT a vehicle ignition signal; no greeting on ordinary Activity launch.
 */
@Singleton
class WelcomeGreetingManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: UserSettingsRepository,
    private val audioDucking: AppAudioDucking
) {
    private val appContext = context.applicationContext
    private val logTag = "EYA-Welcome"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var welcomeSettings = UserSettings()
    private val sessionGate = WelcomeSessionGate()
    private val preferences = VoicePreference(appContext)
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) main.post { closeSession() }
    }
    private val focusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()

    private var engine: TextToSpeech? = null
    private var initializationPending = false
    private var focusGranted = false
    private var active = false
    private val initTimeout = Runnable {
        if (initializationPending) {
            Log.w(logTag, "Turkish TTS initialization timed out")
            closeSession()
        }
    }
    private val speechTimeout = Runnable {
        Log.w(logTag, "Welcome synthesis timed out")
        closeSession()
    }

    /**
     * Thread-safe and idempotent. Call only after the OBD phase is CONNECTED
     * or from a real CarAppService.onCreateSession callback.
     */
    fun launchWelcomeGreeting() {
        if (!sessionGate.claim()) return
        scope.launch {
            // DataStore is asynchronous: read the actual persisted user setting.
            val current = runCatching { settings.settings.first() }.getOrNull() ?: return@launch
            if (!current.isWelcomeGreetingEnabled) return@launch
            welcomeSettings = current
            startSession()
        }
    }

    private fun startSession() {
        if (active) return
        active = true
        initializationPending = true
        main.postDelayed(initTimeout, 8_000L)

        // This engine is separate from en-US navigation TTS and is short-lived.
        try {
            engine = TextToSpeech(appContext) { result ->
                main.post {
                    if (!active || !initializationPending) return@post
                    initializationPending = false
                    main.removeCallbacks(initTimeout)
                    val localEngine = engine
                    if (result != TextToSpeech.SUCCESS || localEngine == null ||
                        !NativeVoiceGuide.selectTurkishGreetingVoice(localEngine, welcomeSettings.ttsVoiceName)
                    ) {
                        Log.w(logTag, "Turkish text-to-speech voice unavailable")
                        closeSession()
                        return@post
                    }
                    localEngine.setAudioAttributes(audioAttributes)
                    localEngine.setSpeechRate(welcomeSettings.ttsSpeechRate)
                    localEngine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                if (utteranceId == UTTERANCE_ID) main.post {
                                    if (active) {
                                        audioDucking.begin("welcome")
                                        Log.d(logTag, "Welcome utterance started")
                                    }
                                }
                            }

                            override fun onDone(utteranceId: String) =
                                finishIfWelcome(utteranceId)

                            override fun onError(utteranceId: String) =
                                finishIfWelcome(utteranceId)

                            override fun onError(utteranceId: String, errorCode: Int) =
                                finishIfWelcome(utteranceId)

                            override fun onStop(
                                utteranceId: String, interrupted: Boolean
                            ) = finishIfWelcome(utteranceId)
                        }
                    )
                    // Let the voice service finish preparation / audio routing.
                    main.postDelayed({
                        if (active) speakGreeting()
                    }, 1_000L)
                }
            }
        } catch (error: RuntimeException) {
            Log.w(logTag, "Could not create TTS engine", error)
            closeSession()
        }
    }

    private fun speakGreeting() {
        val tts = engine ?: return closeSession()
        val granted = runCatching {
            audio.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            Log.w(logTag, "Welcome audio focus denied")
            closeSession()
            return
        }
        focusGranted = true

        val params = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                preferences.volume.coerceIn(0.15f, 1.0f)
            )
        }
        // Exactly one TTS request, with its required stable utterance ID.
        val queued = runCatching {
            tts.speak(
                "Merhaba yol arkadaşım. İyi yolculuklar.",
                TextToSpeech.QUEUE_FLUSH,
                params,
                UTTERANCE_ID
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (queued) {
            main.postDelayed(speechTimeout, 15_000L)
        } else {
            Log.w(logTag, "TTS did not accept the greeting")
            closeSession()
        }
    }

    private fun finishIfWelcome(id: String) {
        if (id == UTTERANCE_ID) main.post { closeSession() }
    }

    private fun closeSession() {
        if (!active) return
        active = false
        audioDucking.end("welcome")
        initializationPending = false
        main.removeCallbacks(initTimeout)
        main.removeCallbacks(speechTimeout)

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
        private const val UTTERANCE_ID = "welcome_greeting"
    }
}
````

## 5. `nativecockpit/src/main/java/com/evcockpit/presentation/sound/EngineMotionPolicy.kt`

````kotlin
package com.evcockpit.presentation.sound

/** Pure, testable synthetic-audio control; input acceleration is km/h per s. */
internal object EngineMotionPolicy {
    enum class Mode { SILENT, IDLE, ACCELERATING }
    data class Decision(val mode: Mode, val pitch: Float)

    fun decide(
        speedKmh: Double?,
        previousKmh: Double?,
        deltaSeconds: Double
    ): Decision {
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0.0) {
            return Decision(Mode.SILENT, 1.0f)
        }
        if (speedKmh < 5.0) return Decision(Mode.IDLE, 0.95f)

        val acceleration = if (
            previousKmh != null && previousKmh.isFinite() &&
            deltaSeconds in 0.1..20.0
        ) (speedKmh - previousKmh) / deltaSeconds else 0.0

        val roaring = acceleration > 10.0
        val pitch = if (roaring) 1.9f
            else (0.85 + (speedKmh.coerceIn(0.0, 130.0) / 130.0 * 0.95)).toFloat()
        val mode = if (acceleration > 0.4) Mode.ACCELERATING else Mode.IDLE
        return Decision(mode, pitch)
    }
}
````

## 6. `nativecockpit/src/main/java/com/evcockpit/presentation/sound/ActiveSoundManager.kt`

````kotlin
package com.evcockpit.presentation.sound

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.evcockpit.R
import com.evcockpit.data.settings.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class EngineProfile(val label: String, val idle: Int, val accel: Int) {
    V8("V8", R.raw.v8_idle, R.raw.v8_accel),
    F1("F1", R.raw.f1_idle, R.raw.f1_accel),
    Futuristic("Futuristic", R.raw.futuristic_idle, R.raw.futuristic_accel),
    Turbine("Turbine", R.raw.turbine_idle, R.raw.turbine_accel),
    Stealth("Stealth", R.raw.stealth_idle, R.raw.stealth_accel);

    companion object {
        fun from(value: String): EngineProfile = entries.firstOrNull {
            it.name == value
        } ?: V8
    }
}

/**
 * In-cabin only synthetic audio. Never replace the vehicle's external AVAS.
 * Mutate ExoPlayer from the main thread; no music library or real engine sample.
 * A real fresh OBD speed is required (no speed => automatic silence).
 */
@Singleton
class ActiveSoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val main = Handler(Looper.getMainLooper())
    private var idle: ExoPlayer? = null
    private var acceleration: ExoPlayer? = null
    private var currentProfile: EngineProfile? = null
    private var lastSpeedKmh: Double? = null
    private var lastSpeedAtMs: Long? = null
    private var enabled = false
    private var volume = 1f
    private var ducked = false
    private var previewing = false
    private var previewToken = 0L
    private var activeInForeground = false
    private var lastSpeedInput: Double? = null
    private var lastInputTime: Long? = null

    private fun player(resourceId: Int): ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            // One ambient mix; do not make two Media3 players fight over focus.
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false
            )
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/$resourceId")
            ))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }

    private fun selectProfile(profile: EngineProfile) {
        if (profile == currentProfile) return
        idle?.release()
        acceleration?.release()
        idle = player(profile.idle)
        acceleration = player(profile.accel)
        currentProfile = profile
    }

    /** Call at most once per real OBD sample; time is the sample timestamp. */
    fun update(
        settings: UserSettings,
        speedKmh: Double?,
        speedAtMs: Long?,
        foreground: Boolean
    ) {
        main.post {
            activeInForeground = foreground
            enabled = settings.isEngineSoundEnabled
            volume = settings.engineVolume.coerceIn(0f, 1.5f)
            lastSpeedInput = speedKmh
            lastInputTime = speedAtMs
            if (!foreground || !enabled || previewing ||
                speedKmh == null || speedAtMs == null) {
                if (!previewing) pausePlayers()
                return@post
            }
            selectProfile(EngineProfile.from(settings.engineSoundProfile))
            if (lastSpeedAtMs == speedAtMs) {
                // Poll tick without a new CAN measurement: do not reset accel mode.
                applyVolumes()
                return@post
            }
            val deltaSeconds =
                lastSpeedAtMs?.let { (speedAtMs - it).toDouble() / 1000.0 } ?: 0.0
            val decision = EngineMotionPolicy.decide(
                speedKmh, lastSpeedKmh, deltaSeconds
            )
            lastSpeedKmh = speedKmh
            lastSpeedAtMs = speedAtMs
            acceleration?.playbackParameters = PlaybackParameters(1f, decision.pitch)
            idle?.playbackParameters = PlaybackParameters(1f, 0.95f)
            when (decision.mode) {
                EngineMotionPolicy.Mode.ACCELERATING -> {
                    idle?.pause()
                    acceleration?.play()
                }
                EngineMotionPolicy.Mode.IDLE -> {
                    acceleration?.pause()
                    idle?.play()
                }
                EngineMotionPolicy.Mode.SILENT -> pausePlayers()
            }
            applyVolumes()
        }
    }

    fun setSpeechDucking(active: Boolean) {
        main.post {
            ducked = active
            applyVolumes()
        }
    }

    private fun applyVolumes() {
        // Media3 player's volume is 0..1. UI 150% means max permitted app gain,
        // NOT boosting above device/system maximum (which risks clipping).
        val normalized = (volume / 1.5f).coerceIn(0f, 1f)
        val multiplier = if (ducked) 0.2f else 1f
        idle?.volume = normalized * multiplier * 0.75f
        acceleration?.volume = normalized * multiplier
    }

    fun preview(settings: UserSettings) {
        main.post {
            if (!activeInForeground) return@post
            previewToken++
            val token = previewToken
            previewing = true
            enabled = settings.isEngineSoundEnabled
            volume = settings.engineVolume.coerceIn(0f, 1.5f)
            selectProfile(EngineProfile.from(settings.engineSoundProfile))
            idle?.pause()
            acceleration?.playbackParameters = PlaybackParameters(1f, 1.25f)
            acceleration?.play()
            applyVolumes()
            main.postDelayed({
                if (previewToken == token) {
                    previewing = false
                    pausePlayers()
                    lastSpeedAtMs = null
                    // Vehicle polling will resume sound on its next fresh update.
                }
            }, 3_000L)
        }
    }

    private fun pausePlayers() {
        idle?.pause()
        acceleration?.pause()
        lastSpeedAtMs = null
        lastSpeedKmh = null
    }

    fun onResume() { main.post { activeInForeground = true } }

    fun onPause() {
        main.post {
            activeInForeground = false
            previewing = false
            previewToken++
            pausePlayers()
        }
    }

    /** Use only when application-owned audio lifetime ends. */
    fun release() {
        main.post {
            pausePlayers()
            idle?.release()
            acceleration?.release()
            idle = null
            acceleration = null
            currentProfile = null
        }
    }
}
````

## 7. `nativecockpit/src/main/java/com/evcockpit/presentation/settings/SettingsViewModel.kt`

````kotlin
package com.evcockpit.presentation.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evcockpit.data.settings.UserSettings
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.presentation.voice.NativeVoiceGuide
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class VoiceOption(val name: String, val label: String)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: UserSettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val settings = repository.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings()
    )
    private val voiceOptionsState = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voiceOptions = voiceOptionsState.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var catalogEngine: TextToSpeech? = null
    private var voiceRequested = false
    private var disposed = false

    /** Android TTS Voice does not contain a reliable biological gender field. */
    fun loadInstalledTurkishVoices() {
        if (voiceRequested) return
        voiceRequested = true
        catalogEngine = TextToSpeech(context.applicationContext) { status ->
            main.postDelayed({
                val tts = catalogEngine ?: return@postDelayed
                if (!disposed && status == TextToSpeech.SUCCESS) {
                    voiceOptionsState.value = NativeVoiceGuide
                        .availableTurkishVoices(tts).map { voice ->
                            val n = voice.name.lowercase(Locale.ROOT)
                            val label = when {
                                listOf("female", "woman", "kadin", "kadın").any(n::contains) ->
                                    "Kadın etiketi • " + voice.name
                                n.contains("male") || n.contains("man") ->
                                    "Erkek etiketi • " + voice.name
                                else -> "Türkçe ses • " + voice.name
                            }
                            VoiceOption(voice.name, label)
                        }
                }
                tts.shutdown()
                catalogEngine = null
            }, 180L)
        }
    }

    fun setWelcome(value: Boolean) {
        viewModelScope.launch {
        repository.setWelcomeGreetingEnabled(value)
        }
    }
    fun setVoice(value: String?) {
        viewModelScope.launch {
        repository.setTtsVoiceName(value)
        }
    }
    fun setRate(value: Float) {
        viewModelScope.launch {
        repository.setTtsSpeechRate(value)
        }
    }
    fun setNight(value: Boolean) {
        viewModelScope.launch {
        repository.setNightModeEnabled(value)
        }
    }
    fun setEngineEnabled(value: Boolean) {
        viewModelScope.launch {
        repository.setEngineSoundEnabled(value)
        }
    }
    fun setEngineProfile(value: String) {
        viewModelScope.launch {
        repository.setEngineSoundProfile(value)
        }
    }
    fun setEngineVolume(value: Float) {
        viewModelScope.launch {
        repository.setEngineVolume(value)
        }
    }

    override fun onCleared() {
        disposed = true
        catalogEngine?.shutdown()
        catalogEngine = null
        super.onCleared()
    }
}
````

## 8. `nativecockpit/src/main/java/com/evcockpit/presentation/settings/SettingsScreen.kt`

````kotlin
package com.evcockpit.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.presentation.sound.ActiveSoundManager
import java.util.Locale

/** Separate Material3 panel; the original Tesla-style CockpitScreen is untouched. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    activeSound: ActiveSoundManager,
    onClose: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voices by viewModel.voiceOptions.collectAsStateWithLifecycle()
    var voicesExpanded by remember { mutableStateOf(false) }
    var profilesExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadInstalledTurkishVoices() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xF20A1522),
            contentColor = Color.White,
            shadowElevation = 16.dp
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text("Ses ve Görünüm Ayarları", style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()

                Text("Sesli Asistan", style = MaterialTheme.typography.titleMedium)
                SettingToggle(
                    "Araca binerken karşıla", settings.isWelcomeGreetingEnabled,
                    viewModel::setWelcome
                )

                Text("Türkçe karşılama sesi", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { voicesExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val current = voices.firstOrNull { it.name == settings.ttsVoiceName }
                    Text(current?.label ?: "Sistem varsayılan Türkçe sesi")
                }
                DropdownMenu(
                    expanded = voicesExpanded,
                    onDismissRequest = { voicesExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sistem Türkçe sesi") },
                        onClick = {
                            viewModel.setVoice(null)
                            voicesExpanded = false
                        }
                    )
                    voices.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.label) },
                            onClick = {
                                viewModel.setVoice(v.name)
                                voicesExpanded = false
                            }
                        )
                    }
                }
                Text(
                    "Android ses motorları cinsiyet garantisi vermez. Kadın/Erkek etiketi yalnız " +
                        "ses dosyasının adında mevcutsa gösterilir. Bu seçim Türkçe karşılamaya uygulanır.",
                    color = Color(0xFFAEC4D6),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Konuşma hızı: " + String.format(Locale.US, "%.2fx", settings.ttsSpeechRate),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.ttsSpeechRate,
                    onValueChange = viewModel::setRate,
                    valueRange = .7f..1.4f,
                    steps = 6
                )

                HorizontalDivider()
                Text("Active Sound · İç mekân", style = MaterialTheme.typography.titleMedium)
                SettingToggle(
                    "Yapay motor sesi", settings.isEngineSoundEnabled,
                    viewModel::setEngineEnabled
                )

                Text("Motor sesi profili", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { profilesExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(settings.engineSoundProfile) }
                DropdownMenu(
                    expanded = profilesExpanded,
                    onDismissRequest = { profilesExpanded = false }
                ) {
                    UserSettingsRepository.ENGINE_PROFILES.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile) },
                            onClick = {
                                viewModel.setEngineProfile(profile)
                                profilesExpanded = false
                            }
                        )
                    }
                }
                Text("Motor sesi seviyesi: " +
                    (settings.engineVolume / 1.5f * 150).toInt() + "%",
                    style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.engineVolume,
                    onValueChange = viewModel::setEngineVolume,
                    valueRange = 0f..1.5f,
                    steps = 14
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { activeSound.preview(settings) }
                ) { Text("Sesi Dinle · 3 saniye") }
                Text(
                    "%150 arayüz ayarı Media3 içinde güvenli 0–1 aralığına ölçeklenir. " +
                        "Bu özellik aracın dış AVAS sisteminin yerine geçmez; " +
                        "OBD hızı yoksa motor sesi otomatik durur.",
                    color = Color(0xFFAEC4D6),
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()
                SettingToggle(
                    "Daha karanlık gece teması",
                    settings.isNightModeEnabled,
                    viewModel::setNight
                )
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Kapat") }
            }
        }
    }
}

@Composable
private fun SettingToggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
````

## 9. `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt`

````kotlin
package com.evcockpit.presentation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.evcockpit.R
import com.evcockpit.presentation.voice.LocalAppAudioDucking

@Composable
fun RadioScreen(model: RadioViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audioDucking = LocalAppAudioDucking.current
    val voiceDucked = if (audioDucking != null)
        audioDucking.ducked.collectAsStateWithLifecycle().value else false
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    LaunchedEffect(player, voiceDucked) {
        // TTS navigation/welcome reduces only our radio player to 20%.
        player.volume = if (voiceDucked) 0.2f else 1f
    }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredStations = remember(state.stations, query) {
        val term = query.trim()
        if (term.isEmpty()) state.stations
        else state.stations.filter {
            it.name.contains(term, ignoreCase = true) ||
                (it.tags?.contains(term, ignoreCase = true) == true)
        }
    }
    var currentName by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
                buffering = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.radio_description), style = MaterialTheme.typography.bodySmall)
        currentName?.let { name ->
            Text(stringResource(R.string.radio_selected, name))
            if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playbackFailed) Text(stringResource(R.string.radio_playback_error))
            Row {
                TextButton(onClick = {
                    if (!playbackFailed && (playing || player.playWhenReady)) player.pause()
                    else { if (player.playerError != null) player.prepare(); playbackFailed = false; player.play() }
                }) {
                    Text(stringResource(if (playing || buffering) R.string.radio_pause else R.string.radio_play))
                }
                TextButton(onClick = { player.stop(); currentName = null; playbackFailed = false }) {
                    Text(stringResource(R.string.radio_stop))
                }
            }
        }
        when {
            state.loading -> CircularProgressIndicator()
            state.failed -> Text(stringResource(R.string.radio_load_error))
            state.stations.isEmpty() -> Text(stringResource(R.string.radio_empty))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            label = { Text(stringResource(R.string.radio_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.radio_results, filteredStations.size, state.stations.size),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::refresh, enabled = !state.loading) {
                Text(stringResource(R.string.radio_refresh))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
            items(filteredStations, key = { it.id }) { station ->
                Column(Modifier.fillMaxWidth().clickable {
                    currentName = station.name
                    playbackFailed = false
                    player.setMediaItem(MediaItem.fromUri(station.urlResolved))
                    player.prepare()
                    player.play()
                }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                    Text(station.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.radio_votes, station.votes),
                        style = MaterialTheme.typography.bodySmall)
                    if (!station.tags.isNullOrBlank()) Text(station.tags,
                        maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        Text(stringResource(R.string.radio_attribution), style = MaterialTheme.typography.labelSmall)
    }
}
````

## 10. `nativecockpit/src/main/java/com/evcockpit/presentation/cockpit/CockpitTheme.kt`

````kotlin
package com.evcockpit.presentation.cockpit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF081320)
internal val Navy = Color(0xFF101F31)
internal val NeonGreen = Color(0xFF39FF88)
internal val SoftText = Color(0xFFB6C6D2)
internal val GlassLine = Color(0x3339FF88)

private val Palette = darkColorScheme(
    primary = NeonGreen,
    secondary = NeonGreen,
    tertiary = NeonGreen,
    background = Ink,
    surface = Navy,
    onPrimary = Ink,
    onSecondary = Ink,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = SoftText,
    outline = GlassLine,
    error = SoftText,
    errorContainer = Navy,
    onError = Ink,
    onErrorContainer = Color.White
)

private val CockpitTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 96.sp, lineHeight = 104.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 80.sp, lineHeight = 88.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 72.sp, lineHeight = 80.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 36.sp, lineHeight = 42.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 32.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)
)

private val CockpitShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun CockpitTheme(
    nightMode: Boolean = false,
    content: @Composable () -> Unit
) {
    // Only change semantic theme surfaces; existing CockpitScreen layout stays intact.
    val colors = if (nightMode) Palette.copy(
        background = Color(0xFF020509),
        surface = Color(0xFF08111C)
    ) else Palette
    MaterialTheme(
        colorScheme = colors,
        typography = CockpitTypography,
        shapes = CockpitShapes,
        content = content
    )
}
````

## 11. `nativecockpit/src/main/java/com/evcockpit/MainActivity.kt`

````kotlin
package com.evcockpit

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.data.settings.UserSettings
import com.evcockpit.data.settings.UserSettingsRepository
import com.evcockpit.presentation.settings.SettingsScreen
import com.evcockpit.presentation.settings.SettingsViewModel
import com.evcockpit.presentation.sound.ActiveSoundManager
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.LocalAppAudioDucking
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.presentation.navigation.NavigationViewModel
import com.evcockpit.presentation.charging.ChargingViewModel
import com.evcockpit.presentation.charging.RouteChargePlanViewModel
import com.evcockpit.presentation.weather.RouteWeatherViewModel
import com.evcockpit.presentation.cockpit.CockpitScreen
import com.evcockpit.presentation.cockpit.CockpitTheme
import com.evcockpit.presentation.media.enterOwnVideoPipWhenParked

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val navigation: NavigationViewModel by viewModels()
    private val charging: ChargingViewModel by viewModels()
    private val chargePlan: RouteChargePlanViewModel by viewModels()
    private val weather: RouteWeatherViewModel by viewModels()
    @Inject lateinit var obd: ObdConnectionController
    @Inject lateinit var vehicleRepository: VehicleRepository
    @Inject lateinit var settingsRepository: UserSettingsRepository
    @Inject lateinit var activeSound: ActiveSoundManager
    @Inject lateinit var audioDucking: AppAudioDucking

    private data class SoundFrame(
        val settings: UserSettings,
        val speed: Double?,
        val sampleAt: Long?,
        val ducked: Boolean
    )

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) obd.refreshPaired() else obd.permissionDenied()
    }

    private fun refreshObdDevices() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else obd.refreshPaired()
    }

    private fun connectObd(address: String) {
        try {
            obd.connect(address)
            if (obd.connection.value.phase != ObdPhase.CONNECTING &&
                obd.connection.value.phase != ObdPhase.CONNECTED) return
            // Explicit user action from a visible Activity; never auto-reconnect.
            ContextCompat.startForegroundService(
                this, Intent(this, ObdForegroundService::class.java)
            )
        } catch (_: Exception) {
            obd.disconnect() // Don't leave an unsupervised background socket.
            Toast.makeText(
                this, "OBD arka plan servisi başlatılamadı. Bağlantı kapatıldı.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun disconnectObd() {
        obd.disconnect()
        stopService(Intent(this, ObdForegroundService::class.java))
    }

    /**
     * For a future OWNED player Activity only; call after a trusted Park signal.
     * Third-party streaming packages own their own PiP and DRM surfaces.
     * Existing MapLibre and media app navigation are unaffected.
     */
    fun enterParkedOwnedVideoPip(
        verifiedParked: Boolean,
        ownVideoPlaying: Boolean
    ): Boolean = enterOwnVideoPipWhenParked(verifiedParked, ownVideoPlaying)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Observe real telemetry only while the phone UI is resumed.
        // Freshness check prevents stale/absent OBD readings producing sound.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val ticker = flow {
                    while (true) {
                        emit(System.currentTimeMillis())
                        delay(1_000L)
                    }
                }
                combine(
                    settingsRepository.settings,
                    vehicleRepository.telemetry,
                    obd.connection,
                    audioDucking.ducked,
                    ticker
                ) { settings, telemetry, connection, ducked, now ->
                    SoundFrame(
                        settings = settings,
                        speed = if (connection.phase == ObdPhase.CONNECTED)
                            telemetry.speedKmh.current(now, 10_000L)
                        else null,
                        sampleAt = telemetry.speedKmh.measuredAtMillis,
                        ducked = ducked
                    )
                }.collect { frame ->
                    activeSound.setSpeechDucking(frame.ducked)
                    activeSound.update(frame.settings, frame.speed, frame.sampleAt, true)
                }
            }
        }

        setContent {
            val context = LocalContext.current
            val fused = remember(context) {
                LocationServices.getFusedLocationProviderClient(context.applicationContext)
            }
            var pendingRouteRequest by remember { mutableStateOf(false) }
            val locationManager = remember(context) {
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            }
            var fusedToken by remember { mutableStateOf<CancellationTokenSource?>(null) }
            DisposableEffect(fused) {
                onDispose { fusedToken?.cancel() }
            }
            // Route selection already has its own validated Fused request in ViewModel.
            // Map refresh uses an independent one-shot request, not stale lastLocation.
            @Suppress("MissingPermission")
            fun granted(forRoute: Boolean) {
                if (!locationManager.isLocationEnabled) {
                    navigation.reportGpsError("Location services are off. Enable location in your phone settings.")
                    return
                }
                navigation.startGps()
                if (forRoute) {
                    navigation.useCurrentGps()
                    return
                }
                val fine = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val request = CurrentLocationRequest.Builder()
                    .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY
                                 else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(3_000L)
                    .setDurationMillis(15_000L)
                    .build()
                fusedToken?.cancel()
                val token = CancellationTokenSource()
                fusedToken = token
                try {
                    fused.getCurrentLocation(request, token.token)
                        .addOnSuccessListener { location ->
                            if (location == null) {
                                navigation.reportGpsError(
                                    "No current location found. Try again outdoors."
                                )
                            } else {
                                navigation.acceptLastLocation(location)
                            }
                        }
                        .addOnFailureListener { error ->
                            navigation.reportGpsError(
                                "Could not get location: " + (error.message ?: "GPS error")
                            )
                        }
                } catch (_: SecurityException) {
                    navigation.reportGpsError("Location permission is no longer valid. Grant it again.")
                }
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val accepted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                if (accepted) granted(pendingRouteRequest)
                else {
                    if (pendingRouteRequest) navigation.locationPermissionDenied()
                    else navigation.reportGpsError(
                        "Location permission was not granted. Enable it in Settings."
                    )
                }
                pendingRouteRequest = false
            }
            val requestGps: (Boolean) -> Unit = { fromRoute ->
                val allowed = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (allowed) granted(fromRoute)
                else {
                    pendingRouteRequest = fromRoute
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
            }
            val gpsError by navigation.gpsError.collectAsStateWithLifecycle()
            val ui by navigation.uiState.collectAsStateWithLifecycle()
            val stations by charging.uiState.collectAsStateWithLifecycle()
            val automaticChargePlan by chargePlan.uiState.collectAsStateWithLifecycle()
            val routeWeather by weather.uiState.collectAsStateWithLifecycle()
            val obdConnection by obd.connection.collectAsStateWithLifecycle()
            val engineering by obd.engineering.collectAsStateWithLifecycle()
            val engineeringMetrics by obd.engineeringMetrics.collectAsStateWithLifecycle()
            val soundSettings by settingsViewModel.settings.collectAsStateWithLifecycle()
            var audioSettingsOpen by rememberSaveable { mutableStateOf(false) }
            CompositionLocalProvider(LocalAppAudioDucking provides audioDucking) {
                CockpitTheme(nightMode = soundSettings.isNightModeEnabled) {
                    Box(Modifier.fillMaxSize()) {
                        CockpitScreen(
                    state = ui.cockpit,
                    routeState = ui.routeState,
                    loadStatus = ui.loadStatus,
                    routePlannerUi = ui.planner,
                    onRouteOriginChange = navigation::editOrigin,
                    onRouteDestinationChange = navigation::editDestination,
                    onRouteOriginSelected = navigation::selectOrigin,
                    onRouteDestinationSelected = navigation::selectDestination,
                    onRouteFromGps = { if (ui.simulationEnabled) navigation.useCurrentGps() else requestGps(true) },
                    onRoutePlan = navigation::plan,
                    onRouteAlternative = navigation::selectAlternative,
                    onRouteClear = navigation::clearRoute,
                    onGpsClick = { if (!ui.simulationEnabled) requestGps(false) },
                    gpsError = gpsError,
                    chargingUi = stations,
                    automaticChargePlan = automaticChargePlan,
                    weatherUi = routeWeather,
                    onStationFilter = charging::changeFilter,
                    onMapViewport = charging::viewportChanged,
                    onRefreshStations = charging::refresh,
                    obdConnection = obdConnection,
                    engineering = engineering,
                    engineeringMetrics = engineeringMetrics,
                    onObdRefresh = ::refreshObdDevices,
                    onObdConnect = ::connectObd,
                    onObdDisconnect = ::disconnectObd,
                    onObdMebDiagnostic = obd::requestMebDiagnostic,
                    onObdClearDiagnostic = obd::clearMebDiagnostic,
                    voiceEnabled = ui.voice.enabled,
                    onVoiceToggle = { navigation.setVoiceEnabled(!ui.voice.enabled) },
                    voiceVolume = ui.voice.volume,
                    onVoiceVolumeChange = navigation::setVoiceVolume,
                    voiceStatus = ui.voice.status,
                    voiceError = ui.voice.error,
                    onVoiceTest = navigation::testVoice,
                    onSimulationToggle = { enabled ->
                        // Never mix a genuine Bluetooth session with synthetic frames.
                        if (enabled) disconnectObd()
                        navigation.setSimulationEnabled(enabled)
                    }
                )
                        // Separate settings affordance: original CockpitScreen untouched.
                        IconButton(
                            modifier = Modifier.align(Alignment.TopEnd)
                                .padding(top = 42.dp, end = 8.dp),
                            onClick = { audioSettingsOpen = true }
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "Ses ayarları")
                        }
                        if (audioSettingsOpen) SettingsScreen(
                            viewModel = settingsViewModel,
                            activeSound = activeSound,
                            onClose = { audioSettingsOpen = false }
                        )
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        activeSound.onResume()
        navigation.setForeground(true)
        val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allowed) navigation.startGps()
    }
    override fun onPause() {
        activeSound.onPause()
        navigation.setForeground(false)
        // OBD is retained only while the user-started connectedDevice FGS runs.
        super.onPause()
    }
}
````

## 12. `nativecockpit/src/main/java/com/evcockpit/ObdForegroundService.kt`

````kotlin
package com.evcockpit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.presentation.voice.WelcomeGreetingManager

/**
 * User-initiated connected-device foreground service.
 *
 * Keeps the same singleton read-only ELM polling coroutine alive when the UI
 * is no longer visible. Does not reconnect without a new explicit user action.
 * Stop notification action closes the Bluetooth socket and clears live values.
 */
@AndroidEntryPoint
class ObdForegroundService : Service() {
    @Inject lateinit var obd: ObdConnectionController
    @Inject lateinit var welcome: WelcomeGreetingManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionMonitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "OBD canlı teknik ölçümler", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            obd.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eya_car)
            .setContentTitle("Elektrikli Yol Arkadaşım")
            .setContentText("OBD teknik ölçümleri ve hesaplamaları çalışıyor")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Bağlantıyı kes", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, notification)

        connectionMonitor?.cancel()
        connectionMonitor = scope.launch {
            var sessionStarted = false
            obd.connection.collect { state ->
                if (state.phase == ObdPhase.CONNECTING || state.phase == ObdPhase.CONNECTED)
                    sessionStarted = true
                // A genuine adapter connection, never a mere Activity launch.
                if (state.phase == ObdPhase.CONNECTED) welcome.launchWelcomeGreeting()
                if (sessionStarted &&
                    (state.phase == ObdPhase.ERROR || state.phase == ObdPhase.DISCONNECTED))
                    stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        connectionMonitor?.cancel()
        scope.cancel()
        obd.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.evcockpit.action.STOP_OBD"
        private const val CHANNEL = "eya_obd_engineering"
        private const val NOTIFICATION_ID = 2460
    }
}
````

## 13. `nativecockpit/src/main/java/com/evcockpit/car/EyaCarAppService.kt`

````kotlin
package com.evcockpit.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.evcockpit.BuildConfig
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.presentation.voice.WelcomeGreetingManager
import com.evcockpit.presentation.voice.AppAudioDucking

/** Android Auto Car App Library entry point. Does NOT start an Activity or play video. */
@AndroidEntryPoint
class EyaCarAppService : CarAppService() {
    @Inject lateinit var routes: RouteRepository
    @Inject lateinit var welcome: WelcomeGreetingManager
    @Inject lateinit var ducking: AppAudioDucking

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session {
        // Shared singleton gate prevents duplicates if OBD connects as well.
        welcome.launchWelcomeGreeting()
        return EyaCarSession(routes, ducking)
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        welcome.launchWelcomeGreeting()
        return EyaCarSession(routes, ducking)
    }
}

private class EyaCarSession(
    private val routes: RouteRepository,
    private val ducking: AppAudioDucking
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        EyaCarNavigationScreen(carContext, routes, ducking)
}
````

## 14. `nativecockpit/src/main/java/com/evcockpit/car/EyaCarNavigationScreen.kt`

````kotlin
package com.evcockpit.car

import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.RouteSnapshot
import com.evcockpit.domain.model.validRouteGeometry
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.usecase.ManeuverGuidancePolicy
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.AppAudioDucking
import com.evcockpit.presentation.voice.VoicePreference
import kotlin.math.roundToInt

/** Real OSRM geometry + measured GPS only. Foreground Android Auto session, not a navigation FGS. */
class EyaCarNavigationScreen(
    context: CarContext,
    private val routes: RouteRepository,
    ducking: AppAudioDucking
) : Screen(context) {
    private val tracker = CarGpsTracker(context.applicationContext)
    private val surface = EyaCarMapSurface(context)
    private val manager = context.getCarService(NavigationManager::class.java)
    private val voice = NativeVoiceGuide(context.applicationContext, ducking)
    private val voicePrefs = VoicePreference(context.applicationContext)
    private val speechPolicy = ManeuverGuidancePolicy()
    private val progress = RouteProgressTracker()
    private val guidancePolicy = CarGuidancePolicy()
    private val gate = CarTripUpdateGate()
    private var work: CoroutineScope? = null
    private var route: RouteSnapshot? = routes.currentRoute.value
    private var fix: GpsFix? = null
    private var guidance: CarGuidance? = null
    private var summary = CarRouteSummary.from(route, null, System.currentTimeMillis())
    private var activeRouteId: String? = null
    private var withoutGpsSinceMs: Long = 0L
    private var autoDriveTestRequested = false
    private var closed = false

    private val callbacks = object : NavigationManagerCallback {
        override fun onStopNavigation() {
            // Host navigation ownership changed: immediately stop all trip IPC and voice.
            endNavigation()
        }
        override fun onAutoDriveEnabled() {
            // Google Play requires a separate, conspicuously labelled test-only simulation.
            // This build never injects mock GPS into real driving or presents it as measured.
            autoDriveTestRequested = true
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                manager.setNavigationManagerCallback(callbacks)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
                tracker.start()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                work = scope
                val ticker = flow {
                    while (kotlin.coroutines.coroutineContext.isActive) {
                        emit(System.currentTimeMillis())
                        delay(1_000L)
                    }
                }
                scope.launch {
                    combine(routes.currentRoute, tracker.gpsFix, ticker) { r, f, now ->
                        Triple(r, f?.takeIf { it.usable(now) }, now)
                    }.collect { (r, f, now) ->
                        route = r
                        fix = f
                        surface.update(r, f)
                        val fresh = guidancePolicy.snapshot(r, f, now)
                        guidance = fresh
                        val newSummary = CarRouteSummary.from(r, f, now)
                        if (newSummary != summary) {
                            summary = newSummary
                            invalidate()
                        }
                        updateActiveNavigation(r, f, fresh, now)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                // No foreground service yet: stop rather than claim background navigation.
                endNavigation()
                work?.cancel(); work = null
                tracker.stop()
                surface.release()
                manager.clearNavigationManagerCallback()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                endNavigation()
                work?.cancel(); work = null
                tracker.stop()
                surface.release()
                if (!closed) { closed = true; voice.close() }
            }
        })
    }

    private fun beginNavigation() {
        if (activeRouteId != null) return
        val current = guidance ?: return // user must have a REAL route and a fresh GPS fix
        if (route?.routeId != current.routeId) return
        try {
            manager.navigationStarted()
            activeRouteId = current.routeId
            CarNavigationOwnership.setNavigating(true)
            withoutGpsSinceMs = 0L
            gate.clear()
            speechPolicy.reset()
            updateActiveNavigation(route, fix, current, System.currentTimeMillis())
            invalidate()
        } catch (e: RuntimeException) {
            Log.w("EyaCarNavigation", "Host refused navigation start", e)
            endNavigation()
        }
    }

    private fun endNavigation() {
        val wasActive = activeRouteId != null
        activeRouteId = null
        withoutGpsSinceMs = 0L
        CarNavigationOwnership.setNavigating(false)
        voice.stop()
        speechPolicy.reset()
        gate.clear()
        if (wasActive) {
            try { manager.navigationEnded() }
            catch (e: RuntimeException) { Log.w("EyaCarNavigation", "Host navigation end", e) }
            invalidate()
        }
    }

    private fun updateActiveNavigation(
        currentRoute: RouteSnapshot?, currentFix: GpsFix?,
        current: CarGuidance?, now: Long
    ) {
        val started = activeRouteId ?: return
        if (currentRoute?.routeId != started || !validRouteGeometry(currentRoute.routePoints)) {
            endNavigation() // new alternative means a new explicit trip
            return
        }
        if (current == null || current.routeId != started) {
            if (withoutGpsSinceMs == 0L) withoutGpsSinceMs = now
            voice.stop()
            if (now - withoutGpsSinceMs >= 15_000L) endNavigation()
            return // never publish stale maneuver or estimated distance
        }
        withoutGpsSinceMs = 0L
        if (gate.shouldPublish(current, now)) {
            try { manager.updateTrip(CarTripFactory.trip(current)) }
            catch (e: RuntimeException) { Log.w("EyaCarNavigation", "Trip update rejected", e) }
            invalidate()
        }
        if (voicePrefs.enabled && currentFix != null &&
            voice.status == NativeVoiceGuide.Status.READY) {
            val enriched = progress.enrich(currentRoute, currentFix, now)
            val announcement = speechPolicy.nextAnnouncement(enriched, currentFix, now)
            if (announcement != null) {
                // An enqueue is not proof that the speech engine started.
                val queued = voice.speak(
                    announcement, voicePrefs.volume,
                    onUtteranceStarted = {
                        // NativeVoiceGuide marshals this callback onto the main thread.
                        speechPolicy.confirmSpoken(System.currentTimeMillis())
                    },
                    onUtteranceFailed = { speechPolicy.cancelPending() }
                )
                if (!queued) speechPolicy.cancelPending()
            }
        } else if (!voicePrefs.enabled) voice.stop()
    }

    override fun onGetTemplate(): Template {
        val active = activeRouteId != null
        val g = guidance?.takeIf { it.routeId == activeRouteId }
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(summary.title).addText(summary.detail).build())
            .addRow(Row.Builder().setTitle("GPS").addText(summary.locationStatus).build())

        if (active) {
            val step = g?.nextCue
            val meters = g?.nextMeters
            pane.addRow(Row.Builder().setTitle("Next maneuver")
                .addText(if (step != null && meters != null) {
                    "$step · ${meters.roundToInt()} m"
                } else "Waiting for current GPS and maneuver data").build())
            pane.addRow(Row.Builder().setTitle("End navigation")
                .setOnClickListener { endNavigation() }.build())
        } else if (guidance != null) {
            pane.addRow(Row.Builder().setTitle("Start navigation")
                .setOnClickListener { beginNavigation() }.build())
        } else {
            pane.addRow(Row.Builder().setTitle("To get started")
                .addText("Select a route and grant location permission on the phone").build())
        }
        if (autoDriveTestRequested) {
            pane.addRow(Row.Builder().setTitle("DHU test request")
                .addText("Simulation is off: actual GPS validation is required").build())
        }
        val content = PaneTemplate.Builder(pane.build())
        if (carContext.carAppApiLevel < 7) {
            @Suppress("DEPRECATION")
            return content.setTitle("EV Cockpit").build()
        }
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content.setHeader(
                Header.Builder().setTitle("EV Cockpit").build()
            ).build())
            .build()
    }
}
````

## 15. `nativecockpit/build.gradle.kts`

````kotlin
import java.io.File
import java.io.DataOutputStream
import kotlin.math.sin
import kotlin.math.min
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Local release signing credentials live in ignored local.properties.
// GitHub Actions supplies the same values as environment variables.
val localSigning = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    providers.environmentVariable(environmentKey).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(propertyKey)?.trim()?.takeIf { it.isNotBlank() }

val storePath = signingValue("eya.release.storeFile", "EYA_RELEASE_STORE_FILE")
val storePasswordValue = signingValue("eya.release.storePassword", "EYA_RELEASE_STORE_PASSWORD")
val keyAliasValue = signingValue("eya.release.keyAlias", "EYA_RELEASE_KEY_ALIAS")
val keyPasswordValue = signingValue("eya.release.keyPassword", "EYA_RELEASE_KEY_PASSWORD")
val releaseStore = storePath?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val signedReleaseReady = releaseStore?.isFile == true &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

android {
    namespace = "com.evcockpit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evcockpit"
        minSdk = 28
        targetSdk = 35
        versionCode = 22
        versionName = "2.0.5"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // No debug-key fallback. This is populated only with real owner credentials.
        if (signedReleaseReady) {
            create("eyaProduction") {
                storeFile = releaseStore
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Remains the explicitly marked UAT/simulation variant.
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            // Diagnostic release: disable R8 code + resource shrinking until crash is reproduced.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signedReleaseReady) {
                signingConfig = signingConfigs.getByName("eyaProduction")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = false }
}

// Fail closed: a signing-less artifact must NEVER be called a production APK.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { task ->
        task.project.path == ":nativecockpit" &&
            task.name in setOf("assembleRelease", "packageRelease", "validateSigningRelease")
    }
    if (buildingRelease && !signedReleaseReady) {
        throw GradleException(
            "Production release signing is not configured. Set " +
                "eya.release.storeFile/storePassword/keyAlias/keyPassword " +
                "in ignored local.properties (or EYA_RELEASE_* environment variables)."
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("org.maplibre.gl:android-sdk-turf:6.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Generate ten original, copyright-free mono WAV loops during Gradle builds.
// GitHub's text-only mobile workflow does not require uploading binary assets.
val generatedSoundRes = layout.buildDirectory.dir("generated/active-sound/res")
android.sourceSets.getByName("main").res.srcDir(generatedSoundRes)

val generateOriginalEngineWavAssets = tasks.register("generateOriginalEngineWavAssets") {
    val output = generatedSoundRes
    outputs.dir(output)
    doLast {
        val raw = output.get().asFile.resolve("raw").apply { mkdirs() }
        val sampleRate = 22_050
        val count = sampleRate * 2
        val profiles = linkedMapOf(
            "v8" to 64.0, "f1" to 175.0, "futuristic" to 115.0,
            "turbine" to 93.0, "stealth" to 42.0
        )
        for ((profile, fundamental) in profiles) {
            for (accelerating in listOf(false, true)) {
                val mode = if (accelerating) "accel" else "idle"
                val file = raw.resolve("${profile}_${mode}.wav")
                DataOutputStream(file.outputStream().buffered()).use { out ->
                    fun word(n: Int) {
                        out.writeByte(n and 255)
                        out.writeByte((n ushr 8) and 255)
                    }
                    fun dword(n: Int) {
                        word(n and 0xffff)
                        word(n ushr 16)
                    }
                    out.writeBytes("RIFF")
                    dword(36 + count * 2)
                    out.writeBytes("WAVEfmt ")
                    dword(16)
                    word(1)  // signed 16-bit PCM, mono
                    word(1)
                    dword(sampleRate)
                    dword(sampleRate * 2)
                    word(2)
                    word(16)
                    out.writeBytes("data")
                    dword(count * 2)
                    for (i in 0 until count) {
                        val t = i.toDouble() / sampleRate
                        val f = fundamental * if (accelerating) 1.75 else 1.0
                        val pulse = 1.0 + 0.10 * sin(
                            2.0 * Math.PI * (if (profile == "v8") 8.0 else 5.0) * t
                        )
                        val signal = (
                            0.52 * sin(2 * Math.PI * f * t) +
                            0.24 * sin(2 * Math.PI * 2 * f * t) +
                            0.12 * sin(2 * Math.PI * 3 * f * t)
                        ) * pulse
                        // Short fades avoid audible clicks at repeat boundaries.
                        val edge = min(i, count - 1 - i)
                        val fade = (edge.toDouble() / 256).coerceIn(0.0, 1.0)
                        val pcm = (signal * fade * 0.23 * 32767)
                            .toInt().coerceIn(-32768, 32767)
                        word(pcm and 0xffff)
                    }
                }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generateOriginalEngineWavAssets) }
````

## 16. `nativecockpit/src/test/java/com/evcockpit/presentation/sound/EngineMotionPolicyTest.kt`

````kotlin
package com.evcockpit.presentation.sound

import org.junit.Assert.*
import org.junit.Test

class EngineMotionPolicyTest {
    @Test fun noRealSpeedMustBeSilent() {
        assertEquals(
            EngineMotionPolicy.Mode.SILENT,
            EngineMotionPolicy.decide(null, null, 0.0).mode
        )
        assertEquals(
            EngineMotionPolicy.Mode.IDLE,
            EngineMotionPolicy.decide(4.5, 3.0, 1.0).mode
        )
    }

    @Test fun accelerationChangesLayerAndPitch() {
        val accel = EngineMotionPolicy.decide(55.0, 45.0, 2.0)
        assertEquals(EngineMotionPolicy.Mode.ACCELERATING, accel.mode)
        assertTrue(accel.pitch > 0.9f)
        val roar = EngineMotionPolicy.decide(65.0, 42.0, 1.0)
        assertEquals(1.9f, roar.pitch, 0.0001f)
        assertEquals(EngineMotionPolicy.Mode.IDLE,
            EngineMotionPolicy.decide(60.0, 65.0, 2.0).mode)
    }

    @Test fun invalidTimeDoesNotProduceArtificialRoar() {
        val m = EngineMotionPolicy.decide(80.0, 20.0, 0.0)
        assertEquals(EngineMotionPolicy.Mode.IDLE, m.mode)
        assertTrue(m.pitch < 1.9f)
    }
}
````

## 17. `nativecockpit/src/test/java/com/evcockpit/presentation/voice/AppAudioDuckingTest.kt`

````kotlin
package com.evcockpit.presentation.voice

import org.junit.Assert.*
import org.junit.Test

class AppAudioDuckingTest {
    @Test fun concurrentWelcomeAndNavigationDoNotUnduckEachOther() {
        val manager = AppAudioDucking()
        assertFalse(manager.ducked.value)
        manager.begin("welcome")
        assertTrue(manager.ducked.value)
        manager.begin("navigation:42")
        manager.end("welcome")
        assertTrue(manager.ducked.value)
        manager.end("navigation:42")
        assertFalse(manager.ducked.value)
        manager.end("navigation:42")
        assertFalse(manager.ducked.value)
    }
}
````


# Türkçe yolculuk karşılama — tam Kotlin kaynakları

Altı dosyanın eksiksiz kaynak içeriği. Doğrudan GitHub'a işlendi; manuel kopyalamaya gerek yok.

- Başlangıç: **yalnızca OBD CONNECTED** veya **Android Auto onCreateSession**.
- Tekrar: **uygulama süreci boyunca bir deneme**. Kesin kontak açılışı algılanmaz.
- Türkçe ve etiketlenmiş kadın sesi: varsa tercih edilir; yoksa mevcut Türkçe motor sesi.
- AudioFocus: **AUDIOFOCUS_GAIN_TRANSIENT**; anons sonunda serbest bırakılır.
- Navigasyon TTS'sinin İngilizce sesi değiştirilmedi.

## 1. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/NativeVoiceGuide.kt`

````kotlin
package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Activity-scoped TTS: foregound-only, focus-safe, no stream-volume changes.
 * Media ducking is requested from Android, not implemented by changing
 * another app's volume. An unavailable en-US voice fails closed.
 */
class NativeVoiceGuide(context: Context) {
    private val logTag = "EYA-Voice"

    companion object {
        private val GREETING_LOCALE = Locale.forLanguageTag("tr-TR")

        /**
         * Gender is not an Android Voice attribute. Prefer only voice names
         * explicitly labelled female; otherwise keep the system Turkish default.
         * Never infer a voice's gender from quality, language or engine vendor.
         * This function is for a separate welcome TTS engine, not navigation.
         */
        internal fun selectTurkishGreetingVoice(engine: TextToSpeech): Boolean {
            val result = engine.setLanguage(GREETING_LOCALE)
            if (result < TextToSpeech.LANG_AVAILABLE) return false

            val candidates = runCatching { engine.voices.orEmpty() }
                .getOrDefault(emptySet())
                .filter { voice ->
                    voice.locale.language.equals("tr", ignoreCase = true) &&
                        voice.locale.country.equals("TR", ignoreCase = true) &&
                        !voice.isNetworkConnectionRequired
                }
            val female = candidates
                .filter { voice ->
                    val id = voice.name.lowercase(Locale.ROOT)
                    listOf("female", "woman", "kadın", "kadin")
                        .any(id::contains)
                }
                .maxByOrNull { it.quality }
            if (female != null) {
                val selected = runCatching {
                    engine.setVoice(female) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
                if (selected) return true
            }
            // The default installed tr-TR voice may or may not sound feminine.
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

## 2. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/WelcomeSessionGate.kt`

````kotlin
package com.evcockpit.presentation.voice

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One greeting attempt per application process. Shared by the OBD service
 * and the Android Auto host; a reconnect does not trigger a second greeting.
 *
 * It is not a physical ignition-state detector.
 */
internal class WelcomeSessionGate {
    private val consumed = AtomicBoolean(false)
    fun claim(): Boolean = consumed.compareAndSet(false, true)
}
````

## 3. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/WelcomeGreetingManager.kt`

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
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val logTag = "EYA-Welcome"
    private val main = Handler(Looper.getMainLooper())
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
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
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
        main.post {
            if (!preferences.enabled) {
                Log.i(logTag, "Welcome skipped: voice preference disabled")
                return@post
            }
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
                        !NativeVoiceGuide.selectTurkishGreetingVoice(localEngine)
                    ) {
                        Log.w(logTag, "Turkish text-to-speech voice unavailable")
                        closeSession()
                        return@post
                    }
                    localEngine.setAudioAttributes(audioAttributes)
                    localEngine.setSpeechRate(0.94f)
                    localEngine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                if (utteranceId == UTTERANCE_ID)
                                    Log.d(logTag, "Welcome utterance started")
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

## 4. `nativecockpit/src/main/java/com/evcockpit/ObdForegroundService.kt`

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

## 5. `nativecockpit/src/main/java/com/evcockpit/car/EyaCarAppService.kt`

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

/** Android Auto Car App Library entry point. Does NOT start an Activity or play video. */
@AndroidEntryPoint
class EyaCarAppService : CarAppService() {
    @Inject lateinit var routes: RouteRepository
    @Inject lateinit var welcome: WelcomeGreetingManager

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session {
        // Shared singleton gate prevents duplicates if OBD connects as well.
        welcome.launchWelcomeGreeting()
        return EyaCarSession(routes)
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        welcome.launchWelcomeGreeting()
        return EyaCarSession(routes)
    }
}

private class EyaCarSession(private val routes: RouteRepository) : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        EyaCarNavigationScreen(carContext, routes)
}
````

## 6. `nativecockpit/src/test/java/com/evcockpit/presentation/voice/WelcomeSessionGateTest.kt`

````kotlin
package com.evcockpit.presentation.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Android TTS sound output must additionally be checked on a real phone. */
class WelcomeSessionGateTest {
    @Test fun obuThenAutoOnlyGreetsOnceUntilProcessEnds() {
        val gate = WelcomeSessionGate()
        assertTrue(gate.claim()) // OBD connected
        assertFalse(gate.claim()) // Android Auto session
        assertFalse(gate.claim()) // adapter reconnected
    }

    @Test fun racingCarAndObdCallbacksClaimExactlyOnce() {
        val gate = WelcomeSessionGate()
        val pool = Executors.newFixedThreadPool(12)
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        try {
            repeat(12) {
                pool.execute {
                    ready.countDown()
                    start.await()
                    if (gate.claim()) successes.incrementAndGet()
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(1, successes.get())
    }
}
````


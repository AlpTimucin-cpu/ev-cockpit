package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * One audio focus owner for short navigation speech.
 * Android ducks cooperative media apps; no stream volume or DRM manipulation.
 */
class AudioDuckingManager(
    context: Context,
    private val onFocusLost: () -> Unit
) : AutoCloseable {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // A broken/disabled audio service must not crash the navigation screen.
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val listener: AudioManager.OnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            val capturedGeneration = lease.lossGeneration()
            if (capturedGeneration != null) main.post {
                // A prior utterance's queued loss cannot stop the next one.
                if (lease.shouldStopAfterLoss(capturedGeneration)) onFocusLost()
            }
        }
    }
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener, main)
        .build()

    private val lease: AudioFocusLease = AudioFocusLease(
        requestFocus = {
            manager?.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        },
        abandonFocus = { manager?.abandonAudioFocusRequest(focusRequest); Unit }
    )

    fun acquire(): Boolean = lease.acquire()
    fun release() = lease.release()
    override fun close() = lease.close()
}

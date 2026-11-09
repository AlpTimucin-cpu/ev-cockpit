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

    /** Navigation speech has priority over an optional startup greeting. */
    @Synchronized fun hasNavigationSpeech(): Boolean =
        activeSpeakers.any { it.startsWith("navigation:") }
}

/** Shared by the phone radio without adding DI to a Composable. */
val LocalAppAudioDucking = staticCompositionLocalOf<AppAudioDucking?> { null }

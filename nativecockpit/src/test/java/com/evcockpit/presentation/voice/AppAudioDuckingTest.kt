package com.evcockpit.presentation.voice

import org.junit.Assert.*
import org.junit.Test

class AppAudioDuckingTest {
    @Test fun optionalWelcomeCanSeeWhenNavigationOwnsSpeech() {
        val manager = AppAudioDucking()
        assertFalse(manager.hasNavigationSpeech())
        manager.begin("welcome")
        assertFalse(manager.hasNavigationSpeech())
        manager.begin("navigation:1")
        assertTrue(manager.hasNavigationSpeech())
        manager.end("welcome")
        assertTrue(manager.hasNavigationSpeech())
        manager.end("navigation:1")
        assertFalse(manager.hasNavigationSpeech())
    }

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

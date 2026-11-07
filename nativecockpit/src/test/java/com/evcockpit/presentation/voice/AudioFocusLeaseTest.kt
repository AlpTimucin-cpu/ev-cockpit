package com.evcockpit.presentation.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM policy simulation only: Android's real AudioManager and Netflix/MediaEngine
 * response require device tests, not assertions about third-party apps.
 */
class AudioFocusLeaseTest {
    @Test fun cooperativeMediaDucksDuringNavigationAndRestoresAfterSpeech() {
        var mediaVolume = 1.0
        var requests = 0
        var releases = 0
        val focus = AudioFocusLease(
            requestFocus = {
                requests++
                mediaVolume = 0.25 // fake media player's onAudioFocusChange
                true
            },
            abandonFocus = {
                releases++
                mediaVolume = 1.0
            }
        )
        assertTrue(focus.acquire())
        assertTrue(focus.shouldStopAfterLoss())
        assertEquals(0.25, mediaVolume, 0.0)
        assertTrue(focus.acquire()) // repeated requests do not stack
        assertEquals(1, requests)
        focus.release()
        assertEquals(1, releases)
        assertEquals(1.0, mediaVolume, 0.0)
        assertFalse(focus.shouldStopAfterLoss())
        focus.close()
        assertEquals(1, releases)
    }

    @Test fun focusLostCallbackQueuedBeforeCloseIsIgnored() {
        var abandoned = 0
        val focus = AudioFocusLease(
            requestFocus = { true },
            abandonFocus = { abandoned++ }
        )
        assertTrue(focus.acquire())
        focus.close() // Activity / ViewModel destroyed
        assertFalse(focus.shouldStopAfterLoss())
        assertFalse(focus.acquire())
        focus.close()
        assertEquals(1, abandoned)
    }

    @Test fun staleQueuedLossFromOldLeaseCannotStopNewTtsUtterance() {
        var grants = 0
        val focus = AudioFocusLease(
            requestFocus = { grants++; true },
            abandonFocus = { }
        )
        assertTrue(focus.acquire())
        val oldEvent = focus.lossGeneration()!!
        assertTrue(focus.shouldStopAfterLoss(oldEvent))
        focus.release()
        assertTrue(focus.acquire())
        val currentEvent = focus.lossGeneration()!!
        assertEquals(2, grants)
        assertTrue(currentEvent > oldEvent)
        assertFalse(focus.shouldStopAfterLoss(oldEvent))
        assertTrue(focus.shouldStopAfterLoss(currentEvent))
        focus.close()
        assertFalse(focus.shouldStopAfterLoss(currentEvent))
    }

    @Test fun focusDeniedDoesNotModifyMediaOrClaimOwnership() {
        var mediaVolume = 1.0
        var releases = 0
        val focus = AudioFocusLease(
            requestFocus = { false },
            abandonFocus = {
                mediaVolume = 1.0
                releases++
            }
        )
        assertFalse(focus.acquire())
        assertFalse(focus.shouldStopAfterLoss())
        focus.close()
        assertEquals(1.0, mediaVolume, 0.0)
        assertEquals(0, releases)
    }
}

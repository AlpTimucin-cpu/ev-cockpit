package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class PlaybackStallGuardTest {
    @Test fun noWarningBeforeThresholdAndOnlyOncePerBufferingInterval() {
        val g=PlaybackStallGuard(25_000L)
        assertFalse(g.shouldRecover(100_000L))
        g.buffering(10_000L)
        assertFalse(g.shouldRecover(34_999L))
        assertTrue(g.shouldRecover(35_000L))
        assertFalse(g.shouldRecover(45_000L))
        assertTrue(g.isBuffering)
    }

    @Test fun readyClearsWindowAndNextBufferMayRecover() {
        val g=PlaybackStallGuard()
        g.buffering(0L)
        assertTrue(g.shouldRecover(26_000L))
        g.ready()
        assertFalse(g.isBuffering)
        assertFalse(g.shouldRecover(90_000L))
        g.buffering(100_000L)
        assertFalse(g.shouldRecover(124_999L))
        assertTrue(g.shouldRecover(125_000L))
    }

    @Test fun restartAndClockRollbackCannotCauseImmediateRecovery() {
        val g=PlaybackStallGuard()
        g.buffering(10_000L)
        assertFalse(g.shouldRecover(9_000L))
        g.restart(70_000L)
        assertFalse(g.shouldRecover(70_000L))
        assertTrue(g.shouldRecover(95_000L))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsTooShortWatchdog() {
        PlaybackStallGuard(500L)
    }
}

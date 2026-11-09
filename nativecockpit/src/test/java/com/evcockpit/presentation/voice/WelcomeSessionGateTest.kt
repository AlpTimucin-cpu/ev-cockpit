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

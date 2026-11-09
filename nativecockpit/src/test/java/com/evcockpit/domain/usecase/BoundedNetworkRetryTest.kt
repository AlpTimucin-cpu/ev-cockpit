package com.evcockpit.domain.usecase

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoundedNetworkRetryTest {
    @Test fun ioFailureRetriesOnlyUpToLimit() = runBlocking {
        var attempts = 0
        val result = BoundedNetworkRetry.io(3, 0L, 0L) {
            attempts++
            if (attempts < 3) throw IOException("synthetic timeout")
            "ready"
        }
        assertEquals(3, attempts)
        assertEquals("ready", result)
    }

    @Test fun nonTransientFailureIsNotRetried() = runBlocking {
        var attempts = 0
        try {
            BoundedNetworkRetry.io(3, 0L, 0L) {
                attempts++
                throw IllegalArgumentException("validation failure")
            }
            fail("expected exception")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, attempts)
        }
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        var attempts = 0
        try {
            BoundedNetworkRetry.io(3, 0L, 0L) {
                attempts++
                throw CancellationException("cancelled")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unboundedRetriesAreRejected() {
        runBlocking {
            BoundedNetworkRetry.io(100, 0L, 0L) { "must not run" }
        }
    }
}

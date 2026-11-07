package com.evcockpit.data.obd

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdProtocolRestorerTest {
    @Test fun allAdapterSettingsMustBeAcknowledgedBeforeSpeedPollingResumes() = runBlocking {
        val sent = mutableListOf<String>()
        var disconnected = 0
        ObdProtocolRestorer.restore(
            exchange = {
                sent += it
                "$it\rOK\r>"
            },
            close = { disconnected++ }
        )
        assertEquals(ObdProtocolRestorer.resetSequence, sent)
        assertEquals(0, disconnected)
    }

    @Test fun badCanResetAckClosesAdapterAndAbortsRemainingCommands() = runBlocking {
        val sent = mutableListOf<String>()
        var closed = false
        val failed = runCatching {
            ObdProtocolRestorer.restore(
                exchange = {
                    sent += it
                    if (it == "ATCRA") "?" else "OK"
                },
                close = { closed = true }
            )
        }
        assertTrue(failed.exceptionOrNull() is ObdProtocolRestoreException)
        assertTrue(closed)
        assertEquals(listOf("ATFCSM0", "ATCRA"), sent)
    }

    @Test fun timeoutDuringRestorationClosesAdapter() = runBlocking {
        var closed = false
        val failed = runCatching {
            ObdProtocolRestorer.restore(
                exchange = { throw IOException("timeout") },
                close = { closed = true }
            )
        }
        assertTrue(failed.exceptionOrNull() is ObdProtocolRestoreException)
        assertTrue(closed)
    }

    @Test fun cancellationPropagatesAndStillInvalidatesAdapter() = runBlocking {
        var closed = false
        val failed = runCatching {
            ObdProtocolRestorer.restore(
                exchange = { throw CancellationException("disconnect") },
                close = { closed = true }
            )
        }
        assertTrue(failed.exceptionOrNull() is CancellationException)
        assertTrue(closed)
    }
}

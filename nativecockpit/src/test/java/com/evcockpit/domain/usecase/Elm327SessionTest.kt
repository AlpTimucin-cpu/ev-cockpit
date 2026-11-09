package com.evcockpit.domain.usecase

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.evcockpit.domain.repository.ObdTransport

class Elm327SessionTest {
    private class FakeTransport : ObdTransport {
        override var connected: Boolean = false
            private set
        val commands = mutableListOf<String>()
        var failOnce = false
        var noDataNext = false
        var alwaysDropOnRead = false
        var connections = 0
        var disconnections = 0
        var blockRead: CompletableDeferred<Unit>? = null
        val readEntered = CompletableDeferred<Unit>()
        override suspend fun connect(timeoutMillis: Long) {
            connections++
            connected = true
        }
        override suspend fun exchange(command: String, timeoutMillis: Long): String {
            commands.add(command)
            if (command == "221E0E" && blockRead != null) {
                if (!readEntered.isCompleted) readEntered.complete(Unit)
                blockRead?.await()
            }
            if (alwaysDropOnRead && command == "221E0E") {
                connected = false
                throw IOException("Bluetooth out of range / ignition off")
            }
            if (failOnce && command == "221E0E") {
                failOnce = false
                throw IOException("Adapter disconnected")
            }
            if (noDataNext && command == "221E0E") {
                noDataNext = false
                return "NO DATA"
            }
            return when (command) {
                "ATZ" -> "ELM327 v1.5"
                "ATE0", "ATL0" -> "OK"
                else -> "62 1E 0E 04 68"
            }
        }
        override fun disconnect() {
            disconnections++
            connected = false
        }
    }

    @Test fun startsWithExactInitializationSequence() = runBlocking {
        val fake = FakeTransport()
        val session = Elm327Session(fake)
        session.initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0"), fake.commands)
        assertEquals("62 1E 0E 04 68", session.readOnly("22 1E 0E"))
        session.close()
    }

    @Test fun retriesConnectionAfterReadFailure() = runBlocking {
        val fake = FakeTransport().apply { failOnce = true }
        val session = Elm327Session(fake)
        session.initialize()
        assertEquals("62 1E 0E 04 68", session.readOnly("221E0E"))
        assertEquals(2, fake.connections)
        assertEquals(2, fake.commands.count { it == "ATZ" })
        session.close()
    }

    @Test fun permanentDropRetriesThreeTimesThenExposesError() = runBlocking {
        val adapter = FakeTransport().apply { alwaysDropOnRead = true }
        val session = Elm327Session(adapter)
        session.initialize()
        val result = runCatching { session.readOnly("221E0E") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(3, adapter.commands.count { it == "221E0E" })
        assertEquals(3, adapter.connections)
        assertTrue(adapter.disconnections >= 3)
        assertFalse(adapter.connected)
        assertEquals(ElmSessionPhase.ERROR, session.state.value.phase)
        assertTrue(session.state.value.error?.contains("after three attempts") == true)
        // The caller/controller must publish the error to ObdConnectionState.ERROR.
        session.close()
    }

    @Test fun closedSessionCanInitializeAgainWithFreshTransportState() = runBlocking {
        val adapter = FakeTransport()
        val session = Elm327Session(adapter)
        session.initialize()
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        session.close()
        assertEquals(ElmSessionPhase.DISCONNECTED, session.state.value.phase)
        assertFalse(adapter.connected)
        session.initialize() // explicit caller reconnect after close
        assertEquals(2, adapter.connections)
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        assertEquals(2, adapter.commands.count { it == "ATZ" })
        session.close()
    }

    @Test fun closeDuringReadCannotPublishStaleResultIntoNewSession() = runBlocking {
        val adapter = FakeTransport().apply {
            blockRead = CompletableDeferred()
        }
        val session = Elm327Session(adapter)
        session.initialize()
        val pending = async {
            runCatching { session.readOnly("221E0E") }
        }
        adapter.readEntered.await()
        session.close() // Must not wait on the command mutex.
        assertEquals(ElmSessionPhase.DISCONNECTED, session.state.value.phase)
        adapter.blockRead?.complete(Unit) // A late old response arrives.
        assertTrue(pending.await().isFailure)
        assertEquals(ElmSessionPhase.DISCONNECTED, session.state.value.phase)
        adapter.blockRead = null
        session.initialize()
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        session.close()
    }

    @Test fun dangerousNonReadCommandsAreRejected(): Unit = runBlocking {
        val session = Elm327Session(FakeTransport())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readOnly("2F1234") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readOnly("ATSH7E0") }
        }
    }
    @Test fun onlyMode01AndUds22AreAcceptedEvenBeforeConnection(): Unit = runBlocking {
        val session = Elm327Session(FakeTransport())
        for (disallowed in listOf("0902", "1001", "140000", "2E1234", "2F1234",
                                  "310001", "ATSH710", "2701", "3E00")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { session.readOnly(disallowed) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { session.readRawOnce(disallowed) }
            }
        }
    }

    @Test fun rawOnceNeedsReadyAndReturnsGenuineNoDataWithoutReconnecting() = runBlocking {
        val fake = FakeTransport()
        val session = Elm327Session(fake)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.readRawOnce("010D") }
        }
        session.initialize()
        assertEquals("62 1E 0E 04 68", session.readRawOnce("221E0E"))
        fake.noDataNext = true
        assertEquals("NO DATA", session.readRawOnce("221E0E"))
        assertEquals(1, fake.connections)
        assertEquals(ElmSessionPhase.READY, session.state.value.phase)
        session.close()
    }

    @Test fun rawOnceNeverAcceptsCommandsThatWriteToEcu(): Unit = runBlocking {
        val session = Elm327Session(FakeTransport())
        session.initialize()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readRawOnce("2E1234") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.readRawOnce("ATSH710") }
        }
        session.close()
    }

}

package com.evcockpit.domain.usecase

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.evcockpit.domain.repository.ObdTransport

enum class ElmSessionPhase { DISCONNECTED, CONNECTING, READY, ERROR }

data class ElmSessionState(
    val phase: ElmSessionPhase = ElmSessionPhase.DISCONNECTED,
    val error: String? = null
)

/**
 * Coroutine-safe serial ELM command queue: Mutex is shared by AT initialization
 * and read requests. close() is deliberately non-blocking: socket.close()/GATT
 * close() must interrupt an in-flight exchange, even if it holds the mutex.
 *
 * close() means "disconnect", not "dispose": the same instance may initialize
 * again. Generation tokens reject callbacks/results from an earlier connection.
 */
class Elm327Session(private val transport: ObdTransport) {
    private val commands = Mutex()
    private val _state = MutableStateFlow(ElmSessionState())
    val state: StateFlow<ElmSessionState> = _state.asStateFlow()
    @Volatile private var generation = 0L

    suspend fun initialize(): String = commands.withLock {
        val token = generation
        try {
            startSession(token)
        } catch (cancel: CancellationException) {
            transport.disconnect()
            if (token == generation) _state.value = ElmSessionState()
            throw cancel
        } catch (error: Exception) {
            transport.disconnect()
            if (token == generation) {
                _state.value = ElmSessionState(
                    ElmSessionPhase.ERROR,
                    error.message ?: "Could not initialize ELM327"
                )
            }
            throw error
        }
    }

    suspend fun readOnly(command: String): String = commands.withLock {
        val query = command.trim().uppercase(Locale.ROOT).replace(" ", "")
        require(query.matches(Regex("01[0-9A-F]{2,6}|22[0-9A-F]{4}"))) {
            "Only permitted read-only queries are allowed"
        }

        val token = generation
        var last: Exception? = null
        repeat(3) { attempt ->
            ensureCurrent(token)
            try {
                if (_state.value.phase != ElmSessionPhase.READY || !transport.connected) {
                    startSession(token)
                }
                val result = transport.exchange(query, 3_000L)
                ensureCurrent(token)
                if (result.isBlank() || result.contains("NO DATA", true) ||
                    result.contains("ERROR", true) || result.contains("?", true)
                ) throw IOException("OBD did not return a valid response")
                return@withLock result
            } catch (cancel: CancellationException) {
                transport.disconnect()
                if (token == generation) _state.value = ElmSessionState()
                if (cancel !is TimeoutCancellationException) throw cancel
                last = cancel
            } catch (error: Exception) {
                transport.disconnect()
                last = error
                if (error is SecurityException) {
                    _state.value = ElmSessionState(ElmSessionPhase.ERROR, "Bluetooth permission is missing")
                    throw error
                }
            }
            ensureCurrent(token)
            if (attempt < 2) {
                _state.value = ElmSessionState(ElmSessionPhase.CONNECTING)
                delay(400L * (1 shl attempt))
            }
        }
        val error = IOException("Could not read OBD after three attempts", last)
        if (token == generation) {
            _state.value = ElmSessionState(ElmSessionPhase.ERROR, error.message)
        }
        throw error
    }

    /**
     * One tolerant read for an already-connected vehicle session.
     * A genuine ECU may reply NO DATA to an unsupported PID. Return that
     * response to the parser without fabricating values or reconnecting.
     * The caller owns the single CAN configuration/polling coroutine.
     */
    suspend fun readRawOnce(command: String): String = commands.withLock {
        val query = command.trim().uppercase(Locale.ROOT).replace(" ", "")
        require(query.matches(Regex("01[0-9A-F]{2,6}|22[0-9A-F]{4}"))) {
            "Only read-only PID and UDS requests are allowed"
        }
        val token = generation
        ensureCurrent(token)
        check(_state.value.phase == ElmSessionPhase.READY && transport.connected) {
            "ELM327 session is not ready"
        }
        val reply = transport.exchange(query, 6_000L)
        ensureCurrent(token)
        reply // NO DATA is a real ECU reply; caller decides whether it is useful.
    }

    private suspend fun startSession(token: Long): String {
        ensureCurrent(token)
        _state.value = ElmSessionState(ElmSessionPhase.CONNECTING)
        if (!transport.connected) transport.connect(12_000L)
        ensureCurrent(token)

        return try {
            var identity = ""
            // Serialized coroutine command queue: never overlap AT and PID writes.
            for (cmd in listOf("ATZ", "ATE0", "ATL0")) {
                val reply = transport.exchange(cmd, if (cmd == "ATZ") 6_000L else 3_000L)
                ensureCurrent(token)
                if (reply.isBlank() || reply.contains("ERROR", true) || reply.contains("?"))
                    throw IOException("ELM327 initialization command failed: $cmd")
                if (cmd == "ATZ") identity = reply
            }
            _state.value = ElmSessionState(ElmSessionPhase.READY)
            identity
        } catch (error: Exception) {
            transport.disconnect()
            throw error
        }
    }

    private fun ensureCurrent(token: Long) {
        if (token != generation) throw IOException("OBD session closed")
    }

    @Synchronized
    fun close() {
        ++generation // Invalidates in-flight commands before the socket is closed.
        _state.value = ElmSessionState()
        transport.disconnect() // SPP read and BLE await are unblocked by transport.
    }
}

package com.evcockpit.data.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.evcockpit.domain.repository.ObdTransport

/** Bluetooth Classic RFCOMM / SPP; no device scan or unsolicited ECU writes. */
class BluetoothClassicObdTransport(
    private val context: Context,
    private val pairedDevice: BluetoothDevice
) : ObdTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var readJob: Job? = null
    @Volatile private var incoming = Channel<ByteArray>(Channel.BUFFERED)
    @Volatile private var ready = false

    override val connected: Boolean get() = ready && socket?.isConnected == true

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Bluetooth connection permission is required")
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(timeoutMillis: Long) = lock.withLock {
        if (connected) return@withLock
        checkPermission()
        disconnect()
        require(timeoutMillis in 1_000L..30_000L)
        val next = pairedDevice.createRfcommSocketToServiceRecord(SPP_UUID)
        socket = next
        try {
            withTimeout(timeoutMillis) {
                // BluetoothSocket.connect() is blocking: cancellation closes the socket.
                suspendCancellableCoroutine<Unit> { continuation ->
                    Thread({
                        try {
                            next.connect()
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }, "eya-obd-spp-connect").apply { isDaemon = true; start() }
                    continuation.invokeOnCancellation { runCatching { next.close() } }
                }
            }
            val receiveChannel = Channel<ByteArray>(Channel.BUFFERED)
            incoming = receiveChannel
            ready = true
            readJob = scope.launch {
                val bytes = ByteArray(512)
                try {
                    while (isActive) {
                        val count = next.inputStream.read(bytes)
                        if (count < 0) throw IOException("OBD disconnected")
                        if (count > 0) receiveChannel.send(bytes.copyOf(count))
                    }
                } catch (error: Exception) {
                    receiveChannel.close(IOException("SPP read connection closed", error))
                    // A late callback from an old socket cannot drop a new link.
                    if (socket === next) ready = false
                }
            }
        } catch (error: Exception) {
            disconnect()
            throw error
        }
    }

    override suspend fun exchange(command: String, timeoutMillis: Long): String =
        lock.withLock {
            if (!connected) throw IOException("No SPP connection")
            require(command.matches(Regex("[A-Z0-9]{2,20}"))) {
                "Only validated ELM ASCII commands are allowed"
            }
            require(timeoutMillis in 250L..15_000L)
            try {
                // Remove late bytes from an earlier response; only one request at a time.
                while (incoming.tryReceive().isSuccess) Unit
                withTimeout(timeoutMillis) {
                    val active = socket ?: throw IOException("SPP socket is closed")
                    withContext(Dispatchers.IO) {
                        active.outputStream.write((command + "\r").toByteArray(Charsets.US_ASCII))
                        active.outputStream.flush()
                    }
                    val reply = StringBuilder()
                    while (true) {
                        val chunk = incoming.receive()
                        for (byte in chunk) {
                            val ch = (byte.toInt() and 0xff).toChar()
                            if (ch == '>') return@withTimeout reply.toString().trim()
                            if (reply.length >= MAX_RESPONSE) throw IOException("ELM response is too large")
                            reply.append(ch)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    ""
                }
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }

    override fun disconnect() {
        // Must work even while exchange() holds 'lock'; never await that mutex here.
        ready = false
        val oldSocket = socket
        socket = null
        val oldJob = readJob
        readJob = null
        // Channel.cancel() drops buffered/stale bytes AND resumes blocked receives.
        // Channel.close() alone could still return bytes from a previous session.
        incoming.cancel()
        runCatching { oldSocket?.close() } // Unblocks blocking inputStream.read().
        oldJob?.cancel()
    }

    companion object {
        private val SPP_UUID = UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
        )
        private const val MAX_RESPONSE = 32_768
    }
}

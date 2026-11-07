package com.evcockpit.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.evcockpit.data.obd.ElmSpeedParser
import com.evcockpit.data.obd.MebUdsParser
import com.evcockpit.data.obd.ObdWireCommandPolicy
import com.evcockpit.data.obd.ObdProtocolRestorer
import com.evcockpit.data.obd.ObdProtocolRestoreException
import com.evcockpit.domain.model.EngineeringMath
import com.evcockpit.domain.model.EngineeringMetrics
import com.evcockpit.domain.model.EngineeringSnapshot
import com.evcockpit.domain.model.MebResponseStatus
import com.evcockpit.domain.model.ObdConnectionState
import com.evcockpit.domain.model.ObdPhase
import com.evcockpit.domain.model.PairedObdDevice
import com.evcockpit.domain.model.TimedValue
import com.evcockpit.domain.model.VehicleTelemetry
import com.evcockpit.domain.repository.ObdConnectionController
import com.evcockpit.domain.repository.ObdTransport
import com.evcockpit.domain.usecase.Elm327Session
import com.evcockpit.domain.usecase.ElmSessionPhase
import com.evcockpit.domain.usecase.ObdDiagnosticSafetyPolicy
import com.evcockpit.domain.repository.VehicleRepository

/**
 * Bluetooth Classic ELM327 SPP. All traffic is serialized in one worker:
 * - standard 010D speed polling when the ECU supports it;
 * - explicit, one-shot, read-only UDS 22 queries from a strict allowlist.
 * ELM adapter settings are restored after diagnostics; no VIN, no writes,
 * no unverified battery values flowing into cockpit telemetry.
 */
@Singleton
class Elm327VehicleRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : VehicleRepository, ObdConnectionController {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _telemetry = MutableStateFlow(VehicleTelemetry())
    override val telemetry: StateFlow<VehicleTelemetry> = _telemetry.asStateFlow()
    private val _connection = MutableStateFlow(ObdConnectionState())
    override val connection: StateFlow<ObdConnectionState> = _connection.asStateFlow()
    private val _engineering = MutableStateFlow(EngineeringSnapshot())
    override val engineering: StateFlow<EngineeringSnapshot> = _engineering.asStateFlow()
    private val _metrics = MutableStateFlow(EngineeringMetrics())
    override val engineeringMetrics: StateFlow<EngineeringMetrics> = _metrics.asStateFlow()

    private fun recalculate() {
        _metrics.value = EngineeringMath.calculate(_engineering.value, System.currentTimeMillis())
    }

    /** Do not leave old speed in either dashboard OR engineering screen. */
    private fun clearSpeedObservation() {
        _telemetry.value = VehicleTelemetry()
        _engineering.value = _engineering.value.copy(speedKmh = TimedValue())
        recalculate()
        _connection.value = _connection.value.copy(
            lastPollSucceeded = false, lastSpeedAtMs = null, lastSpeedKmh = null
        )
    }
    private val diagnosticRequests = Channel<String>(capacity=1)
    private var task: Job? = null
    // A cancelled attempt must never overwrite the state/socket of a newer one.
    private var sessionEpoch: Long = 0L
    @Volatile private var socket: BluetoothSocket? = null
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun refreshPaired() {
        if (!hasPermission()) { permissionDenied(); return }
        if (adapter == null) {
            _connection.value = _connection.value.copy(devices = emptyList(),
                error = "Bu telefonda Bluetooth adaptörü bulunamadı.")
            return
        }
        if (!adapter.isEnabled) {
            _connection.value = _connection.value.copy(devices = emptyList(),
                error = "Bluetooth kapalı. Telefonun Bluetooth ayarlarından açın.")
            return
        }
        try {
            val found = adapter?.bondedDevices.orEmpty().mapNotNull { device ->
                val address = device.address.orEmpty()
                if (!BluetoothAdapter.checkBluetoothAddress(address)) return@mapNotNull null
                PairedObdDevice(device.name?.take(60) ?: "Paired Bluetooth device", address)
            }.sortedBy { it.name.lowercase() }
            _connection.value = _connection.value.copy(devices=found,
                error=if (adapter == null) "Bluetooth hardware was not found" else null)
        } catch (_: SecurityException) { permissionDenied() }
    }

    override fun permissionDenied() {
        _connection.value = _connection.value.copy(
            error = "Yakındaki cihazlar izni gerekli. Android uygulama izinlerinden izin verin."
        )
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        if (_connection.value.phase == ObdPhase.CONNECTING ||
            _connection.value.phase == ObdPhase.CONNECTED) return
        if (!hasPermission()) { permissionDenied(); return }
        if (adapter?.isEnabled != true) {
            _connection.value = _connection.value.copy(phase = ObdPhase.ERROR,
                error = "Bluetooth kapalı. Açıp tekrar deneyin.")
            return
        }
        refreshPaired()
        val device = try { adapter?.bondedDevices?.firstOrNull { it.address == address } }
            catch (_: SecurityException) { null }
        if (device == null) {
            _connection.value = _connection.value.copy(phase = ObdPhase.ERROR,
                error = "OBD cihazını önce Android Bluetooth ayarlarından eşleştirin.")
            return
        }
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            _connection.value = _connection.value.copy(phase = ObdPhase.ERROR,
                error = "Bu BLE adaptör için üreticinin UART/GATT UUID profili gerekiyor; " +
                    "Bluetooth Classic SPP bağlantısı kullanılamaz.")
            return
        }
        // Publish CONNECTING before launching: the Activity/Service must never
        // inspect an outdated DISCONNECTED snapshot.
        val epoch = ++sessionEpoch
        task?.cancel()
        socket?.closeSilently()
        socket = null
        _connection.value = _connection.value.copy(phase=ObdPhase.CONNECTING,
            selectedDevice=address,error=null,lastSpeedAtMs=null,lastSpeedKmh=null,
            lastPollSucceeded=false,diagnosticBusy=false,diagnosticResult=null)
        task = scope.launch {
            _telemetry.value = VehicleTelemetry()
            _engineering.value = EngineeringSnapshot()
            _metrics.value = EngineeringMetrics()
            try {
                withContext(Dispatchers.IO) { runSession(device) }
            } catch (_: CancellationException) {
                // disconnect() closes socket to unblock Bluetooth reads.
            } catch (e: Exception) {
                if (epoch == sessionEpoch) {
                    android.util.Log.e("EV-Cockpit-OBD", "OBD session failed", e)
                    val reason = when (e) {
                        is SecurityException -> "Bluetooth bağlantı izni reddedildi."
                        is IOException -> "ELM327 yanıt vermedi: " +
                            (e.message?.take(100) ?: "cihazı ve kontağı kontrol edin.")
                        else -> "ELM327 bağlantısı başarısız: " +
                            (e.message?.take(100) ?: "cihaz türünü kontrol edin.")
                    }
                    _connection.value = _connection.value.copy(
                        phase=ObdPhase.ERROR, diagnosticBusy=false, lastPollSucceeded=false,
                        lastSpeedAtMs=null, lastSpeedKmh=null, error=reason
                    )
                }
            } finally {
                if (epoch == sessionEpoch) {
                    socket?.closeSilently();socket=null
                    _telemetry.value=VehicleTelemetry()
                    _engineering.value=EngineeringSnapshot()
                    _metrics.value=EngineeringMetrics()
                    if (_connection.value.phase != ObdPhase.ERROR)
                        _connection.value=_connection.value.copy(phase=ObdPhase.DISCONNECTED,
                            lastPollSucceeded=false,lastSpeedAtMs=null,lastSpeedKmh=null,
                            diagnosticBusy=false,diagnosticResult=null)
                }
            }
        }
    }

    override fun requestMebDiagnostic(command: String) {
        if (command !in MebUdsParser.ALLOWED_REQUESTS) return
        if (!ObdDiagnosticSafetyPolicy.canRequest(
                _connection.value, System.currentTimeMillis()
            )
        ) {
            _connection.value = _connection.value.copy(
                error = "MEB ham sorgusu için güncel gerçek 0 km/sa OBD ölçümü gerekir. " +
                    "Aracı güvenli biçimde P konumuna alın; hız sıfır olması P kanıtı değildir."
            )
            return
        }
        _connection.value=_connection.value.copy(diagnosticBusy=true,diagnosticResult=null,error=null)
        if (diagnosticRequests.trySend(command).isFailure) {
            _connection.value=_connection.value.copy(diagnosticBusy=false,
                error="The OBD diagnostic queue is busy")
        }
    }

    override fun clearMebDiagnostic() {
        _connection.value=_connection.value.copy(diagnosticResult=null)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSession(device: BluetoothDevice) {
        // The adapter must have been paired in Android settings first.
        // Start with authenticated Bluetooth Classic SPP; if the adapter is a
        // known clone rejecting SDP authentication, retry paired-device RFCOMM.
        val conn = connectSpp(device)
        if (!conn.isConnected) throw IOException("Bluetooth SPP soketi açılamadı")
        // Adapt the same physical RFCOMM socket to the tested Elm327Session.
        // The single runSession worker still owns all CAN header/filter changes.
        val transport = object : ObdTransport {
            override val connected: Boolean get() = conn.isConnected
            override suspend fun connect(timeoutMillis: Long) {
                if (!conn.isConnected) throw IOException("SPP socket is no longer connected")
            }
            override suspend fun exchange(command: String, timeoutMillis: Long): String =
                sendAndRead(conn, command)
            override fun disconnect() { conn.closeSilently() }
        }
        val protocol = Elm327Session(transport)
        try {
            protocol.initialize() // ATZ, ATE0, ATL0; READY only on real response.
            if (protocol.state.value.phase != ElmSessionPhase.READY) {
                throw IOException("ELM327 initialization was not acknowledged")
            }
            for (command in listOf("ATS0","ATH0","ATSP0"))
                sendAndRead(conn, command)
            _connection.value = _connection.value.copy(phase=ObdPhase.CONNECTED, error=null)
        // SAFETY: Only SAE Mode 01 speed is polled after connection.
        // Unverified MEB DID/CAN mappings must NEVER be queried automatically.
        // A manufacturer DID read requires an explicit user selection and remains raw.
        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
            val manual = diagnosticRequests.tryReceive().getOrNull()
            // Poll the vehicle's SAE speed *before* slow MEB UDS reads.
            // A rejected manufacturer DID can take seconds; speed must not wait.
            try {
                val speed = ElmSpeedParser.decode(protocol.readRawOnce("010D"))
                if (speed != null) {
                    val now = System.currentTimeMillis()
                    _telemetry.value = VehicleTelemetry(speedKmh = TimedValue(speed, now))
                    _engineering.value = _engineering.value.copy(speedKmh = TimedValue(speed, now))
                    recalculate()
                    _connection.value = _connection.value.copy(
                        lastPollSucceeded = true, lastSpeedAtMs = now, lastSpeedKmh = speed
                    )
                } else {
                    clearSpeedObservation()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                clearSpeedObservation()
            }

            if (manual != null) {
                if (!ObdDiagnosticSafetyPolicy.canExecute(
                        _connection.value, System.currentTimeMillis()
                    )
                ) {
                    _connection.value = _connection.value.copy(
                        diagnosticBusy = false, diagnosticResult = null,
                        error = "Ham MEB sorgusu iptal edildi: güncel 0 km/sa ölçümü yok."
                    )
                } else {
                    try {
                        val response = runMebReadOnlyQuery(conn, protocol, manual)
                        val parsed = MebUdsParser.parse(
                            manual, response, System.currentTimeMillis()
                        )
                        // Only raw ECU reply is exposed. Never derive SoC/SoH
                        // from unverified DID scaling or headers.
                        _connection.value = _connection.value.copy(
                            diagnosticBusy = false, diagnosticResult = parsed, error = null
                        )
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (fatal: ObdProtocolRestoreException) {
                        // Unconfirmed CAN/filter reset: never poll PID 010D
                        // through a potentially stale 29-bit diagnostic filter.
                        clearSpeedObservation()
                        _connection.value = _connection.value.copy(
                            diagnosticBusy = false, diagnosticResult = null,
                            error = "CAN ayarları geri yüklenemedi; bağlantı güvenli biçimde kesildi."
                        )
                        throw fatal
                    } catch (_: Exception) {
                        _connection.value = _connection.value.copy(
                            diagnosticBusy = false,
                            error = "MEB read failed; DID/CAN mapping is unverified"
                        )
                    }
                }
            }

            delay(700L)
        }
        } finally {
            protocol.close() // Closes only the socket owned by this session.
        }
    }

    @SuppressLint("MissingPermission") // User permission was verified in connect().
    private suspend fun connectSpp(device: BluetoothDevice): BluetoothSocket {
        var failure: Exception? = null
        for (authenticated in listOf(true, false)) {
            val candidate = if (authenticated)
                device.createRfcommSocketToServiceRecord(spp)
            else device.createInsecureRfcommSocketToServiceRecord(spp)
            socket = candidate
            val timeoutJob = scope.launch {
                delay(12_000L)
                if (!candidate.isConnected) candidate.closeSilently()
            }
            try {
                // SDP discovery can prevent the OBD adapter accepting RFCOMM.
                adapter?.cancelDiscovery()
                candidate.connect()
                return candidate
            } catch (cancel: CancellationException) {
                candidate.closeSilently()
                throw cancel
            } catch (error: Exception) {
                failure = error
                candidate.closeSilently()
                // A permission denial cannot be fixed by a second socket mode.
                if (error is SecurityException) throw error
            } finally {
                timeoutJob.cancel()
            }
        }
        socket = null
        throw IOException(
            "Eşleştirilmiş OBD cihazı SPP bağlantısı kurmadı. " +
                "Cihazın Bluetooth türünü ve başka uygulamanın bağlı olup olmadığını kontrol edin.",
            failure
        )
    }

    private suspend fun runMebReadOnlyQuery(
        conn: BluetoothSocket, protocol: Elm327Session, command: String
    ): String {
        require(command in MebUdsParser.ALLOWED_REQUESTS)
        // Candidate ECU addresses are unverified for this specific ID.4.
        // ReadDataByIdentifier 22 only; no diagnostic write services.
        val capacityRead = command == "222AB2"
        val configuration = if (capacityRead) listOf(
            "ATH1", "ATSP6", "ATCAF1", "ATCFC1",
            "ATSH710", "ATCRA77A", "ATFCSH710",
            "ATFCSD300000", "ATFCSM1"
        ) else listOf(
            "ATH1", "ATSP7", "ATCP17", "ATCAF1", "ATCFC1",
            "ATSHFC007B", "ATCRA17FE007B", "ATFCSH17FC007B",
            "ATFCSD300000", "ATFCSM1"
        )
        var configurationAttempted = false
        try {
            for (adapterCommand in configuration) {
                configurationAttempted = true
                val reply = sendAndRead(conn, adapterCommand)
                if (!ObdWireCommandPolicy.acknowledged(adapterCommand, reply))
                    throw IOException("ELM diagnostic setting not acknowledged: $adapterCommand")
            }
            return protocol.readRawOnce(command)
        } finally {
            if (configurationAttempted) {
                ObdProtocolRestorer.restore(
                    exchange = { reset -> sendAndRead(conn, reset) },
                    close = { conn.closeSilently() }
                )
            }
        }
    }

    private suspend fun sendAndRead(conn:BluetoothSocket,command:String):String {
        require(ObdWireCommandPolicy.allowed(command)) {
            "Unauthorized ELM command rejected before transport write"
        }
        conn.outputStream.write((command+"\r").toByteArray(Charsets.US_ASCII))
        conn.outputStream.flush()
        return try {
            withTimeout(6_000L) {
                val input=conn.inputStream
            val builder=StringBuilder()
            val chunk=ByteArray(256)
            while (!builder.contains('>')) {
                val ready=input.available()
                if (ready>0) {
                    val count=input.read(chunk,0,minOf(chunk.size,ready))
                    if (count<0) throw IOException("ELM327 connection closed")
                    builder.append(String(chunk,0,count,Charsets.US_ASCII))
                    if (builder.length>4096) throw IOException("ELM327 reply oversized")
                } else delay(35)
            }
                builder.toString()
            }
        } catch (timeout: TimeoutCancellationException) {
            // An ELM command timeout is a failed ECU/adapter exchange, not
            // a user-initiated coroutine cancellation. Keep the error visible.
            throw IOException("ELM327 komutu $command için 6 sn yanıt vermedi", timeout)
        }
    }

    override fun disconnect() {
        ++sessionEpoch
        task?.cancel();task=null
        socket?.closeSilently();socket=null
        while (diagnosticRequests.tryReceive().isSuccess) { /* discard pending reads */ }
        _telemetry.value=VehicleTelemetry()
        _engineering.value=EngineeringSnapshot()
        _metrics.value=EngineeringMetrics()
        _connection.value=_connection.value.copy(phase=ObdPhase.DISCONNECTED,
            selectedDevice=null,lastPollSucceeded=false,lastSpeedAtMs=null,
            lastSpeedKmh=null,diagnosticBusy=false,diagnosticResult=null,error=null)
    }

    private fun BluetoothSocket.closeSilently() { try { close() } catch (_:Exception) { } }
}

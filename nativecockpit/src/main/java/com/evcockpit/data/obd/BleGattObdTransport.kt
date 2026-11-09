package com.evcockpit.data.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import com.evcockpit.domain.repository.ObdTransport

/**
 * An ELM327 BLE dongle needs its OWN documented UART/GATT profile.
 * There is no universal ELM327 BLE UUID: never guess one.
 */
data class BleUartProfile(
    val service: UUID,
    val writeCharacteristic: UUID,
    val notifyCharacteristic: UUID
)

/** Foreground, permission-gated and serialized BLE GATT UART transport. */
class BleGattObdTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val profile: BleUartProfile
) : ObdTransport {

    private class Connection {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var tx: BluetoothGattCharacteristic? = null
        @Volatile var ready = false
        val startup = CompletableDeferred<Unit>()
        var writeAck: CompletableDeferred<Unit>? = null
        val incoming = Channel<ByteArray>(Channel.BUFFERED)
    }

    private val lock = Mutex()
    @Volatile private var active: Connection? = null
    override val connected: Boolean get() = active?.ready == true

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Bluetooth connection permission is required")
    }

    @SuppressLint("MissingPermission") // Checked before invoking GATT.
    override suspend fun connect(timeoutMillis: Long) = lock.withLock {
        if (connected) return@withLock
        checkPermission()
        disconnect()
        require(timeoutMillis in 1_000L..30_000L)

        val session = Connection()
        active = session
        val callback = object : BluetoothGattCallback() {
            private fun fail(message: String) {
                if (active !== session) return
                val cause = IOException(message)
                session.ready = false
                if (!session.startup.isCompleted) session.startup.completeExceptionally(cause)
                session.writeAck?.let { if (!it.isCompleted) it.completeExceptionally(cause) }
                session.incoming.close(cause)
            }

            override fun onConnectionStateChange(
                gatt: BluetoothGatt, status: Int, newState: Int
            ) {
                if (active !== session) return
                if (status != BluetoothGatt.GATT_SUCCESS ||
                    newState == BluetoothProfile.STATE_DISCONNECTED
                ) {
                    fail("BLE GATT disconnected: $status")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.discoverServices()) fail("BLE service discovery did not start")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (active !== session) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("BLE service discovery failed")
                    return
                }
                val service = gatt.getService(profile.service)
                val writer = service?.getCharacteristic(profile.writeCharacteristic)
                val reader = service?.getCharacteristic(profile.notifyCharacteristic)
                if (writer == null || reader == null) {
                    fail("Dongle BLE UART UUID profile does not match")
                    return
                }
                val canWrite = writer.properties and
                    (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                if (!canWrite) { fail("BLE UART TX is not writable"); return }
                session.tx = writer
                val canNotify =
                    reader.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val canIndicate =
                    reader.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (!canNotify && !canIndicate) {
                    fail("BLE UART RX does not support notifications")
                    return
                }
                if (!gatt.setCharacteristicNotification(reader, true)) {
                    fail("Could not enable BLE notifications"); return
                }
                val cccd = reader.getDescriptor(CCCD)
                if (cccd == null) { fail("BLE CCCD descriptor is missing"); return }
                val value = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                if (!started) fail("BLE notification registration failed")
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
            ) {
                if (active !== session || descriptor.uuid != CCCD) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    session.ready = true
                    if (!session.startup.isCompleted) session.startup.complete(Unit)
                } else fail("BLE notification descriptor error: $status")
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (active !== session) return
                session.writeAck?.let { ack ->
                    if (!ack.isCompleted) {
                        if (status == BluetoothGatt.GATT_SUCCESS) ack.complete(Unit)
                        else ack.completeExceptionally(IOException("BLE UART write error $status"))
                    }
                }
            }

            @Deprecated("API 33+ supplies value directly")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < 33 && active === session &&
                    characteristic.uuid == profile.notifyCharacteristic) {
                    @Suppress("DEPRECATION")
                    val copy = characteristic.value?.clone()
                    if (copy != null) session.incoming.trySend(copy)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (active === session &&
                    characteristic.uuid == profile.notifyCharacteristic) {
                    session.incoming.trySend(value.copyOf())
                }
            }
        }

        try {
            session.gatt = device.connectGatt(
                context, false, callback, BluetoothDevice.TRANSPORT_LE
            ) ?: throw IOException("Could not create a BLE GATT session")
            withTimeout(timeoutMillis) { session.startup.await() }
        } catch (error: Exception) {
            disconnect()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun exchange(command: String, timeoutMillis: Long): String =
        lock.withLock {
            checkPermission()
            val session = active?.takeIf { it.ready }
                ?: throw IOException("No BLE UART connection")
            val gatt = session.gatt ?: throw IOException("BLE GATT is closed")
            val tx = session.tx ?: throw IOException("BLE TX characteristic is missing")
            require(command.matches(Regex("[A-Z0-9]{2,16}")))
            require(timeoutMillis in 250L..15_000L)
            try {
                while (session.incoming.tryReceive().isSuccess) Unit
                withTimeout(timeoutMillis) {
                    val bytes = (command + "\r").toByteArray(Charsets.US_ASCII)
                    val waitForAck = tx.properties and
                        BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    val writeType = if (waitForAck)
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val ack = CompletableDeferred<Unit>()
                    session.writeAck = ack

                    val submitted = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(tx, bytes, writeType) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        tx.value = bytes
                        @Suppress("DEPRECATION")
                        tx.writeType = writeType
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(tx)
                    }
                    if (!submitted) throw IOException("Could not send the BLE UART command")
                    if (waitForAck) ack.await()
                    val response = StringBuilder()
                    while (true) {
                        val chunk = session.incoming.receive()
                        for (byte in chunk) {
                            val ch = (byte.toInt() and 0xff).toChar()
                            if (ch == '>') return@withTimeout response.toString().trim()
                            if (response.length >= 32_768) {
                                throw IOException("ELM response is too large")
                            }
                            response.append(ch)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    ""
                }
            } catch (error: Exception) {
                disconnect()
                throw error
            } finally {
                session.writeAck = null
            }
        }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val old = active ?: return
        active = null
        old.ready = false
        old.incoming.close()
        if (!old.startup.isCompleted) {
            old.startup.completeExceptionally(IOException("BLE connection closed"))
        }
        old.writeAck?.let {
            if (!it.isCompleted) it.completeExceptionally(IOException("BLE connection closed"))
        }
        runCatching { old.gatt?.disconnect() }
        runCatching { old.gatt?.close() }
        old.gatt = null
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}

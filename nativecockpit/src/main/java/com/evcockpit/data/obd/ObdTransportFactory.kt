package com.evcockpit.data.obd

import android.bluetooth.BluetoothDevice
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.evcockpit.domain.repository.ObdTransport
import com.evcockpit.domain.usecase.Elm327Session

/**
 * Explicit adapter type selection. BLE profile UUIDs must come from the
 * documented dongle model; they cannot be inferred from a Car Scanner APK.
 */
@Singleton
class ObdTransportFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun classic(pairedDevice: BluetoothDevice): ObdTransport =
        BluetoothClassicObdTransport(context, pairedDevice)

    fun ble(device: BluetoothDevice, profile: BleUartProfile): ObdTransport =
        BleGattObdTransport(context, device, profile)

    fun session(transport: ObdTransport) = Elm327Session(transport)
}

package com.evcockpit.domain.repository

/** Both Bluetooth SPP and BLE UART/GATT implement the same ELM327 protocol. */
interface ObdTransport {
    val connected: Boolean

    /** Caller must have runtime BLUETOOTH_CONNECT and the correct device/profile. */
    suspend fun connect(timeoutMillis: Long = 12_000L)

    /** Exactly one ELM327 ASCII command and its prompt-terminated response. */
    suspend fun exchange(command: String, timeoutMillis: Long = 3_000L): String

    /** Must abort blocking reads and release the socket/GATT object. */
    fun disconnect()
}

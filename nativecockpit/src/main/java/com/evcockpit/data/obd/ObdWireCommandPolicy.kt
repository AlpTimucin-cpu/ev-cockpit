package com.evcockpit.data.obd

/**
 * ELM serial boundary. Only the exact commands used by this read-only
 * application may reach the physical adapter, including fixed protocol
 * setup/restoration. Never pass arbitrary user text, AT sequences or CAN
 * diagnostic write services to the socket.
 */
object ObdWireCommandPolicy {
    private val adapterCommands = setOf(
        "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0",
        "ATH1", "ATSP6", "ATCAF1", "ATCFC1",
        "ATSH710", "ATCRA77A", "ATFCSH710",
        "ATFCSD300000", "ATFCSM1",
        "ATSP7", "ATCP17", "ATSHFC007B", "ATCRA17FE007B",
        "ATFCSH17FC007B", "ATFCSM0", "ATCRA"
    )

    fun allowed(command: String): Boolean =
        command == "010D" || command in MebUdsParser.ALLOWED_REQUESTS ||
            command in adapterCommands

    /** ELM adapter commands must return a plain OK, not NO DATA/?/ERROR. */
    fun acknowledged(command: String, response: String): Boolean {
        if (command !in adapterCommands || response.length !in 1..512) return false
        val lines = response.uppercase().replace('>', '\r')
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != command }
        return lines.size == 1 && lines.single() == "OK"
    }
}

/** A failed protocol restoration invalidates the entire SPP session. */
class ObdProtocolRestoreException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

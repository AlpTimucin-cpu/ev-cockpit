package com.evcockpit.data.obd

import com.evcockpit.domain.model.MebDiagnosticReport
import com.evcockpit.domain.model.MebResponseStatus

/**
 * Read-only, fail-closed ISO-TP 29-bit CAN parser for specific MEB battery UDS
 * responses. 17FE007B is the expected ECU response ID from a community
 * parameter list; requests must be sent to 17FC007B by the adapter layer.
 * These are UNVERIFIED candidate diagnostic addresses and must NEVER be used as cockpit measurements.
 *
 * Handles ISO-TP single and consecutive frames; ignores echoed commands,
 * ELM 'SEARCHING...', and adapter prompts. Does not accept headerless data
 * because there is no trustworthy ECU identity without the CAN header.
 */
object MebUdsParser {
    val ALLOWED_REQUESTS: Set<String> = setOf(
        "22028C", "221E0E", "221E0F", "221E3B", "221E3D",
        "221E33", "221E34", "221E32", "221E1B", "222AB2"
    )
    const val REQUEST_ECU = "17FC007B"
    private const val RESPONSE_ECU = "17FE007B"
    private const val CAPACITY_ECU = "0000077A"
    private const val MAX_RESPONSE = 4096
    private val byteToken = Regex("[0-9A-F]{2}")

    fun parse(request: String, response: String, nowMillis: Long): MebDiagnosticReport {
        require(request in ALLOWED_REQUESTS) { "Unsupported diagnostic request" }
        val expectedEcu = if (request == "222AB2") CAPACITY_ECU else RESPONSE_ECU
        fun result(status: MebResponseStatus, payload: ByteArray? = null,
                   soc: Double? = null, temp: Double? = null): MebDiagnosticReport =
            MebDiagnosticReport(request, nowMillis, status,
                if (status == MebResponseStatus.WRONG_ECU || status == MebResponseStatus.NO_DATA ||
                    status == MebResponseStatus.ADAPTER_ERROR) null else expectedEcu,
                payload?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }, soc, temp)

        if (response.length !in 1..MAX_RESPONSE) return result(MebResponseStatus.MALFORMED)
        val text = response.uppercase()
        if (listOf("CAN ERROR", "BUS ERROR", "UNABLE TO CONNECT", "STOPPED", "?")
                .any(text::contains)) return result(MebResponseStatus.ADAPTER_ERROR)
        if (text.contains("NO DATA")) return result(MebResponseStatus.NO_DATA)
        val frames = mutableListOf<List<Int>>()
        var foreign = false
        for (line in text.replace('>', '\r').split('\r','\n')) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed == request || trimmed == "OK" ||
                trimmed.startsWith("SEARCHING")) continue
            val compact = trimmed.filterNot { it.isWhitespace() }
            if (!compact.matches(Regex("[0-9A-F]+"))) continue
            if (compact.length < 9) continue
            // Battery ECU: 29-bit 17FE007B. Capacity 2AB2: 11-bit 77A.
            // Refuse headerless data: never infer an ECU from payload alone.
            val sourceLength = when {
                compact.startsWith(expectedEcu) -> expectedEcu.length
                expectedEcu == CAPACITY_ECU && compact.startsWith("77A") -> 3
                else -> {
                    foreign = true
                    continue
                }
            }
            val hex = compact.drop(sourceLength)
            if (hex.length !in 2..32 || hex.length % 2 != 0) return result(MebResponseStatus.MALFORMED)
            frames.add(byteToken.findAll(hex).map { it.value.toInt(16) }.toList())
        }
        if (foreign || frames.isEmpty()) return result(MebResponseStatus.WRONG_ECU)
        val first = frames.first()
        val payload = when (first.firstOrNull()?.ushr(4)) {
            0 -> {
                val length = first[0] and 0x0F
                if (length !in 3..7 || first.size < length + 1 || frames.size != 1)
                    return result(MebResponseStatus.INCOMPLETE)
                first.drop(1).take(length)
            }
            1 -> {
                if (first.size < 3) return result(MebResponseStatus.INCOMPLETE)
                val length = ((first[0] and 0x0F) shl 8) + first[1]
                if (length !in 8..256) return result(MebResponseStatus.MALFORMED)
                val data = first.drop(2).toMutableList()
                var expected = 1
                for (frame in frames.drop(1)) {
                    if (frame.isEmpty() || frame[0] != 0x20 + (expected and 0x0F))
                        return result(MebResponseStatus.INCOMPLETE)
                    data.addAll(frame.drop(1)); expected++
                    if (data.size >= length) break
                }
                if (data.size < length) return result(MebResponseStatus.INCOMPLETE)
                data.take(length)
            }
            else -> return result(MebResponseStatus.MALFORMED)
        }
        if (payload.size >= 3 && payload[0] == 0x7F && payload[1] == 0x22)
            return result(MebResponseStatus.NEGATIVE, payload.toSignedBytes())
        if (payload.size < 4 || payload[0] != 0x62) return result(MebResponseStatus.MALFORMED)
        val actualDid = "%02X%02X".format(payload[1], payload[2])
        if (actualDid != request.drop(2))
            return result(MebResponseStatus.WRONG_IDENTIFIER, payload.toSignedBytes())
        val bytes = payload.toSignedBytes()
        // Vehicle-specific DID meanings/scales are UNVERIFIED for this ID.4.
        // Preserve the ECU identity + raw payload without inventing SoC or temperature.
        return result(MebResponseStatus.VALID_RAW, bytes)
    }

    private fun List<Int>.toSignedBytes(): ByteArray =
        map { it.toByte() }.toByteArray()
}

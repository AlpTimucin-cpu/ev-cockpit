package com.evcockpit.data.obd

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Verified reset of every ELM setting changed for manual UDS reads.
 *
 * Never continue the next SAE speed poll when a CAN filter/flow-control
 * restoration failed. A bad ACK, timeout or disconnection closes the
 * underlying adapter link; the caller must terminate its polling session.
 */
internal object ObdProtocolRestorer {
    val resetSequence = listOf("ATFCSM0", "ATCRA", "ATSP0", "ATH0")

    suspend fun restore(
        exchange: suspend (String) -> String,
        close: () -> Unit
    ) {
        try {
            for (command in resetSequence) {
                val reply = exchange(command)
                if (!ObdWireCommandPolicy.acknowledged(command, reply)) {
                    throw IOException("Adapter did not acknowledge $command reset")
                }
            }
        } catch (cancel: CancellationException) {
            close()
            throw cancel
        } catch (failure: Exception) {
            close()
            throw ObdProtocolRestoreException(
                "CAN protocol restoration failed; connection must be terminated", failure
            )
        }
    }
}

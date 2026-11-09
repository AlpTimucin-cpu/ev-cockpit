package com.evcockpit.domain.usecase

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Retries transient transport failures only; never catches cancellation. */
object BoundedNetworkRetry {
    suspend fun <T> io(
        maxAttempts: Int = 2,
        firstDelayMillis: Long = 400L,
        maxDelayMillis: Long = 2_000L,
        block: suspend () -> T
    ): T {
        require(maxAttempts in 1..4)
        require(firstDelayMillis >= 0L && maxDelayMillis >= firstDelayMillis)
        var delayMillis = firstDelayMillis
        for (attempt in 1 until maxAttempts) {
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IOException) {
                delay(delayMillis)
                delayMillis = (delayMillis * 2L).coerceAtMost(maxDelayMillis)
            }
        }
        return block()
    }
}

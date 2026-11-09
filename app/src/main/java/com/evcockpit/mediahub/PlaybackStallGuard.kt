package com.evcockpit.mediahub

/**
 * Bounded "stuck buffering" detection for an authorized Media3 stream.
 *
 * Uses elapsed realtime provided by the caller (never wall-clock time).
 * Keeps no URL, signed token, user metadata or playback content.
 * No DiPlay code/binary is incorporated.
 */
class PlaybackStallGuard(private val timeoutMs: Long = 25_000L) {
    private var bufferingSinceMs: Long? = null
    private var raisedForCurrentBuffer = false

    init { require(timeoutMs >= 1_000L) }

    fun buffering(nowMs: Long) {
        if (bufferingSinceMs == null) {
            bufferingSinceMs = nowMs
            raisedForCurrentBuffer = false
        }
    }

    fun ready() {
        bufferingSinceMs = null
        raisedForCurrentBuffer = false
    }

    /** One alarm per continuous buffering interval; wait for new buffering on retry. */
    fun shouldRecover(nowMs: Long): Boolean {
        val start = bufferingSinceMs ?: return false
        if (raisedForCurrentBuffer || nowMs < start) return false
        if (nowMs - start < timeoutMs) return false
        raisedForCurrentBuffer = true
        return true
    }

    fun restart(nowMs: Long) {
        ready()
        buffering(nowMs)
    }

    val isBuffering: Boolean get() = bufferingSinceMs != null
}

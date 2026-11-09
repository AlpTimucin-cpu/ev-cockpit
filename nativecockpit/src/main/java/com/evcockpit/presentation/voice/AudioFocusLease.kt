package com.evcockpit.presentation.voice

/**
 * One short navigation utterance's AudioFocus lease.
 * Each successful acquire after release gets a fresh generation. Capturing
 * that generation BEFORE posting loss callbacks prevents a queued old loss
 * from stopping speech that already acquired a new lease.
 *
 * Android OS callbacks delivered after the new request cannot be attributed
 * to an older platform request without OS-level identifiers; device QA remains
 * necessary.
 */
internal class AudioFocusLease(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit
) {
    private var held = false
    private var closed = false
    private var generation = 0L

    @Synchronized
    fun acquire(): Boolean {
        if (closed) return false
        if (held) return true
        val success = runCatching(requestFocus).getOrDefault(false)
        if (success) {
            held = true
            generation++
        }
        return success
    }

    /** Capture on receipt, not later inside a posted Runnable. */
    @Synchronized
    fun lossGeneration(): Long? = generation.takeIf { held && !closed }

    @Synchronized
    fun shouldStopAfterLoss(expectedGeneration: Long): Boolean =
        held && !closed && generation == expectedGeneration

    /** Retained for existing JVM simulations. */
    @Synchronized
    fun shouldStopAfterLoss(): Boolean = held && !closed

    @Synchronized
    fun release() {
        if (held) runCatching(abandonFocus)
        held = false
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        release()
    }
}

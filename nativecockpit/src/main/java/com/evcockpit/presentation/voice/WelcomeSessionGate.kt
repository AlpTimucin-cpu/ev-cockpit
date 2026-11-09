package com.evcockpit.presentation.voice

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One greeting attempt per application process. Shared by the OBD service
 * and the Android Auto host; a reconnect does not trigger a second greeting.
 *
 * It is not a physical ignition-state detector.
 */
internal class WelcomeSessionGate {
    private val consumed = AtomicBoolean(false)
    fun claim(): Boolean = consumed.compareAndSet(false, true)
}

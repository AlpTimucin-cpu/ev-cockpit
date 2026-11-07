package com.evcockpit.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process Android Auto navigation owner. A destroyed/stopped older Screen
 * MUST NOT switch off phone guidance arbitration for a newer car Session.
 *
 * Each Screen has a private opaque token; never persist it or use a route ID
 * as an ownership token (two concurrent screens can have the same route).
 */
object CarNavigationOwnership {
    private val mutableNavigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = mutableNavigating.asStateFlow()
    private var currentToken: Any? = null

    @Synchronized fun claim(token: Any) {
        currentToken = token
        mutableNavigating.value = true
    }

    @Synchronized fun owns(token: Any): Boolean = currentToken === token

    /** Returns true only if the caller relinquished the active session. */
    @Synchronized fun release(token: Any): Boolean {
        if (currentToken !== token) return false
        currentToken = null
        mutableNavigating.value = false
        return true
    }
}

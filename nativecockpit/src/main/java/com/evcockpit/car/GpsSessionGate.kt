package com.evcockpit.car

/**
 * Invalidation token for FusedLocation callbacks. A delayed callback from
 * a stopped or replaced Android Auto Screen GPS subscription is ignored.
 */
internal class GpsSessionGate {
    private var epoch = 0L
    private var active: Long? = null

    @Synchronized fun open(): Long {
        epoch++
        active = epoch
        return epoch
    }

    @Synchronized fun isCurrent(token: Long): Boolean =
        active != null && active == token

    @Synchronized fun invalidate() {
        epoch++
        active = null
    }
}

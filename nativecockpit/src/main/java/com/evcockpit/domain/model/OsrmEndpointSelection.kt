package com.evcockpit.domain.model

/**
 * Route geometry depends on a road router, NOT vehicle battery measurements.
 *
 * The public OSRM endpoint is permitted only for an explicitly opted-in
 * DEBUG build. It is an unstable demo, not a production routing service.
 * A configured private HTTPS server always wins.
 */
data class OsrmEndpointSelection(val baseUrl: String, val demo: Boolean)

object OsrmEndpointSelector {
    const val PUBLIC_DEBUG_DEMO_URL = "https://router.project-osrm.org/"

    fun choose(endpoints: ServiceEndpointConfig, debugBuild: Boolean): OsrmEndpointSelection? {
        val configured = endpoints.osrmBaseUrl?.let {
            ServiceEndpointConfig.checkedHttpsRoot(it, osrm = true)
        }
        if (configured != null) return OsrmEndpointSelection(configured, false)
        if (debugBuild && endpoints.debugDemoRoutingEnabled) {
            return OsrmEndpointSelection(PUBLIC_DEBUG_DEMO_URL, true)
        }
        return null
    }
}

/** Prevent rapid repeated requests to a shared, capacity-limited debug demo. */
internal class DebugDemoRequestGate(private val intervalMs: Long = 15_000L) {
    private var lastAcceptedAtMs: Long? = null

    @Synchronized fun accept(nowMs: Long): Boolean {
        if (nowMs <= 0L) return false
        val last = lastAcceptedAtMs
        if (last != null && nowMs >= last && nowMs - last < intervalMs) return false
        if (last != null && nowMs < last) return false
        lastAcceptedAtMs = nowMs
        return true
    }
}

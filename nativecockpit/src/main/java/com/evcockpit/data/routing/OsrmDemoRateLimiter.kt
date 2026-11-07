package com.evcockpit.data.routing

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * One shared throttle for route calculation AND charging-stop detour calls.
 *
 * Public FOSSGIS OSRM demo guidance: at most one request/second. Keep the
 * demo opt-in and debug-only; do not call it for background polling.
 * All retries go through the same mutex. Deliberately serialize the entire
 * call so a single user cannot overwhelm the shared public service.
 */
@Singleton
class OsrmDemoRateLimiter @Inject constructor() {
    private val mutex = Mutex()
    private var lastStartedAtNs: Long? = null

    suspend fun <T> execute(demo: Boolean, request: suspend () -> T): T {
        if (!demo) return request()
        return mutex.withLock {
            val now = System.nanoTime()
            lastStartedAtNs?.let { previous ->
                val elapsed = (now - previous) / 1_000_000L
                if (elapsed in 0..1_199L) delay(1_200L - elapsed)
            }
            lastStartedAtNs = System.nanoTime()
            request()
        }
    }

    companion object {
        /**
         * Identity required by the OSRM demo use guidance. Avoid pretending
         * requests originate from a browser or a different application.
         */
        fun client(base: OkHttpClient, demo: Boolean): OkHttpClient {
            if (!demo) return base
            return base.newBuilder().addInterceptor { chain ->
                val identified = chain.request().newBuilder().header(
                    "User-Agent",
                    "EV-Cockpit-Debug/1.0 (+https://github.com/AlpTimucin-cpu/ev-cockpit)"
                ).build()
                chain.proceed(identified)
            }.build()
        }
    }
}

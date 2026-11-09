package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class VodErrorPolicyTest {
    @Test fun deniedOrRemovedStreamsAreNeverAutomaticallyRetried() {
        for(code in listOf(401,403,404,410,429)) {
            val failure=VodErrorPolicy.classify(code, "ERROR_CODE_IO_BAD_HTTP_STATUS")
            assertFalse("HTTP $code must not auto retry",failure.canRetry)
            assertFalse(failure.message.contains("https://"))
        }
    }

    @Test fun networkAndSelectedServerFailuresPermitFiniteRetries() {
        assertTrue(VodErrorPolicy.classify(null,"ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT").canRetry)
        assertTrue(VodErrorPolicy.classify(503,"ERROR_CODE_IO_BAD_HTTP_STATUS").canRetry)
        val budget=StreamRetryBudget()
        assertEquals(1500L,budget.nextDelay(true))
        assertEquals(4000L,budget.nextDelay(true))
        assertNull(budget.nextDelay(true))
        budget.reset()
        assertNull(budget.nextDelay(false))
        assertEquals(1500L,budget.nextDelay(true))
    }

    @Test fun drmAndParsingErrorsDoNotRetry() {
        assertEquals(VodFailureKind.DRM,
            VodErrorPolicy.classify(null,"ERROR_CODE_DRM_LICENSE_EXPIRED").kind)
        assertFalse(VodErrorPolicy.classify(null,"ERROR_CODE_PARSING_CONTAINER_MALFORMED").canRetry)
    }

    @Test fun metricsReportDoesNotContainMediaAddressOrToken() {
        val d=PlaybackDiagnostics()
        d.start(1000L)
        d.buffering(2000L)
        d.ready(3500L)
        d.firstFrame(3600L)
        d.videoSize(1920,1080)
        d.problem("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")
        d.retry()
        val report=d.report(5000L)
        assertTrue(report.contains("1920"))
        assertTrue(report.contains("1500").not()) // Times displayed in seconds
        assertTrue(report.contains("Otomatik kurtarma denemeleri: 1"))
        assertFalse(report.contains("token="))
    }
}

package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorPolicyTest {
    @Test fun denyAutomaticRetryForUnauthorizedOrMissingSources() {
        listOf(401,403,404,410,429).forEach { code ->
            assertFalse(PlaybackErrorPolicy.classify("ERROR_CODE_IO_BAD_HTTP_STATUS",code).retryable)
        }
    }
    @Test fun retryOnlyTemporaryNetworkAndServerProblems() {
        assertTrue(PlaybackErrorPolicy.classify("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED").retryable)
        assertTrue(PlaybackErrorPolicy.classify("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT").retryable)
        assertTrue(PlaybackErrorPolicy.classify("ERROR_CODE_IO_BAD_HTTP_STATUS",503).retryable)
        assertFalse(PlaybackErrorPolicy.classify("ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED").retryable)
    }
    @Test fun noUrlsOrTokensAppearInMessages() {
        val test=PlaybackErrorPolicy.classify("ERROR_CODE_IO_BAD_HTTP_STATUS",403)
        assertFalse(test.userMessage.contains("token"))
        assertEquals("permission",test.category)
    }
}

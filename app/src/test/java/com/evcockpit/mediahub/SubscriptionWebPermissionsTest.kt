package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class SubscriptionWebPermissionsTest {
    @Test fun protectedMediaIsBoundToCurrentSiteOrigin() {
        assertTrue(SubscriptionWebPermissions.allowsProtectedMedia(StreamingService.NETFLIX,
            "https://www.netflix.com/watch/123", "https://www.netflix.com"))
        assertFalse(SubscriptionWebPermissions.allowsProtectedMedia(StreamingService.NETFLIX,
            "https://www.netflix.com/watch/123", "https://help.netflix.com"))
        assertFalse(SubscriptionWebPermissions.allowsProtectedMedia(StreamingService.PRIME_VIDEO,
            "https://www.primevideo.com/", "https://www.amazon.com"))
        assertFalse(SubscriptionWebPermissions.allowsProtectedMedia(StreamingService.NETFLIX,
            "https://www.netflix.com/", "https://www.netflix.com:8443"))
        assertFalse(SubscriptionWebPermissions.allowsProtectedMedia(StreamingService.NETFLIX,
            null, "https://www.netflix.com"))
    }
}

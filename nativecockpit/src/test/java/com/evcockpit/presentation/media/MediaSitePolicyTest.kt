package com.evcockpit.presentation.media

import org.junit.Assert.*
import org.junit.Test

class MediaSitePolicyTest {
    @Test fun loginHostsAndRedirectBoundaries() {
        assertTrue(MediaSitePolicy.allows(MediaSite.NETFLIX, "https://www.netflix.com/login"))
        assertTrue(MediaSitePolicy.allows(MediaSite.DISNEY, "https://mydisney.com/identity"))
        assertTrue(MediaSitePolicy.allows(MediaSite.PRIME, "https://www.amazon.com.tr/ap/signin"))
        for (url in listOf("https://netflix.com.evil.test/", "https://netflix.com@evil.test/",
            "http://netflix.com/", "intent://netflix.com/", "https://netflix.com:444/",
            "https://evil.test/?next=https://netflix.com", "file:///netflix.com", "https://user@netflix.com/")) {
            assertFalse(url, MediaSitePolicy.allows(MediaSite.NETFLIX, url))
        }
    }
    @Test fun protectedMediaRequiresExactOriginEvenForAllowedLoginHosts() {
        assertTrue(MediaSitePolicy.grantsProtectedMedia(MediaSite.NETFLIX,
            "https://www.netflix.com/watch/1", "https://www.netflix.com:443"))
        assertFalse(MediaSitePolicy.grantsProtectedMedia(MediaSite.NETFLIX,
            "https://www.netflix.com/watch/1", "https://help.netflix.com"))
        assertFalse(MediaSitePolicy.grantsProtectedMedia(MediaSite.NETFLIX,
            "https://www.netflix.com/watch/1", "https://evil.test"))
        assertFalse(MediaSitePolicy.grantsProtectedMedia(MediaSite.NETFLIX, null, "https://www.netflix.com"))
    }
    @Test fun desktopCompatibilityRetainsCurrentChromiumVersion() {
        val agent = "Mozilla/5.0 (Linux; Android 15; Phone; wv) AppleWebKit/537.36 Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"
        val desktop = MediaSitePolicy.userAgent(MediaSite.NETFLIX, agent)
        assertTrue(desktop.contains("Chrome/140.0.0.0"))
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains(" Mobile"))
        assertEquals(agent, MediaSitePolicy.userAgent(MediaSite.YOUTUBE, agent))
    }
}

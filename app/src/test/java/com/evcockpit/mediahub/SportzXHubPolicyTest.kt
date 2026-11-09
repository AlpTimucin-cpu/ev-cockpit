package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

class SportzXHubPolicyTest {
    @Test fun siteAllowsOnlyItsHttpsHosts() {
        assertTrue(SportzXSitePolicy.allow("https://sportzxlive.com/"))
        assertTrue(SportzXSitePolicy.allow("https://www.sportzxlive.com/?page=2"))
        assertFalse(SportzXSitePolicy.allow("http://sportzxlive.com/"))
        assertFalse(SportzXSitePolicy.allow("https://sportzxlive.com.evil.test/"))
        assertFalse(SportzXSitePolicy.allow("https://evil.test/path"))
        assertFalse(SportzXSitePolicy.allow("intent://sportzxlive.com"))
        assertFalse(SportzXSitePolicy.allow("https://person@www.sportzxlive.com/"))
    }

    @Test fun filmAndSeriesAlwaysHaveVisibleOfficialCardsWithoutM3u() {
        val films=OfficialMediaDirectory.list(VideoLibraryKind.MOVIE)
        val series=OfficialMediaDirectory.list(VideoLibraryKind.SERIES)
        assertTrue(films.any { it.mode == OfficialMediaMode.OPEN_MOVIE })
        assertTrue(series.isNotEmpty())
        assertTrue(films.any { it.id == "wing_it" })
        assertTrue(series.any { it.id == "caminandes_all" })
        assertTrue(OfficialMediaDirectory.list(VideoLibraryKind.MOVIE, "WING IT")
            .any { it.id == "wing_it" })
    }
}

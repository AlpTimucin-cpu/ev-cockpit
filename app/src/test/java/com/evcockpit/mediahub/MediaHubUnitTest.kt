package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

/** Runs as part of :app:testDebugUnitTest on a proper Android/Gradle builder. */
class MediaHubUnitTest {
    @Test fun cockpitPlannerKeepsRecentOrderDropsMissingAndNeverExposesUrls() {
        val tv = listOf(
            VideoChannel("v1", "Haber HD", "https://example.org/private?key=x", "Haber",
                true, "", "haber.tr"),
            VideoChannel("v2", "Belgesel", "https://example.org/doc.m3u8", "Kultur",
                false, "", "doc.tr")
        )
        val radio = listOf(
            Station("r1", "Caz FM", "https://radio.example/aac?token=secret",
                "Muzik", true),
            Station("r2", "Pop Radyo", "https://radio.example/mp3",
                "Muzik", false)
        )
        val summary = CockpitPlanner.build(
            listOf("tv:deleted", "radio:r1", "tv:v1", "radio:r1", "unknown:id",
                "tv:v2", "radio:r2"), tv, radio)
        assertEquals(listOf("r1", "v1", "v2", "r2"), summary.recents.map { it.id })
        assertEquals(listOf("v1", "r1"), summary.favourites.map { it.id })
        assertEquals("haber.tr", summary.recents.first { it.id == "v1" }.tvgId)
        assertEquals(2, summary.tvCount)
        assertEquals(2, summary.radioCount)
        assertFalse(summary.toString().contains("secret"))
        assertFalse(summary.toString().contains("private?key"))
        assertTrue(CockpitPlanner.build(emptyList(), tv, radio, recentLimit = 0).recents.isEmpty())
        assertEquals(1, CockpitPlanner.build(
            listOf("radio:r1", "tv:v1"), tv, radio, recentLimit = 1
        ).recents.size)
    }

    @Test fun videoCatalogSeparatesMoviesSeriesSportsAndLiveTv() {
        val videos = listOf(
            VideoChannel("film","Uzun Film","https://media.example/film.m3u8",
                "Filmler / Aksiyon",true),
            VideoChannel("dizi","Örnek Dizi S01E05","https://media.example/episode.mp4",
                "Diziler / Dram",false),
            VideoChannel("mac","Canlı karşılaşma","https://media.example/live.m3u8",
                "Spor / Maçlar",true),
            VideoChannel("tv","Haber","https://media.example/news.m3u8",
                "Haber",false),
            VideoChannel("episode","Öykü S02E11","https://media.example/ep.mp4",
                "Diğer",false)
        )
        assertEquals(VideoLibraryKind.MOVIE,VideoLibraryClassifier.classify(videos[0]))
        assertEquals(VideoLibraryKind.SERIES,VideoLibraryClassifier.classify(videos[1]))
        assertEquals(VideoLibraryKind.SPORT,VideoLibraryClassifier.classify(videos[2]))
        assertEquals(VideoLibraryKind.OTHER,VideoLibraryClassifier.classify(videos[3]))
        assertEquals(VideoLibraryKind.SERIES,VideoLibraryClassifier.classify(videos[4]))
        assertEquals(listOf("mac"),VideoLibraryClassifier.filtered(
            videos,VideoLibraryKind.SPORT,"KARŞILAŞMA",true).map { it.id })
        assertEquals(0,VideoLibraryClassifier.filtered(
            videos,VideoLibraryKind.MOVIE,"not-found").size)
        assertEquals(listOf("film"),VideoLibraryClassifier.filtered(
            videos,VideoLibraryKind.MOVIE,"aksiyon",true).map { it.id })
    }

    @Test fun videoCategoryDoesNotLeakIntoUnrelatedWords() {
        val normal = VideoChannel("v","Transport Haber","https://media.example/news.m3u8",
            "Transportation",false)
        assertEquals(VideoLibraryKind.OTHER,VideoLibraryClassifier.classify(normal))
        assertEquals(VideoLibraryKind.SPORT,
            VideoLibraryClassifier.classify(normal.copy(group="FUTBOL / Süper Lig")))
        assertEquals(VideoLibraryKind.MOVIE,
            VideoLibraryClassifier.classify(normal.copy(group="Sinema")))
    }

    @Test fun mediaengineTvGroupsRespectRealPlaylistCountsAndFavoriteFiltering() {
        val entries=listOf(
            VideoChannel("1","TRT 1","https://example.com/1.m3u8","ULUSAL",true),
            VideoChannel("2","TRT Haber","https://example.com/2.m3u8","HABER",false),
            VideoChannel("3","Haber 24","https://example.com/3.m3u8","HABER",true),
            VideoChannel("4","NT","https://example.com/4.m3u8","",false)
        )
        assertEquals(listOf("HABER","Diğer","ULUSAL"),
            MediaEngineTvPlanner.categories(entries).map{it.title})
        assertEquals(2,MediaEngineTvPlanner.categories(entries).first().count)
        assertEquals(listOf("3"),MediaEngineTvPlanner.list(entries,"HABER","haber",true).map{it.id})
        assertEquals(listOf("1"),MediaEngineTvPlanner.list(entries,null,"TRT 1",false).map{it.id})
        assertTrue(MediaEngineTvPlanner.categories(emptyList()).isEmpty())
    }

    @Test fun userPodcastRssAndOpmlParseOnlyHttpsEnclosures() {
        val rss = """
            <rss><channel><item><title>Birinci Bölüm</title>
            <enclosure url="https://audio.example/part1.mp3" type="audio/mpeg"/>
            </item><item><title>Geçersiz</title>
            <enclosure url="http://example.com/part2.mp3" /></item></channel></rss>
        """.trimIndent().toByteArray()
        val episodes=PodcastSources.parseRss(rss,"Örnek")
        assertEquals(1,episodes.size)
        assertEquals("Birinci Bölüm",episodes[0].title)
        val opml = """
            <opml version="2.0"><body>
            <outline text="Örnek RSS" xmlUrl="https://rss.example/feed.xml"/>
            <outline text="HTTP" xmlUrl="http://rss.example/feed.xml"/>
            </body></opml>
        """.trimIndent().toByteArray()
        assertEquals(listOf("Örnek RSS"),PodcastSources.parseOpml(opml).map{it.title})
        val exported=PodcastSources.exportOpml(listOf(PodcastFeed("News &amp;", "https://pod.example/a?x=1&amp;y=2")))
        assertTrue(exported.contains("&amp;"))
        assertFalse(exported.contains("http://"))
        assertTrue(PodcastSources.parseRss(
            "<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>"
                .toByteArray(), "X").isEmpty())
    }

    @Test fun officialDiscoveryIsNonEmptyForFilmsShowsAndSports() {
        listOf(VideoLibraryKind.MOVIE,VideoLibraryKind.SERIES,VideoLibraryKind.SPORT)
            .forEach { type ->
                assertTrue("Missing official provider choices for " + type,
                    OfficialMediaDirectory.list(type).isNotEmpty())
            }
        val movies=OfficialMediaDirectory.list(VideoLibraryKind.MOVIE)
        assertTrue(movies.any { it.mode == OfficialMediaMode.OPEN_MOVIE &&
            it.provider == "Blender Open Movies" })
        val shows=OfficialMediaDirectory.list(VideoLibraryKind.SERIES)
        assertTrue(shows.any { it.mode == OfficialMediaMode.PROVIDER_APP &&
            it.target == OfficialMediaDirectory.TABII_PACKAGE })
        val sports=OfficialMediaDirectory.list(VideoLibraryKind.SPORT)
        assertTrue(sports.any { it.target == OfficialMediaDirectory.TRT_SPOR_PACKAGE })
        assertFalse(sports.any { it.mode == OfficialMediaMode.OPEN_MOVIE })
        assertEquals(1,OfficialMediaDirectory.list(VideoLibraryKind.MOVIE,
            "Big Buck").size)
        assertEquals(0,OfficialMediaDirectory.list(VideoLibraryKind.SPORT,
            "Big Buck").size)
    }

    @Test fun paidProvidersHaveNoFakeDirectPremiumVideoLinks() {
        OfficialMediaDirectory.cards.forEach { provider ->
            assertFalse(provider.target.startsWith("http://"))
            if(provider.provider == "tabii" || provider.provider == "TRT") {
                assertNotEquals(OfficialMediaMode.OPEN_MOVIE,provider.mode)
                assertFalse(provider.target.endsWith(".m3u8"))
                assertFalse(provider.target.endsWith(".mpd"))
            }
        }
    }

    @Test fun integrationCatalogIsUniqueAndCoversAllPreviousAppResearch() {
        val sources = IntegrationCatalog.sources
        val ids = sources.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.size >= 12)
        val required = listOf("aptv", "media3", "web", "screenonauto", "fermata",
            "screen2auto", "shizuku", "aaad", "mirrormobile", "carstream", "sportzx", "diplay", "androidauto")
        required.forEach { assertNotNull("Missing source: " + it, IntegrationCatalog.byId(it)) }
        assertEquals(IntegrationKind.NATIVE, IntegrationCatalog.byId("aptv")?.kind)
        assertEquals(IntegrationKind.EXTERNAL, IntegrationCatalog.byId("screenonauto")?.kind)
        assertEquals(IntegrationKind.REFERENCE, IntegrationCatalog.byId("mirrormobile")?.kind)
        assertTrue(IntegrationCatalog.byId("screen2auto")!!.packages.contains(
            "android.loandamaps.it.aaad_157ff3"))
        assertTrue(IntegrationCatalog.byId("screenonauto")!!.packages.contains(
            "idv.lzn.screenonauto"))
        assertTrue(IntegrationCatalog.byId("web")!!.packages.contains("com.kododake.aabrowser"))
    }

    @Test fun driveDashboardRecentOrderDeduplicatesAndIgnoresStaleIds() {
        val entries = DriveHomePlanner.recent(
            listOf("tv:one", "radio:r2", "tv:one", "tv:missing", "unknown:x",
                "radio:r1", "badentry", "tv:two"),
            mapOf("one" to "Haber", "two" to "Sinema"),
            mapOf("r1" to "Müzik", "r2" to "Radyo 2"),
            4
        )
        assertEquals(listOf("Haber", "Radyo 2", "Müzik", "Sinema"),
            entries.map { it.title })
        assertEquals(listOf("tv", "radio", "radio", "tv"),
            entries.map { it.kind })
        assertEquals(emptyList<DriveRecentItem>(), DriveHomePlanner.recent(
            listOf("tv:one"), mapOf("one" to "Haber"), emptyMap(), 0))
        assertEquals(emptyList<DriveRecentItem>(), DriveHomePlanner.recent(
            listOf("tv:unknown"), emptyMap(), emptyMap()))
    }

    @Test fun m3uAdvertisedEpgRequiresHttpsAndExplicitApproval() {
        val playlist = """#EXTM3U x-tvg-url="http://unsafe.example/epg.xml,https://guide.example/epg.xml.gz"
#EXTINF:-1 tvg-id="trt.tr" group-title="TV",TRT
https://tv.example/stream.m3u8
"""
        assertEquals("https://guide.example/epg.xml.gz",
            M3uParser.suggestedGuideUrl(playlist))
        assertNull(M3uParser.suggestedGuideUrl(
            "#EXTM3U url-tvg=\"http://insecure.example/guide.xml\""))
        assertEquals("https://example.org/guide.xml",
            M3uParser.suggestedGuideUrl(
                "\uFEFF#EXTM3U url-tvg=\"https://example.org/guide.xml\"\n#EXTINF:-1,A"))
        assertNull(M3uParser.suggestedGuideUrl("#EXTINF:-1,Missing header"))
    }

    @Test fun epgIndexNormalizesIdsFindsNowNextAndProgress() {
        val now = 10_000L
        val programmes = listOf(
            EpgProgramme(" TRT1.TR ", "Eski", 7_000L, 9_000L),
            EpgProgramme(" TRT1.TR ", "Gündem", 9_000L, 11_000L),
            EpgProgramme("trt1.tr", "Yeni Haberler", 11_000L, 13_000L),
            EpgProgramme("OTHER.TR", "Sinema", 8_000L, 12_000L),
            EpgProgramme("TRT1.TR", "Hatalı", 8_000L, 7_000L)
        )
        val index = EpgScheduleIndex(programmes, now)
        assertEquals("Gündem", index.current("trt1.tr")?.title)
        assertEquals("Yeni Haberler", index.next(" TRT1.TR ")?.title)
        assertEquals(50, index.progressPercent("trt1.tr"))
        assertTrue(index.hasCurrent("trt1.tr"))
        assertTrue(index.matchingProgramme("trt1.tr", "gündem"))
        assertTrue(index.matchingProgramme("trt1.tr", "yeni haberler"))
        assertFalse(index.matchingProgramme("trt1.tr", "spor"))
        assertFalse(index.hasCurrent("missing"))
        assertEquals(0, index.progressPercent("missing"))
    }

    @Test fun fermataXManifestInteropIsStrictlyHttpsAndExplicitlyScoped() {
        assertEquals("me.app.fermataX.auto.debug", FermataXInterop.DEBUG_PACKAGE)
        assertEquals("me.aap.fermata.ui.activity.MainActivity",
            FermataXInterop.EXPORTED_ACTIVITY)
        assertTrue(FermataXInterop.canShare("https://media.example.org/live/channel.m3u8"))
        assertTrue(FermataXInterop.canShare(
            "https://media.example.org/stream?access_token=not-logged"))
        assertFalse(FermataXInterop.canShare("http://media.example.org/live.mp4"))
        assertFalse(FermataXInterop.canShare("file:///storage/emulated/0/video.mp4"))
        assertFalse(FermataXInterop.canShare("content://provider/movie"))
        assertFalse(FermataXInterop.canShare(
            "https://user:password@media.example.org/live.m3u8"))
        assertFalse(FermataXInterop.canShare("fermata://play"))
        assertFalse(FermataXInterop.canShare(""))
    }

    @Test fun uploadedFermataXDebugPackageIsRecognized() {
        val source = IntegrationCatalog.byId("fermatax")
        assertNotNull(source)
        assertEquals(IntegrationKind.EXTERNAL, source!!.kind)
        assertTrue(source.packages.contains("me.app.fermataX.auto.debug"))
        assertTrue(source.packages.contains("me.app.fermataX.auto"))
    }

    @Test fun explicitHlsDashAndUnknownFormatsAreClassified() {
        assertEquals(StreamFormat.HLS, StreamFormatDetector.detect(
            "https://example.org/live.m3u8?token=not-logged"))
        assertEquals(StreamFormat.DASH, StreamFormatDetector.detect(
            "https://example.org/manifest.MPD?quality=hd"))
        assertEquals(StreamFormat.AUTO, StreamFormatDetector.detect(
            "https://example.org/video.mp4"))
        assertEquals(StreamFormat.AUTO, StreamFormatDetector.detect(
            "https://example.org/opaque-stream"))
    }

    @Test fun pagesAreBoundedAndOverflowSafe() {
        val items = (0 until 2500).toList()
        assertEquals((40 until 60).toList(), LibraryPage.slice(items, 2, 20))
        assertEquals((2480 until 2500).toList(), LibraryPage.slice(items, 124, 20))
        assertTrue(LibraryPage.slice(items, 125, 20).isEmpty())
        assertTrue(LibraryPage.slice(items, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(LibraryPage.slice(items, -1, 20).isEmpty())
    }

    @Test fun radioQueueIsNeverUnbounded() {
        val stations = (0 until 2500).map {
            RadioSource("radio-$it", "Radio $it", "https://audio.example.org/$it.mp3")
        }
        val plan = RadioQueuePlanner.plan(stations, listOf(RadioRequest(id = "radio-2400")), 0)
        assertNotNull(plan)
        assertTrue(plan!!.ids.size <= RadioQueuePlanner.MAX_QUEUE_ITEMS)
        assertEquals("radio-2400", plan.ids[plan.index])
    }

    @Test fun xmltvEpgMatchesM3uTvgId() {
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"trt1.tr\" group-title=\"Ulusal\",TRT 1\nhttps://example.org/tv.m3u8"
        val entry = M3uParser.parse(playlist).single()
        assertEquals("trt1.tr", entry.tvgId)
        val exported = M3uExporter.export(listOf(entry))
        assertTrue(exported.contains("tvg-id=\"trt1.tr\""))
        val imported = M3uParser.parse(exported).single()
        assertEquals(entry.tvgId, imported.tvgId)
        val xml = """<tv><programme channel="trt1.tr" start="20261012090000 +0000" stop="20261012120000 +0000"><title>Sabah Haberleri</title></programme></tv>"""
        val now = 1_760_000_000_000L
        val shows = EpgGuide.parse(xml.toByteArray(Charsets.UTF_8), now)
        // The input is outside this synthetic clock's 3-day window and must be rejected.
        assertTrue(shows.isEmpty())
        val validNow = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
            .parse("20261012100000 +0000")!!.time
        val valid = EpgGuide.parse(xml.toByteArray(Charsets.UTF_8), validNow)
        assertEquals(1, valid.size)
        assertEquals("Sabah Haberleri", EpgGuide.current(valid, imported.tvgId, imported.name, validNow)?.title)
        assertNull(EpgGuide.current(valid, "wrong-id", imported.name, validNow))
        assertNull(EpgGuide.current(valid, "", imported.name, validNow))
    }

    @Test fun xmltvRejectsDocTypeAndExternalEntities() {
        val unsafe = """<!DOCTYPE tv [<!ENTITY secret SYSTEM "file:///etc/passwd">]><tv><programme channel="x" start="20261012090000 +0000" stop="20261012120000 +0000"><title>&secret;</title></programme></tv>"""
        try {
            EpgGuide.parse(unsafe.toByteArray(Charsets.UTF_8))
            fail("DTD must not be parsed")
        } catch (_: Exception) { /* Expected */ }
    }

    @Test fun m3uRejectsNonHttpsAndVideoDoesNotBecomeAudio() {
        val playlist = """#EXTM3U
#EXTINF:-1 group-title="Radio" radio="true",Music
https://audio.example.org/music.mp3
#EXTINF:-1 group-title="Sports",Match
https://tv.example.org/match.m3u8
#EXTINF:-1,Insecure
http://invalid.example.org/test.mp3
"""
        val parsed = M3uParser.parse(playlist)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isAudio)
        assertFalse(parsed[1].isAudio)
    }


    @Test fun quotedUserAgentCommaDoesNotCorruptChannelNames() {
        val playlist = """#EXTM3U
#EXTINF:-1 tvg-logo="https://img.example.org/a.png" user-agent="Mozilla/5.0 (KHTML, like Gecko) Chrome/130" group-title="Movies",beIN Movies Turk
https://video.example.org/bein.m3u8
#EXTINF:-1 group-title="News" tvg-name="Haber 24",Ignored Label
https://video.example.org/news.m3u8
"""
        val channels = M3uParser.parse(playlist)
        assertEquals(2, channels.size)
        assertEquals("beIN Movies Turk", channels[0].name)
        assertEquals("Movies", channels[0].group)
        assertEquals("https://img.example.org/a.png", channels[0].logo)
        assertEquals("Haber 24", channels[1].name)
    }

    @Test fun pollutedStoredNamesAreCleanedWithoutDeletingRecords() {
        assertEquals("beIN Movies Turk", M3uParser.displayTitle(
            "like Gecko) Chrome/130.0.0 Safari/537.36\" group-title=\"Movies\",beIN Movies Turk"
        ))
        assertEquals("TRT Haber", M3uParser.displayTitle("TRT Haber"))
    }

    @Test fun probeDoesNotConfuseHeadFailureWithStreamFailure() {
        assertEquals(StreamStatus.HEAD_UNSUPPORTED, StreamProbe.classify(405).status)
        assertEquals(StreamStatus.LOGIN_REQUIRED, StreamProbe.classify(403).status)
        assertEquals(StreamStatus.SERVER_RESPONDS, StreamProbe.classify(200).status)
    }
    @Test fun youtubeHistoryCanonicalizesVideoIdentityWithoutTrackingParams() {
        val video = "dQw4w9WgXcQ"
        val canonical = "https://www.youtube.com/watch?v=" + video
        listOf(
            "https://www.youtube.com/watch?v=$video&t=85s&si=TRACK",
            "https://m.youtube.com/watch?v=$video&feature=share",
            "https://youtu.be/$video?si=TRACK",
            "https://www.youtube.com/shorts/$video?feature=share",
            "https://youtube.com/live/$video?si=TRACK",
            "https://www.youtube.com/embed/$video?autoplay=1",
            "https://YOUTUBE.COM/watch?v=$video&list=LIST"
        ).forEach {
            assertEquals("Unexpected YouTube normalization: " + it,
                canonical, YoutubePagePolicy.canonicalWatchUrl(it))
        }
    }

    @Test fun youtubeHistoryRejectsWrongHostsCredentialsAndOtherSchemes() {
        listOf(
            "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://youtube.com@evil.example/watch?v=dQw4w9WgXcQ",
            "https://user:pass@www.youtube.com/watch?v=dQw4w9WgXcQ",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "javascript:alert(1)",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/results?search_query=car",
            "https://youtu.be/invalid",
            "https://www.youtube.com:444/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ%0A",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ\n",
            "https://youtu.be/dQw4w9WgXcQ/extra",
            ""
        ).forEach { assertNull(it, YoutubePagePolicy.canonicalWatchUrl(it)) }
    }

    @Test fun mediaengineSubscriptionsSupportBothSitesWithoutAllowingPhishingOrHttp() {
        for (service in StreamingService.values()) {
            assertTrue(SubscriptionPortalPolicy.isAllowed(service,
                SubscriptionPortalPolicy.homepage(service)))
            assertTrue(SubscriptionPortalPolicy.isAllowed(service,
                SubscriptionPortalPolicy.search(service, "Dizi adı").orEmpty()))
            assertNull(SubscriptionPortalPolicy.search(service, "  "))
            assertFalse(SubscriptionPortalPolicy.isAllowed(service,
                "http://www.netflix.com/title/80192098"))
            assertFalse(SubscriptionPortalPolicy.isAllowed(service,
                "https://evil.example.com/?url=www.netflix.com"))
            assertFalse(SubscriptionPortalPolicy.isAllowed(service,
                "https://user:secret@www.primevideo.com/"))
            assertFalse(SubscriptionPortalPolicy.isAllowed(service,
                "https://www.netflix.com.evil.example/title/80192098"))
            assertFalse(SubscriptionPortalPolicy.isAllowed(service, "javascript:alert(1)"))
        }
        assertTrue(SubscriptionPortalPolicy.isAllowed(
            StreamingService.NETFLIX, "https://www.netflix.com/tr/title/80192098"))
        assertTrue(SubscriptionPortalPolicy.isAllowed(
            StreamingService.PRIME_VIDEO, "https://www.amazon.com.tr/ap/signin"))
        assertTrue(SubscriptionPortalPolicy.isAllowed(
            StreamingService.PRIME_VIDEO,
            "https://www.primevideo.com/-/tr/detail/B0XYZABCDE"))
        assertFalse(SubscriptionPortalPolicy.isAllowed(
            StreamingService.NETFLIX, "https://www.primevideo.com/"))
        assertFalse(SubscriptionPortalPolicy.isAllowed(
            StreamingService.PRIME_VIDEO, "https://www.netflix.com/"))
        assertTrue(SubscriptionPortalPolicy.search(
            StreamingService.NETFLIX,"film ismi")!!.contains("film+ismi"))
    }

    @Test fun netflixLocalePageHistoryAvoidsSearchQueryAndTracking() {
        assertEquals("https://www.netflix.com/title/80192098",
            StreamingPagePolicy.canonical(
                "https://www.netflix.com/tr/title/80192098?trackId=987")?.url)
        assertEquals("https://www.netflix.com/watch/80192098",
            StreamingPagePolicy.canonical(
                "https://netflix.com/tr-TR/watch/80192098?auth=bad")?.url)
        assertNull(StreamingPagePolicy.canonical(
            "https://www.netflix.com/tr/login?email=private"))
        assertNull(StreamingPagePolicy.canonical(
            "https://www.netflix.com/search?q=private"))
    }

    @Test fun tmdbWatchProviderRoutesStayOnOfficialPathsAndTrRegion() {
        assertEquals("movie/550/watch/providers",
            TmdbWatchPolicy.apiPath(TmdbKind.MOVIE,550L))
        assertEquals("tv/1396/watch/providers",
            TmdbWatchPolicy.apiPath(TmdbKind.SERIES,1396L))
        assertEquals("https://www.themoviedb.org/movie/550/watch?locale=TR",
            TmdbWatchPolicy.watchPage(TmdbKind.MOVIE,550L))
        assertEquals("https://www.themoviedb.org/tv/1396/watch?locale=TR",
            TmdbWatchPolicy.watchPage(TmdbKind.SERIES,1396L))
        listOf(-1L,0L,2147483648L).forEach { invalid ->
            try {
                TmdbWatchPolicy.apiPath(TmdbKind.MOVIE,invalid)
                fail("Invalid id must fail: " + invalid)
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun streamingHistoryRecognizesNetflixPagesAndStripsTracking() {
        val cases = listOf(
            "https://www.netflix.com/watch/80192098?trackId=123&jbv=80192098" to
                "https://www.netflix.com/watch/80192098",
            "https://netflix.com/title/70143836?fromSearch=true#details" to
                "https://www.netflix.com/title/70143836"
        )
        for ((input, expected) in cases) {
            val parsed = StreamingPagePolicy.canonical(input)
            assertEquals(StreamingService.NETFLIX, parsed?.service)
            assertEquals(expected, parsed?.url)
        }
    }

    @Test fun streamingHistoryRecognizesPrimeVideoAndRegionalAmazonDetail() {
        val cases = listOf(
            "https://www.primevideo.com/detail/0QXYZABC12345?ref_=atv_dp" to
                "https://www.primevideo.com/detail/0QXYZABC12345",
            "https://primevideo.com/-/tr/detail/B0XYZABCDE?language=tr_TR" to
                "https://www.primevideo.com/detail/B0XYZABCDE",
            "https://www.amazon.com/gp/video/detail/B012345678?ref_=abc" to
                "https://www.amazon.com/gp/video/detail/B012345678",
            "https://www.amazon.com.tr/gp/video/detail/B012345678?tag=sample" to
                "https://www.amazon.com.tr/gp/video/detail/B012345678"
        )
        for ((input, expected) in cases) {
            val parsed = StreamingPagePolicy.canonical(input)
            assertEquals(StreamingService.PRIME_VIDEO, parsed?.service)
            assertEquals(expected, parsed?.url)
        }
    }

    @Test fun streamingHistoryRejectsCredentialLinksAndOtherSites() {
        val invalid = listOf(
            "https://www.netflix.com.evil.example/watch/80192098",
            "https://www.primevideo.com.evil.example/detail/0QXYZABC12345",
            "https://user:pass@www.netflix.com/watch/80192098",
            "https://www.netflix.com@evil.example/watch/80192098",
            "https://www.primevideo.com:444/detail/0QXYZABC12345",
            "http://www.netflix.com/watch/80192098",
            "file:///android_asset/private",
            "https://www.netflix.com/login?auth=secret",
            "https://www.primevideo.com/region?token=secret",
            "https://www.netflix.com/watch/80192098%0A",
            "https://www.primevideo.com/detail/abcd\nxyz",
            "https://www.amazon.com/gp/video/detail/x",
            "https://www.primevideo.com/detail/zz?token=secret"
        )
        invalid.forEach { assertNull("Should reject: " + it, StreamingPagePolicy.canonical(it)) }
        assertTrue(StreamingPagePolicy.isSubscriptionHost("https://www.netflix.com/login"))
        assertTrue(StreamingPagePolicy.isSubscriptionHost("https://www.primevideo.com/"))
        assertFalse(StreamingPagePolicy.isSubscriptionHost("https://www.netflix.com.evil.example/"))
    }

    @Test fun recTvIsAnExternalAppNeverPartOfNativeVideoStack() {
        val source = IntegrationCatalog.byId("rectv")
        assertEquals(IntegrationKind.EXTERNAL, source?.kind)
        assertEquals(listOf("com.rectv.shot"), source?.packages)
        assertTrue(IntegrationCatalog.byId("media3")?.kind == IntegrationKind.NATIVE)
    }

    @Test fun webLinksRejectUntrustedSchemesAndCredentials() {
        assertEquals("https://www.example.com/watch?id=15",
            BrowserStore.safeHttps(" https://www.example.com/watch?id=15 "))
        assertNull(BrowserStore.safeHttps("http://www.example.com/video"))
        assertNull(BrowserStore.safeHttps("javascript:alert(1)"))
        assertNull(BrowserStore.safeHttps("file:///etc/passwd"))
        assertNull(BrowserStore.safeHttps("https://user:password@example.com"))
        assertNull(BrowserStore.safeHttps("https://bad host.example"))
        assertNull(BrowserStore.safeHttps("https://"))
    }

    @Test fun globalIptvDirectoryGeneratesOnlyCountryListUrls() {
        assertEquals("https://iptv-org.github.io/iptv/countries/tr.m3u",
            WorldCatalog.countryCodeUrl("TR"))
        assertTrue(WorldCatalog.countries().any { it.url.endsWith("/tr.m3u") })
        assertTrue(WorldCatalog.genres.size >= 6)
        try {
            WorldCatalog.countryCodeUrl("../../etc")
            fail("Country codes must be exactly two ISO letters")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid country input blocked.
        }
    }

    @Test fun carLibraryClientIsClassifiedWithoutLeakingPackageNames() {
        assertEquals("android_auto", CarConnectionJournal.classifyClient(
            "com.google.android.projection.gearhead", "com.evcockpit.mediahub"))
        assertEquals("phone", CarConnectionJournal.classifyClient(
            "com.evcockpit.mediahub", "com.evcockpit.mediahub"))
        assertEquals("other_browser", CarConnectionJournal.classifyClient(
            "com.example.some.media.client", "com.evcockpit.mediahub"))
    }

    @Test fun playbackReportTracksBufferingAndFirstFrameWithoutUrls() {
        val health = PlaybackDiagnostics()
        health.start(1000L)
        health.buffering(1020L)
        health.buffering(1030L) // Duplicate state must not add a new stall.
        health.ready(2520L)
        health.firstFrame(2600L)
        health.firstFrame(2700L) // First frame remains the first one.
        health.videoSize(1920, 1080)
        health.videoDecoder("c2.qti.avc.decoder")
        health.dropped(3)
        health.dropped(-5)
        health.retry()
        health.problem("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")
        val report = health.report(4000L)
        assertTrue(report.contains("1600 ms"))
        assertTrue(report.contains("1 kez, 1.5 sn"))
        assertTrue(report.contains("1920 × 1080"))
        assertTrue(report.contains("Düşen video kareleri: 3"))
        assertTrue(report.contains("Otomatik kurtarma denemeleri: 1"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("Bearer"))
    }

    @Test fun playbackMetricStateResetsForNextChannel() {
        val health = PlaybackDiagnostics()
        health.start(1L)
        health.dropped(11)
        health.videoDecoder("hw.decoder")
        health.start(20L)
        val report = health.report(100L)
        assertTrue(report.contains("Düşen video kareleri: 0"))
        assertTrue(report.contains("Video çözücü: Bilinmiyor"))
        assertTrue(report.contains("İlk görüntü: Henüz alınmadı"))
    }

    @Test fun transientRetriesAreLimitedAndNeverApplyToAuthErrors() {
        val budget = StreamRetryBudget()
        assertNull(budget.nextDelay(false))
        assertEquals(1500L, budget.nextDelay(true))
        assertEquals(4000L, budget.nextDelay(true))
        assertNull(budget.nextDelay(true))
        budget.reset()
        assertEquals(1500L, budget.nextDelay(true))
        assertEquals(1, budget.attempts)
    }

    @Test fun carBrowseReportUsesFolderTypeWithoutStationNames() {
        assertEquals("Tüm radyolar", CarConnectionJournal.folderLabel("evcockpit_audio_all"))
        assertEquals("Favori radyolar", CarConnectionJournal.folderLabel("evcockpit_audio_favorites"))
        assertEquals("Radyo grubu", CarConnectionJournal.folderLabel("evcockpit_group_Haber%20%26%20Sohbet"))
        assertEquals("Bilinmeyen klasör", CarConnectionJournal.folderLabel("https://secret.example/token"))
    }

}

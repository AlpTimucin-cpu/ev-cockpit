package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test

/** Source probing never logs or publishes a user-provided signed URL. */
class SourceHealthProbeTest {
    @Test fun separatesAccessExpiryAndServerIssues() {
        assertEquals(SourceCheckKind.ACCESS_DENIED,SourceHealthPolicy.classify(403,null).kind)
        assertEquals(SourceCheckKind.ACCESS_DENIED,SourceHealthPolicy.classify(401,null).kind)
        assertEquals(SourceCheckKind.NOT_FOUND,SourceHealthPolicy.classify(404,null).kind)
        assertEquals(SourceCheckKind.NOT_FOUND,SourceHealthPolicy.classify(410,null).kind)
        assertEquals(SourceCheckKind.THROTTLED,SourceHealthPolicy.classify(429,null).kind)
        assertEquals(SourceCheckKind.TEMPORARY,SourceHealthPolicy.classify(503,null).kind)
        assertEquals(SourceCheckKind.UNREACHABLE,SourceHealthPolicy.classify(null,null).kind)
    }

    @Test fun detectsHtmlAndJsonInsteadOfFilmMedia() {
        assertEquals(SourceCheckKind.BAD_FORMAT,
            SourceHealthPolicy.classify(200,"text/html; charset=utf-8","<html>login</html>").kind)
        assertEquals(SourceCheckKind.BAD_FORMAT,
            SourceHealthPolicy.classify(200,"application/json", "{\"error\":\"expired\"}").kind)
        assertEquals(SourceCheckKind.AVAILABLE,
            SourceHealthPolicy.classify(206,"video/mp4","ftyp").kind)
        assertEquals(SourceCheckKind.AVAILABLE,
            SourceHealthPolicy.classify(200,"application/octet-stream","#EXTM3U\n#EXTINF:1,seg").kind)
    }

    @Test fun reportsNeverEchoServerDataOrToken() {
        val secret="https://example.com/watch.m3u8?token=DO_NOT_LEAK"
        val result=SourceHealthPolicy.classify(403,"text/html",secret)
        assertFalse(result.description.contains("DO_NOT_LEAK"))
        assertFalse(result.description.contains("https://"))
        assertFalse(SourceHealthPolicy.classify(200,"text/html",secret).description.contains("token="))
    }
}

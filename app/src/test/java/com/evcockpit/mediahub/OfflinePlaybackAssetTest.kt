package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Regression: offline Media3 player test must ship an actual, valid MP4 asset. */
class OfflinePlaybackAssetTest {
    @Test fun localH264TestVideoExistsAndMatchesKnownBytes() {
        val asset = listOf(
            File("src/main/assets/evcockpit_selftest.mp4"),
            File("app/src/main/assets/evcockpit_selftest.mp4")
        ).firstOrNull { it.isFile } ?: error("Bundled offline test MP4 is missing")
        val bytes = asset.readBytes()
        assertEquals(2518, bytes.size)
        assertEquals("ftyp", bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        assertEquals("moov", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII).let {
            // In this MP4 the moov box follows an ISO brand header at offset 32.
            it
        })
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        assertEquals(
            "63e829c6b1737527c26d8b6a99d961416249d467eba904bf82e67a2668b8c4d1",
            sha
        )
    }
}

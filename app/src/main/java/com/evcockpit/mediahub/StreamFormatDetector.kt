package com.evcockpit.mediahub

/**
 * A small format classifier for authorized HTTPS sources. No DRM decryption,
 * screen capture or Android Auto driver state is involved.
 */
enum class StreamFormat { HLS, DASH, AUTO }

object StreamFormatDetector {
    fun detect(url: String): StreamFormat {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> StreamFormat.HLS
            path.endsWith(".mpd") -> StreamFormat.DASH
            else -> StreamFormat.AUTO
        }
    }
}

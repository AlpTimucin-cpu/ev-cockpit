package com.evcockpit.mediahub

/**
 * Privacy-safe IPTV playback counters for Media3; inspired by DiPlay's
 * diagnostic approach but independently implemented, not copied from it.
 * Never record URLs, DRM identifiers, credentials or packet contents.
 */
class PlaybackDiagnostics {
    private var startedAtMs = -1L
    private var bufferingSinceMs = -1L
    private var bufferedMs = 0L
    private var bufferingCount = 0
    private var firstFrameDelayMs = -1L
    private var droppedFrames = 0L
    private var processedVideoFrames = 0L
    private var decoderStartupMs = -1L
    private var decoder = "Bilinmiyor"
    private var width = 0
    private var height = 0
    private var lastIssue = "Yok"
    private var recoveryCount = 0

    fun start(nowMs: Long) {
        startedAtMs = nowMs
        bufferingSinceMs = -1L
        bufferedMs = 0L
        bufferingCount = 0
        firstFrameDelayMs = -1L
        droppedFrames = 0
        processedVideoFrames = 0
        decoderStartupMs = -1L
        decoder = "Bilinmiyor"
        width = 0
        height = 0
        lastIssue = "Yok"
        recoveryCount = 0
    }

    fun buffering(nowMs: Long) {
        if (startedAtMs < 0L || bufferingSinceMs >= 0L) return
        bufferingSinceMs = nowMs
        bufferingCount++
    }

    fun ready(nowMs: Long) {
        if (bufferingSinceMs >= 0L) {
            bufferedMs += (nowMs - bufferingSinceMs).coerceAtLeast(0L)
            bufferingSinceMs = -1L
        }
    }

    fun firstFrame(nowMs: Long) {
        if (startedAtMs >= 0L && firstFrameDelayMs == -1L)
            firstFrameDelayMs = (nowMs - startedAtMs).coerceAtLeast(0L)
    }

    fun videoDecoder(value: String, startupMs: Long = -1L) {
        decoder = value.take(90).ifBlank { "Bilinmiyor" }
        if (startupMs >= 0L) decoderStartupMs = startupMs
    }

    fun processedFrames(count: Int) {
        if (count > 0) processedVideoFrames += count.toLong()
    }

    fun videoSize(w: Int, h: Int) {
        if (w in 1..16384 && h in 1..16384) { width = w; height = h }
    }

    fun dropped(count: Int) {
        if (count > 0) droppedFrames += count.toLong()
    }

    fun problem(issue: String) { lastIssue = issue.take(80) }
    fun retry() { recoveryCount++ }

    fun report(nowMs: Long): String {
        val liveBuffer = if (bufferingSinceMs < 0L) 0L
            else (nowMs - bufferingSinceMs).coerceAtLeast(0L)
        val runtime = if (startedAtMs < 0L) 0L
            else (nowMs - startedAtMs).coerceAtLeast(0L)
        return buildString {
            appendLine("EV Cockpit | IPTV oynatma tanılama")
            appendLine("Oturum süresi: " + (runtime / 1000) + " sn")
            appendLine("İlk görüntü: " + if (firstFrameDelayMs < 0L)
                "Henüz alınmadı" else firstFrameDelayMs.toString() + " ms")
            appendLine("Tamponlama: " + bufferingCount + " kez, " +
                ((bufferedMs + liveBuffer) / 1000.0) + " sn")
            appendLine("Düşen video kareleri: " + droppedFrames)
            appendLine("Media3 işlenen kare sayısı: " + processedVideoFrames)
            val activeMs = if (firstFrameDelayMs < 0L) 0L
                else (runtime - firstFrameDelayMs).coerceAtLeast(0L)
            appendLine("Ortalama kare işleme hızı: " +
                if (activeMs < 1000L || processedVideoFrames == 0L) "Ölçülmedi"
                else ((processedVideoFrames * 1000L) / activeMs).toString() + " kare/sn (tahmini)")
            appendLine("Çözünürlük: " + if (width == 0)
                "Bilinmiyor" else width.toString() + " × " + height)
            appendLine("Video çözücü: " + decoder)
            appendLine("Video çözücü başlatma: " +
                if (decoderStartupMs < 0L) "Bilinmiyor" else decoderStartupMs.toString() + " ms")
            appendLine("Otomatik kurtarma denemeleri: " + recoveryCount)
            appendLine("Son hata türü: " + lastIssue)
            appendLine("Bu ölçümler Media3'e aittir; araçta video çalıştığını kanıtlamaz.")
        }
    }
}

/** Finite, user-cancellable retry schedule. Never retry auth/DRM errors. */
class StreamRetryBudget {
    private var used = 0
    private val backoffs = longArrayOf(1500L, 4000L)

    fun nextDelay(recoverable: Boolean): Long? {
        if (!recoverable || used >= backoffs.size) return null
        return backoffs[used++]
    }

    fun reset() { used = 0 }
    val attempts: Int get() = used
}

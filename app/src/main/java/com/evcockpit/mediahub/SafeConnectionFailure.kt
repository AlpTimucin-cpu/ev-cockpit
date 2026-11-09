package com.evcockpit.mediahub

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Privacy-preserving transport error classification.
 * Never include Throwable.message: it can contain an entire signed media URL.
 * Does NOT attempt to repair expired links, server authorization or DRM.
 */
enum class ConnectionIssueKind {
    DNS, TLS, TIMEOUT, CONNECTION_REFUSED, NETWORK_UNREACHABLE,
    CONNECTION_INTERRUPTED, CONNECTION_CLOSED, UNKNOWN
}

data class ConnectionIssue(val kind: ConnectionIssueKind, val explanation: String)

object SafeConnectionFailure {
    fun classify(problem: Throwable): ConnectionIssue {
        var cause: Throwable? = problem
        val visited = HashSet<Throwable>()
        repeat(16) {
            val current = cause ?: return unknown()
            if (!visited.add(current)) return unknown()
            val value = when (current) {
                is UnknownHostException -> ConnectionIssue(
                    ConnectionIssueKind.DNS,
                    "Sunucu adı DNS üzerinden çözümlenemedi. Farklı ağ veya DNS ile dene.")
                is SSLHandshakeException -> ConnectionIssue(
                    ConnectionIssueKind.TLS,
                    "Güvenli HTTPS bağlantısı kurulamadı (TLS/sertifika).")
                is SSLException -> ConnectionIssue(
                    ConnectionIssueKind.TLS,
                    "HTTPS güvenli iletişim hatası.")
                is SocketTimeoutException -> ConnectionIssue(
                    ConnectionIssueKind.TIMEOUT,
                    "Yayın sunucusuyla bağlantı zaman aşımına uğradı.")
                is NoRouteToHostException -> ConnectionIssue(
                    ConnectionIssueKind.NETWORK_UNREACHABLE,
                    "Sunucunun ağ yoluna erişilemiyor.")
                is ConnectException -> ConnectionIssue(
                    ConnectionIssueKind.CONNECTION_REFUSED,
                    "Sunucu bağlantısı kurulamadı veya reddedildi.")
                is EOFException -> ConnectionIssue(
                    ConnectionIssueKind.CONNECTION_CLOSED,
                    "Sunucu bağlantıyı veri tamamlanmadan kapattı.")
                is SocketException -> ConnectionIssue(
                    ConnectionIssueKind.CONNECTION_INTERRUPTED,
                    "Ağ bağlantısı sunucu veya ağ tarafından kesildi.")
                is InterruptedIOException -> ConnectionIssue(
                    ConnectionIssueKind.TIMEOUT,
                    "Ağ isteği kesildi veya zaman aşımına uğradı.")
                else -> null
            }
            if (value != null) return value
            cause = current.cause
        }
        return unknown()
    }

    private fun unknown() = ConnectionIssue(
        ConnectionIssueKind.UNKNOWN,
        "HTTPS bağlantı hatası oluştu, kesin ağ nedeni belirlenemedi."
    )
}

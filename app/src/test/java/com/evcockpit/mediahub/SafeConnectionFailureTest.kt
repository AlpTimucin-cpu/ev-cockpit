package com.evcockpit.mediahub

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SafeConnectionFailureTest {
    @Test fun differentiatesDnsTlsTimeoutAndSocketWithoutRawData() {
        val dns = SafeConnectionFailure.classify(
            RuntimeException("wrapper", UnknownHostException("sensitive.example?token=PRIVATE")))
        assertEquals(ConnectionIssueKind.DNS, dns.kind)
        assertFalse(dns.explanation.contains("PRIVATE"))
        assertFalse(dns.explanation.contains("sensitive.example"))

        val tls = SafeConnectionFailure.classify(
            RuntimeException("root", SSLHandshakeException("secret signed url")))
        assertEquals(ConnectionIssueKind.TLS, tls.kind)
        assertFalse(tls.explanation.contains("secret"))

        val timeout = SafeConnectionFailure.classify(
            SocketTimeoutException("https://private.example/media.m3u8?token=secret"))
        assertEquals(ConnectionIssueKind.TIMEOUT, timeout.kind)
        assertFalse(timeout.explanation.contains("https://"))

        assertEquals(ConnectionIssueKind.CONNECTION_REFUSED,
            SafeConnectionFailure.classify(ConnectException("private")).kind)
        assertEquals(ConnectionIssueKind.CONNECTION_CLOSED,
            SafeConnectionFailure.classify(EOFException("private")).kind)
    }

    @Test fun unknownExceptionsRemainPrivateAndBounded() {
        val issue = SafeConnectionFailure.classify(RuntimeException(
            "Bearer PrivateToken", IllegalArgumentException("private signed URL")))
        assertEquals(ConnectionIssueKind.UNKNOWN, issue.kind)
        assertFalse(issue.explanation.contains("PrivateToken"))
    }
}

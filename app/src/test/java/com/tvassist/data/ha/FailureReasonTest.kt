package com.tvassist.data.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Tests for turning a connection exception into something a person can act on.
 *
 * Raw JSSE text ("Trust anchor for certification path not found") tells a TV user nothing about the
 * setting that would fix it. These assertions keep the mapping — and the pointer to the Verify
 * certificate toggle — from quietly regressing into stack-trace text.
 */
class FailureReasonTest {

    private val client = HaWebSocketClient()
    private fun reason(t: Throwable) = client.failureReason(t)

    @Test fun `an untrusted certificate names the setting that fixes it`() {
        val msg = reason(SSLHandshakeException("Trust anchor for certification path not found"))
        assertTrue(msg, msg.contains("Certificate not trusted"))
        assertTrue("should point at the toggle", msg.contains("Verify certificate"))
    }

    @Test fun `a certificate exception is treated the same`() {
        assertTrue(reason(CertificateException("bad cert")).contains("Certificate not trusted"))
    }

    @Test fun `a hostname mismatch is distinguished from an untrusted chain`() {
        val msg = reason(SSLPeerUnverifiedException("hostname mismatch"))
        assertTrue(msg, msg.contains("doesn't match this address"))
    }

    @Test fun `an unknown host points at the URL`() {
        assertTrue(reason(UnknownHostException("nope.invalid")).contains("Can't find that address"))
    }

    @Test fun `a refused connection mentions the port`() {
        assertTrue(reason(ConnectException("refused")).contains("port"))
    }

    @Test fun `a timeout reads as a timeout`() {
        assertTrue(reason(SocketTimeoutException("timed out")).contains("Timed out"))
    }

    @Test fun `a generic SSL failure questions the scheme`() {
        assertTrue(reason(SSLException("handshake broke")).contains("https"))
    }

    // --- the cause chain -------------------------------------------------------------------------

    @Test fun `a wrapped cause is still recognised`() {
        // OkHttp wraps the interesting exception a level or two down; walking the chain is the
        // whole reason this isn't a simple `when (t)`.
        val wrapped = IOException("connection failed", SSLHandshakeException("trust anchor"))
        assertTrue(reason(wrapped).contains("Certificate not trusted"))
    }

    @Test fun `a deeply wrapped cause is still recognised`() {
        val deep = IOException("outer", IOException("middle", SocketTimeoutException("inner")))
        assertTrue(reason(deep).contains("Timed out"))
    }

    // --- fallback --------------------------------------------------------------------------------

    @Test fun `an unrecognised error falls back to its own message`() {
        assertEquals("something odd", reason(IllegalStateException("something odd")))
    }

    @Test fun `an unrecognised error with no message still says something`() {
        assertEquals("Connection failed", reason(IllegalStateException()))
    }
}

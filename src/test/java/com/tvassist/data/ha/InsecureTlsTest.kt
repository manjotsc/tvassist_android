package com.tvassist.data.ha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the gate that decides whether TLS verification may be relaxed.
 *
 * This is the security boundary of the whole verify-certificate feature: if it ever returns true
 * for a public address, a user's long-lived Home Assistant token becomes MITM-able off-network.
 *
 * Every case uses an IP literal, so `InetAddress.getAllByName` resolves locally and the tests need
 * no DNS and no network.
 */
class InsecureTlsTest {

    // --- allowed: private space over https -------------------------------------------------------

    @Test fun `RFC1918 192_168 is private`() {
        assertTrue(InsecureTls.isPrivateHost("https://192.168.1.5:8123"))
    }

    @Test fun `RFC1918 10-dot is private`() {
        assertTrue(InsecureTls.isPrivateHost("https://10.0.2.2:8123"))
    }

    @Test fun `RFC1918 172_16 is private`() {
        assertTrue(InsecureTls.isPrivateHost("https://172.16.0.1:8123"))
    }

    @Test fun `loopback is private`() {
        assertTrue(InsecureTls.isPrivateHost("https://127.0.0.1:8123"))
    }

    @Test fun `link-local is private`() {
        assertTrue(InsecureTls.isPrivateHost("https://169.254.83.107:8123"))
    }

    @Test fun `IPv6 loopback is private`() {
        assertTrue(InsecureTls.isPrivateHost("https://[::1]:8123"))
    }

    @Test fun `IPv6 unique-local fc00 is private`() {
        // fc00::/7 — isSiteLocalAddress misses this, so it's checked explicitly in isPrivateAddress.
        assertTrue(InsecureTls.isPrivateHost("https://[fd00::1]:8123"))
    }

    // --- refused: public space -------------------------------------------------------------------

    @Test fun `public IPv4 is not private`() {
        assertFalse(InsecureTls.isPrivateHost("https://8.8.8.8:8123"))
    }

    @Test fun `172_32 is outside RFC1918 and not private`() {
        // The 172 block is only 172.16-172.31; a naive prefix check would wrongly allow this.
        assertFalse(InsecureTls.isPrivateHost("https://172.32.0.1:8123"))
    }

    @Test fun `public IPv6 is not private`() {
        assertFalse(InsecureTls.isPrivateHost("https://[2001:4860:4860::8888]:8123"))
    }

    // --- refused: wrong scheme or unusable input -------------------------------------------------

    @Test fun `scheme is not this function's concern`() {
        // isPrivateHost answers "is this host on the LAN?" only. The https requirement lives in
        // HaRepository.shouldRelaxTls, which also checks the user's preference before relaxing.
        assertTrue(InsecureTls.isPrivateHost("http://192.168.1.5:8123"))
    }

    @Test fun `scheme-less URL is refused`() {
        assertFalse(InsecureTls.isPrivateHost("192.168.1.5:8123"))
    }

    @Test fun `blank and malformed URLs are refused`() {
        assertFalse(InsecureTls.isPrivateHost(""))
        assertFalse(InsecureTls.isPrivateHost("https://"))
        assertFalse(InsecureTls.isPrivateHost("not a url"))
    }
}

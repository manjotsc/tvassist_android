package com.tvassist.data.ha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on the Home Assistant token. The image fetchers once attached it to any absolute URL —
 * a camera snapshot on another host, a notification image, album art — so each of these `false`
 * cases was a real leak of full admin access to the house.
 */
class SameOriginTest {

    private val ha = "https://ha.example.net:8123"

    @Test fun haItselfGetsTheToken() {
        assertTrue(sameOrigin("https://ha.example.net:8123/api/image/abc.jpg", ha))
        assertTrue(sameOrigin("https://HA.example.net:8123/local/door.jpg", ha))
    }

    @Test fun anotherHostDoesNot() {
        assertFalse(sameOrigin("https://cameras.example.org/camera.ashx?id=12", ha))
        assertFalse(sameOrigin("https://i.scdn.co/image/album.jpg", ha))
        // A lookalike that merely starts with HA's host.
        assertFalse(sameOrigin("https://ha.example.net.evil.com:8123/x.jpg", ha))
    }

    @Test fun anotherPortOnTheSameHostDoesNot() {
        assertFalse(sameOrigin("https://ha.example.net:8443/x.jpg", ha))
        assertFalse(sameOrigin("https://ha.example.net/x.jpg", ha))
    }

    @Test fun plainHttpToAnHttpsInstanceDoesNot() {
        // The token would cross the network in the clear.
        assertFalse(sameOrigin("http://ha.example.net:8123/x.jpg", ha))
    }

    @Test fun defaultPortsMatchTheirExplicitNumbers() {
        assertTrue(sameOrigin("https://ha.lan:443/x.jpg", "https://ha.lan"))
        assertTrue(sameOrigin("http://192.168.1.20/x.jpg", "http://192.168.1.20:80"))
    }

    @Test fun credentialsInTheUrlDoNotChangeTheAnswer() {
        assertFalse(sameOrigin("https://user:pass@cam.lan/snap.jpg", ha))
    }

    @Test fun noBaseOrGarbageIsNeverHa() {
        assertFalse(sameOrigin("https://ha.example.net:8123/x.jpg", ""))
        assertFalse(sameOrigin("not a url", ha))
        assertFalse(sameOrigin("/api/image/abc.jpg", ha))
    }
}

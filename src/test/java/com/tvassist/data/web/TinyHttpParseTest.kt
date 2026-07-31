package com.tvassist.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the query/form parser used by every endpoint. */
class TinyHttpParseTest {
    @Test fun `decodes percent-encoding`() {
        val m = TinyHttpServer.parseQuery("message=Hello%20World&title=Hi")
        assertEquals("Hello World", m["message"])
        assertEquals("Hi", m["title"])
    }

    @Test fun `plus decodes to space`() {
        assertEquals("b c", TinyHttpServer.parseQuery("a=b+c")["a"])
    }

    @Test fun `pairs without an equals sign are skipped`() {
        val m = TinyHttpServer.parseQuery("a=1&garbage&b=2")
        assertEquals("1", m["a"])
        assertEquals("2", m["b"])
        assertEquals(2, m.size)
    }

    @Test fun `empty string yields an empty map`() {
        assertTrue(TinyHttpServer.parseQuery("").isEmpty())
    }

    @Test fun `parseForm mirrors parseQuery`() {
        assertEquals("v", TinyHttpServer.parseForm("k=v")["k"])
    }
}

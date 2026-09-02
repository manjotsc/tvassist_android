package com.tvassist.data.web

import java.io.ByteArrayInputStream
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

    // --- request bodies -----------------------------------------------------------------------

    private fun read(body: String, declaredLength: Int? = null): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return TinyHttpServer.readBody(ByteArrayInputStream(bytes), declaredLength ?: bytes.size)
    }

    @Test fun `a body of plain ASCII survives`() {
        assertEquals("x=cafeXXXXXX", read("x=cafeXXXXXX"))
    }

    @Test fun `a body with multi-byte characters survives`() {
        // The regression. Content-Length counts BYTES, and this body has more bytes than
        // characters — so reading it as that many CHARACTERS waited for ones that never came:
        // ten seconds of nothing, then a closed connection and no response at all.
        //
        // Verified on a real TV before the fix: an ASCII body of the same byte length answered in
        // 15ms, while this one failed six times out of six after 10.03s.
        val body = "x=café☕é"
        assertTrue(body.toByteArray(Charsets.UTF_8).size > body.length)
        assertEquals(body, read(body))
    }

    @Test fun `an emoji in a notification caption survives`() {
        // The shape that actually reaches this app: a doorbell caption typed on a phone.
        val body = "title=Front+door&message=Someone+is+here+👋"
        assertEquals(body, read(body))
    }

    @Test fun `a body shorter than its declared length returns what arrived`() {
        // A client that hangs up mid-body must not leave us waiting for the rest.
        assertEquals("abc", read("abc", declaredLength = 99))
    }

    @Test fun `a bogus content-length cannot make us allocate the world`() {
        assertEquals("hi", TinyHttpServer.readBody(ByteArrayInputStream("hi".toByteArray()), Int.MAX_VALUE, cap = 8))
    }

    @Test fun `no body means no read`() {
        assertEquals("", read("", declaredLength = 0))
    }
}

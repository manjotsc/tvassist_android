package com.tvassist.data.web

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** A parsed HTTP request handed to a [TinyHttpServer.handle] implementation. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names lower-cased. */
    val headers: Map<String, String>,
    val body: String,
) {
    fun form(): Map<String, String> = TinyHttpServer.parseForm(body)

    fun cookie(name: String): String? {
        val raw = headers["cookie"] ?: return null
        return raw.split(';').firstNotNullOfOrNull {
            val p = it.trim(); val i = p.indexOf('=')
            if (i > 0 && p.substring(0, i) == name) p.substring(i + 1) else null
        }
    }
}

/** A response for [TinyHttpServer] to write back. */
class HttpResponse(
    val body: String = "",
    val status: Int = 200,
    val contentType: String = "text/html; charset=utf-8",
    val extraHeaders: List<Pair<String, String>> = emptyList(),
)

/**
 * A tiny, dependency-free HTTP/1.1 server: the socket accept loop, request-line/header parsing,
 * a hardened body read (socket timeout + size cap), and response writing. Subclasses only implement
 * [handle]. Shared by the notification server and the on-demand setup console.
 */
abstract class TinyHttpServer(
    private val port: Int,
    private val tag: String,
    /** When non-null the server speaks TLS (https) using this context; otherwise plain http. */
    private val sslContext: javax.net.ssl.SSLContext? = null,
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    /**
     * Requests are served here, not on the accept thread.
     *
     * They used to share it, which meant one slow client stopped the server dead for as long as
     * [SOCKET_TIMEOUT_MS]. Measured against the notification server: a request left mid-hang made
     * the very next `GET /` take **7.97 seconds**. A browser opening a speculative connection and
     * sending nothing does the same thing, and Chrome does that routinely.
     *
     * Bounded rather than unbounded: this serves one household on a LAN, and a fixed few workers
     * cannot be turned into a thread bomb by anything that reaches the port.
     */
    private var workers: ExecutorService? = null

    protected abstract fun handle(req: HttpRequest): HttpResponse

    fun start(): Boolean = try {
        serverSocket = sslContext?.serverSocketFactory?.createServerSocket(port) ?: ServerSocket(port)
        workers = Executors.newFixedThreadPool(MAX_WORKERS) { r ->
            Thread(r, "$tag-worker").apply { isDaemon = true }
        }
        running = true
        thread(isDaemon = true, name = "$tag-server") { acceptLoop() }
        Log.i(tag, "listening on $port (${if (sslContext != null) "https" else "http"})")
        true
    } catch (e: Exception) {
        Log.w(tag, "failed to start on $port", e); false
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { workers?.shutdownNow() }
        workers = null
    }

    fun address(): String = "${if (sslContext != null) "https" else "http"}://${localIp() ?: "<tv-ip>"}:$port"

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket = try { server.accept() } catch (e: Exception) { break }
            runCatching { socket.soTimeout = SOCKET_TIMEOUT_MS } // bound how long a client holds a worker
            val pool = workers
            if (pool == null) {
                runCatching { socket.close() }
                break
            }
            // Straight back to accepting: whatever this client does now is a worker's problem.
            runCatching {
                pool.execute {
                    try {
                        serve(socket)
                    } catch (e: Exception) {
                        Log.w(tag, "request failed", e)
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.onFailure { runCatching { socket.close() } } // pool shutting down
        }
    }

    private fun serve(socket: Socket) {
        // Bytes, not a Reader. The head is ASCII and the body's length is counted in bytes; mixing
        // the two is what broke every request carrying a non-ASCII character. See [readBody].
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readHeadLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        val headers = HashMap<String, String>()
        while (headers.size < MAX_HEADERS) {
            val line = readHeadLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readBody(input, len)
        val resp = handle(HttpRequest(method, path, query, headers, body))
        write(socket.getOutputStream(), resp)
    }

    /**
     * One line of the request head, without its CRLF, or null at end of stream.
     *
     * Read as bytes and decoded as Latin-1 so every byte maps to exactly one character: a request
     * line is ASCII, and anything non-ASCII in a URL arrives percent-encoded, decoded as UTF-8
     * later by [parseQuery]. Capped, because reading a line off a socket is otherwise an open
     * invitation to send one very long one.
     */
    private fun readHeadLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == NEWLINE) break
            if (out.size() >= MAX_LINE_BYTES) throw IOException("request head line too long")
            out.write(b)
        }
        val line = out.toString("ISO-8859-1")
        return if (line.isNotEmpty() && line.last().code == CARRIAGE_RETURN) line.dropLast(1) else line
    }

    private fun write(out: OutputStream, resp: HttpResponse) {
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n")
        sb.append("Content-Type: ${resp.contentType}\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        // Nothing either server returns is cacheable, and some of it is a Home Assistant
        // token and a notification token rendered on screen. Those have no business
        // sitting on a laptop's disk.
        sb.append("Cache-Control: no-store\r\n")
        resp.extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    /** Anything unlisted used to be sent as `200 OK`, so a 500 announced itself as a success. */
    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        303 -> "See Other"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> when (status / 100) {
            2 -> "OK"
            3 -> "Redirect"
            4 -> "Client Error"
            else -> "Server Error"
        }
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val NEWLINE = 10
        private const val CARRIAGE_RETURN = 13

        // Four is plenty for one household, and small enough that nothing reaching the port can
        // turn the TV into a thread bomb.
        private const val MAX_WORKERS = 4

        /**
         * The request body, read as **bytes** and decoded once as UTF-8.
         *
         * The bug this replaces: `Content-Length` counts bytes, but the body used to be read as
         * that many *characters* from a UTF-8 reader. A body containing any multi-byte character
         * has fewer characters than bytes, so the loop waited for characters that could never
         * arrive — until the socket timed out ten seconds later and the client got no response at
         * all.
         *
         * Measured against the notification server on a real TV: `x=cafeXXXXXX` answered in 15 ms;
         * `x=cafe` plus two multi-byte characters, the same byte length, failed six times out of
         * six after 10.03 s. Which is to say every doorbell caption with an emoji, every accented
         * name, and every curly apostrophe a phone keyboard inserts by itself.
         *
         * Internal so a test can hand it a stream rather than a socket.
         */
        internal fun readBody(input: InputStream, length: Int, cap: Int = MAX_BODY_BYTES): String {
            if (length <= 0) return ""
            // Capped so a bogus Content-Length cannot make us allocate the world.
            val want = length.coerceAtMost(cap)
            val buf = ByteArray(want)
            var read = 0
            while (read < want) {
                val r = input.read(buf, read, want - read)
                if (r == -1) break
                read += r
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }

        fun html(body: String, status: Int = 200, extra: List<Pair<String, String>> = emptyList()) =
            HttpResponse(body, status, "text/html; charset=utf-8", extra)

        fun json(body: String, status: Int = 200) =
            HttpResponse(body, status, "application/json")

        fun redirect(location: String, extra: List<Pair<String, String>> = emptyList()) =
            HttpResponse("", 303, "text/html; charset=utf-8", listOf("Location" to location) + extra)

        fun notFound() = HttpResponse("""{"ok":false,"error":"not found"}""", 404, "application/json")

        fun parseQuery(q: String): Map<String, String> =
            q.split('&').mapNotNull { pair ->
                val i = pair.indexOf('='); if (i < 0) return@mapNotNull null
                val k = runCatching { URLDecoder.decode(pair.substring(0, i), "UTF-8") }.getOrNull() ?: return@mapNotNull null
                val v = runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrDefault("")
                k to v
            }.toMap()

        /** Form bodies are application/x-www-form-urlencoded, same shape as a query string. */
        fun parseForm(body: String): Map<String, String> = parseQuery(body)

        fun escape(s: String): String =
            s.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;")
                .replace("<", "&lt;").replace(">", "&gt;")

        /**
         * This TV's LAN address — the one the console tells someone to type.
         *
         * Ordered, not "whichever came first". A TV with a VPN, or with Ethernet and Wi-Fi both up,
         * has several site-local addresses and only some of them are reachable from the sofa; the
         * old version returned whatever the interface enumeration happened to yield first. Wired
         * beats wireless, and tunnels come last.
         */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .sortedBy { interfaceRank(it.name.orEmpty()) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }

        private fun interfaceRank(name: String): Int = when {
            name.startsWith("eth") -> 0
            name.startsWith("wlan") -> 1
            // A tunnel's address is real but almost never the one a laptop on the sofa can reach.
            name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("vpn") -> 9
            else -> 5
        }
    }
}

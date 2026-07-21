package com.tvassist.data.web

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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

    protected abstract fun handle(req: HttpRequest): HttpResponse

    fun start(): Boolean = try {
        serverSocket = sslContext?.serverSocketFactory?.createServerSocket(port) ?: ServerSocket(port)
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
    }

    fun address(): String = "${if (sslContext != null) "https" else "http"}://${localIp() ?: "<tv-ip>"}:$port"

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket = try { server.accept() } catch (e: Exception) { break }
            runCatching { socket.soTimeout = SOCKET_TIMEOUT_MS } // bound how long a client holds the thread
            try { serve(socket) } catch (e: Exception) { Log.w(tag, "request failed", e) } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readBody(reader, len)
        val resp = handle(HttpRequest(method, path, query, headers, body))
        write(socket.getOutputStream(), resp)
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val cap = length.coerceAtMost(MAX_BODY_CHARS) // cap so a bogus Content-Length can't OOM us
        val buf = CharArray(cap); var read = 0
        while (read < cap) { val r = reader.read(buf, read, cap - read); if (r == -1) break; read += r }
        return String(buf, 0, read)
    }

    private fun write(out: OutputStream, resp: HttpResponse) {
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        val reason = when (resp.status) {
            200 -> "OK"; 303 -> "See Other"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "OK"
        }
        val sb = StringBuilder("HTTP/1.1 ${resp.status} $reason\r\n")
        sb.append("Content-Type: ${resp.contentType}\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        resp.extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY_CHARS = 256 * 1024

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

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) { null }
    }
}

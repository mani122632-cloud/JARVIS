package com.jarvis.assistant.brain.llm

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Socket
import java.net.URI

/**
 * Minimal HTTP/1.1 POST client for the LOCAL llama.cpp server (http://127.0.0.1:8080/v1).
 *
 * Why this exists: since Android 9 [java.net.HttpURLConnection] refuses every cleartext `http://` request that the
 * app's network security config does not allow, loopback included ("CLEARTEXT communication to 127.0.0.1 not
 * permitted by network security policy"). That IOException was reported as NO_NETWORK, so EVERY utterance silently
 * fell back to the offline brain and JARVIS only ever said «بله ارباب، متوجهم». A plain socket to the loopback
 * interface is not subject to that policy and the traffic never leaves the phone. Only loopback addresses are
 * accepted here; anything else is refused before a connection is made.
 *
 * Supports Content-Length, chunked and read-until-close bodies. No keep-alive: one request per connection.
 * [post] returns the whole body; [postStream] hands the body to the caller line by line while it arrives
 * (Server-Sent Events of a streamed completion).
 */
internal object LoopbackHttp {

    class Reply(val code: Int, val body: String)

    private class Head(val code: Int, val contentLength: Long, val chunked: Boolean)

    @Throws(IOException::class)
    fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBodyBytes: Int
    ): Reply {
        openAndSend(url, headers, body, connectTimeoutMs, readTimeoutMs).use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            return readReply(input, maxBodyBytes)
        }
    }

    /**
     * Streaming variant. For a 2xx answer every line of the body is passed to [onLine] as soon as it arrives
     * (without its line break); [onLine] returns false to stop reading, and the connection is closed. For any other
     * status the body is not read. Returns the HTTP status. [cancel] (optional) closes the connection from another
     * thread, which makes a blocked read throw an [IOException].
     */
    @Throws(IOException::class)
    fun postStream(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBodyBytes: Int,
        cancel: LlmCancel?,
        onLine: (String) -> Boolean
    ): Int {
        val socket = openAndSend(url, headers, body, connectTimeoutMs, readTimeoutMs)
        cancel?.attach { try { socket.close() } catch (e: IOException) { /* ignore */ } }
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input)
            if (head.code !in 200..299) return head.code
            when {
                head.chunked -> pumpLines(ChunkedInputStream(input), maxBodyBytes, onLine)
                head.contentLength >= 0 -> {
                    if (head.contentLength > maxBodyBytes) throw IOException("response too large")
                    val text = String(readExactly(input, head.contentLength.toInt()), Charsets.UTF_8)
                    for (line in text.split('\n')) if (!onLine(line.trimEnd('\r'))) break
                }
                else -> pumpLines(input, maxBodyBytes, onLine)
            }
            return head.code
        } finally {
            cancel?.detach()
            try { socket.close() } catch (e: IOException) { /* ignore */ }
        }
    }

    private fun pumpLines(stream: InputStream, maxBodyBytes: Int, onLine: (String) -> Boolean) {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var total = 0L
        while (true) {
            val line = reader.readLine() ?: break
            total += line.length + 1
            if (total > maxBodyBytes) throw IOException("response too large")
            if (!onLine(line)) break
        }
    }

    /** Connects (loopback only), sends the request and returns the open socket; the caller closes it. */
    private fun openAndSend(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Socket {
        val uri = try { URI(url.trim()) } catch (e: Exception) { throw MalformedURLException("bad URL") }
        val rawHost = uri.host ?: throw MalformedURLException("no host")
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        // "localhost" may resolve to ::1 first while llama.cpp listens on 127.0.0.1: use the IPv4 loopback.
        val address = if (rawHost.equals("localhost", ignoreCase = true)) InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        else InetAddress.getByName(rawHost)
        if (!address.isLoopbackAddress) throw IOException("not a loopback address")

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            val head = StringBuilder()
                .append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(rawHost).append(':').append(port).append("\r\n")
                .append("Content-Length: ").append(body.size).append("\r\n")
                .append("Connection: close\r\n")
            for ((k, v) in headers) {
                if (k.contains('\r') || k.contains('\n') || v.contains('\r') || v.contains('\n')) continue
                head.append(k).append(": ").append(v).append("\r\n")
            }
            head.append("\r\n")

            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
            return socket
        } catch (t: Throwable) {
            try { socket.close() } catch (e: IOException) { /* ignore */ }
            throw t
        }
    }

    private fun readHead(input: InputStream): Head {
        val status = readLine(input) ?: throw IOException("empty reply")
        val parts = status.split(' ', limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")

        var contentLength = -1L
        var chunked = false
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toLongOrNull() ?: -1L
                "transfer-encoding" -> if (value.lowercase().contains("chunked")) chunked = true
            }
        }
        return Head(code, contentLength, chunked)
    }

    private fun readReply(input: InputStream, maxBodyBytes: Int): Reply {
        val head = readHead(input)
        val bytes = when {
            head.chunked -> readChunked(input, maxBodyBytes)
            head.contentLength >= 0 -> {
                if (head.contentLength > maxBodyBytes) throw IOException("response too large")
                readExactly(input, head.contentLength.toInt())
            }
            else -> readToEnd(input, maxBodyBytes)
        }
        return Reply(head.code, String(bytes, Charsets.UTF_8))
    }

    private fun readChunked(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("truncated chunk")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                // Optional trailers up to the final blank line (or the end of the stream).
                while (true) { val t = readLine(input) ?: break; if (t.isEmpty()) break }
                break
            }
            if (out.size() + size > max) throw IOException("response too large")
            out.write(readExactly(input, size))
            readLine(input)                                     // CRLF after the chunk data
        }
        return out.toByteArray()
    }

    /**
     * Incremental decoder of a chunked body: hands out the bytes of a chunk as soon as they arrive, so a streamed
     * reply is not held back until the connection ends. The CRLF after a chunk is consumed lazily, when the next
     * chunk is asked for, so a read never blocks on bytes the caller does not need yet.
     */
    private class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var remaining = 0
        private var needCrlf = false
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (eof) return -1
            if (remaining == 0) {
                if (needCrlf) {
                    LoopbackHttp.readLine(src)
                    needCrlf = false
                }
                val sizeLine = LoopbackHttp.readLine(src) ?: throw IOException("truncated chunk")
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
                if (size == 0) {
                    eof = true
                    return -1
                }
                remaining = size
            }
            val n = src.read(b, off, minOf(len, remaining))
            if (n < 0) throw IOException("truncated chunk")
            remaining -= n
            if (remaining == 0) needCrlf = true
            return n
        }

        override fun available(): Int = if (eof) 0 else minOf(remaining, src.available())
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("truncated body")
            off += r
        }
        return buf
    }

    private fun readToEnd(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > max) throw IOException("response too large")
        }
        return out.toByteArray()
    }

    /** One header / status line without its CRLF; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw IOException("header line too long")
        }
        return sb.toString()
    }

    private const val MAX_LINE = 8192
}

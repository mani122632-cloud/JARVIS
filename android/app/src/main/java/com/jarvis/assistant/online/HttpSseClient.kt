package com.jarvis.assistant.online

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTPS + Server-Sent-Events client on [HttpURLConnection] (no dependency). For a future provider.
 *
 *  - HTTPS only; the work (connect, write, read) always runs on a background thread, never the caller's.
 *  - connect timeout 5 s; first event 8 s; idle (no data) 15 s; total 30 s; `Accept-Encoding: identity`.
 *  - [Cancellable.cancel] calls `disconnect()`; after cancel NO listener callback is delivered.
 *  - Exactly one terminal callback ([Listener.onComplete] or [Listener.onError]) unless cancelled.
 *  - Neither the URL, the headers nor the body are ever logged.
 */
class HttpSseClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val firstEventTimeoutMs: Long = FIRST_EVENT_TIMEOUT_MS,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS
) {
    interface Listener {
        /** One complete SSE event ([event] may be null); [data] is the joined `data:` lines. Background thread. */
        fun onEvent(event: String?, data: String)
        /** The stream ended normally. */
        fun onComplete()
        /** [httpCode] is set for ErrorKind.HTTP. [detail] never contains request or response text. */
        fun onError(kind: ErrorKind, httpCode: Int?, detail: String?)
    }

    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "jarvis-sse").apply { isDaemon = true }
    }
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jarvis-sse-watchdog").apply { isDaemon = true }
    }

    /** Starts a POST and returns at once. */
    fun post(url: String, headers: Map<String, String>, body: String, listener: Listener): Cancellable {
        val call = Call(listener)
        if (!url.startsWith("https://", ignoreCase = true)) {
            call.fail(ErrorKind.UNAVAILABLE, null, "https required")
            return call
        }
        try {
            workers.execute { call.run(url, headers, body) }
        } catch (e: RuntimeException) {
            call.fail(ErrorKind.UNKNOWN, null, "executor")
        }
        return call
    }

    fun close() {
        workers.shutdownNow()
        watchdog.shutdownNow()
    }

    private inner class Call(private val listener: Listener) : Cancellable {
        private val cancelled = AtomicBoolean(false)
        private val terminated = AtomicBoolean(false)
        @Volatile private var conn: HttpURLConnection? = null
        @Volatile private var timeoutKind: String? = null
        private var firstTask: ScheduledFuture<*>? = null
        private var idleTask: ScheduledFuture<*>? = null
        private var totalTask: ScheduledFuture<*>? = null

        override val isCancelled: Boolean get() = cancelled.get()

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            terminated.set(true)
            stopTimers()
            disconnect()
        }

        private fun disconnect() {
            try { conn?.disconnect() } catch (e: RuntimeException) { /* ignore */ }
        }

        private fun stopTimers() {
            firstTask?.cancel(false); idleTask?.cancel(false); totalTask?.cancel(false)
        }

        private fun schedule(ms: Long, what: String): ScheduledFuture<*>? = try {
            watchdog.schedule({
                if (!terminated.get()) { timeoutKind = what; disconnect() }
            }, ms, TimeUnit.MILLISECONDS)
        } catch (e: RuntimeException) { null }

        fun fail(kind: ErrorKind, code: Int?, detail: String?) {
            if (!terminated.compareAndSet(false, true)) return
            stopTimers(); disconnect()
            safe { listener.onError(kind, code, detail) }
        }

        private fun complete() {
            if (!terminated.compareAndSet(false, true)) return
            stopTimers(); disconnect()
            safe { listener.onComplete() }
        }

        private inline fun safe(block: () -> Unit) {
            try { block() } catch (e: RuntimeException) { /* a listener bug must not kill the worker */ }
        }

        fun run(url: String, headers: Map<String, String>, body: String) {
            if (cancelled.get()) return
            totalTask = schedule(totalTimeoutMs, "total")
            firstTask = schedule(firstEventTimeoutMs, "first")
            var stream: InputStream? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                if (cancelled.get()) { c.disconnect(); return }
                c.requestMethod = "POST"
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = idleTimeoutMs.toInt()
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.doOutput = true
                c.setRequestProperty("Accept", "text/event-stream")
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }

                val code = c.responseCode
                if (code !in 200..299) { fail(ErrorKind.HTTP, code, "http"); return }
                stream = c.inputStream
                readEvents(stream)
                if (cancelled.get()) return
                if (timeoutKind != null) fail(ErrorKind.TIMEOUT, null, timeoutKind) else complete()
            } catch (e: IOException) {
                if (cancelled.get() || terminated.get()) return
                val t = timeoutKind
                if (t != null || e is java.net.SocketTimeoutException) fail(ErrorKind.TIMEOUT, null, t ?: "read")
                else fail(ErrorKind.NETWORK, null, "io")
            } catch (e: RuntimeException) {
                if (!cancelled.get()) fail(ErrorKind.UNKNOWN, null, "runtime")
            } finally {
                try { stream?.close() } catch (e: IOException) { /* ignore */ }
                disconnect()
            }
        }

        private fun readEvents(input: InputStream) {
            val reader = InputStreamReader(input, Charsets.UTF_8)
            val data = StringBuilder()
            var event: String? = null
            var gotFirst = false
            val line = StringBuilder()
            var prevCr = false
            val one = CharArray(4096)
            while (!terminated.get()) {
                val n = reader.read(one)
                if (n < 0) break
                idleTask?.cancel(false)
                idleTask = schedule(idleTimeoutMs, "idle")
                for (k in 0 until n) {
                    val ch = one[k]
                    if (ch == '\n' && prevCr) { prevCr = false; continue }
                    if (ch == '\r' || ch == '\n') {
                        prevCr = ch == '\r'
                        val l = line.toString(); line.setLength(0)
                        if (l.isEmpty()) {
                            if (data.isNotEmpty() || event != null) {
                                if (!gotFirst) { gotFirst = true; firstTask?.cancel(false) }
                                val payload = data.toString(); data.setLength(0)
                                val ev = event; event = null
                                if (!terminated.get()) safe { listener.onEvent(ev, payload) }
                            }
                        } else if (!l.startsWith(":")) {
                            val idx = l.indexOf(':')
                            val field = if (idx < 0) l else l.substring(0, idx)
                            var value = if (idx < 0) "" else l.substring(idx + 1)
                            if (value.startsWith(" ")) value = value.substring(1)
                            when (field) {
                                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
                                "event" -> event = value
                            }
                        }
                        continue
                    }
                    prevCr = false
                    line.append(ch)
                    if (line.length > MAX_LINE_CHARS) throw IOException("line too long")
                }
            }
            if (data.isNotEmpty() && !terminated.get()) safe { listener.onEvent(event, data.toString()) }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val FIRST_EVENT_TIMEOUT_MS = 8_000L
        const val IDLE_TIMEOUT_MS = 15_000L
        const val TOTAL_TIMEOUT_MS = 30_000L
        private const val MAX_LINE_CHARS = 256 * 1024
    }
}

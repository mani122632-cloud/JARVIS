package com.jarvis.assistant.online

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Online provider order: Gemini -> Groq -> (the existing offline path, which [OnlineBrain] / the conversation
 * controller already take when this provider reports an error).
 *
 * It is itself an [OnlineProvider], so [OnlineBrain] is unchanged: it asks for ONE round, the router chooses the
 * provider and may transparently restart that same round (same [ChatRequest], i.e. same history) on Groq ONCE.
 *
 * A round moves from Gemini to Groq only when ALL of these hold:
 *  - Gemini failed before delivering any text or tool call (so nothing was spoken: no repeated speech);
 *  - the failure is transient: HTTP 429/500/502/503/504, timeout, network failure or an empty answer;
 *    (400/401/403 and other configuration / authentication errors are NOT passed on);
 *  - Groq has an API key and the round was not cancelled.
 * If Groq fails too, its error is delivered as is and the offline path continues as before.
 *
 * After a transient Gemini failure Gemini is skipped for [GEMINI_COOLDOWN_MS] so the next requests go straight to
 * Groq instead of waiting for a broken Gemini again. When Groq is not configured Gemini is always tried.
 *
 * Available as soon as EITHER provider has a key. Neither keys nor request / answer text are ever logged.
 */
class OnlineProviderRouter(
    private val gemini: OnlineProvider,
    private val groq: OnlineProvider,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val geminiCooldownMs: Long = GEMINI_COOLDOWN_MS
) : OnlineProvider {

    override val id: String = "router"

    /** Elapsed-realtime until which Gemini is skipped; 0 = not cooling down. */
    @Volatile private var geminiBlockedUntil = 0L

    override fun isAvailable(): Boolean = safeAvailable(gemini) || safeAvailable(groq)

    override fun stream(request: ChatRequest, listener: StreamListener): Cancellable {
        val geminiOk = safeAvailable(gemini)
        val groqOk = safeAvailable(groq)
        val round = RoutedRound(request, listener)
        val useGeminiFirst = geminiOk && (!isGeminiCoolingDown() || !groqOk)
        when {
            useGeminiFirst -> round.startGemini(canFallBack = groqOk)
            groqOk -> round.startGroq()
            else -> round.failNow()
        }
        return round
    }

    // ---- state -----------------------------------------------------------------------------------

    private fun isGeminiCoolingDown(): Boolean {
        val until = geminiBlockedUntil
        return until != 0L && clock() < until
    }

    private fun markGeminiFailed() { geminiBlockedUntil = clock() + geminiCooldownMs }

    private fun safeAvailable(p: OnlineProvider): Boolean = try { p.isAvailable() } catch (e: RuntimeException) { false }

    // ---- one routed round ------------------------------------------------------------------------

    private inner class RoutedRound(
        private val request: ChatRequest,
        private val out: StreamListener
    ) : Cancellable {
        private val cancelled = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)       // the terminal event was delivered
        @Volatile private var handle: Cancellable? = null

        override val isCancelled: Boolean get() = cancelled.get()

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            handle?.cancel()
        }

        fun failNow() {
            // No provider at all (OnlineBrain checks isAvailable first, this is only a safety net).
            deliverTerminal(StreamEvent.Error(ErrorKind.UNAVAILABLE, "no_provider"))
        }

        fun startGemini(canFallBack: Boolean) {
            val stage = Stage(isGemini = true, canFallBack = canFallBack)
            launch(gemini, stage)
        }

        fun startGroq() {
            launch(groq, Stage(isGemini = false, canFallBack = false))
        }

        private fun launch(provider: OnlineProvider, stage: Stage) {
            val h = try {
                provider.stream(request, StreamListener { ev -> stage.onEvent(ev) })
            } catch (e: RuntimeException) {
                Log.w(TAG, "${provider.id} failed to start")
                stage.onEvent(StreamEvent.Error(ErrorKind.UNKNOWN, "start"))
                return
            }
            handle = h
            if (cancelled.get()) h.cancel()
        }

        private fun deliverTerminal(ev: StreamEvent) {
            if (cancelled.get() || !finished.compareAndSet(false, true)) return
            out.onEvent(ev)
        }

        /** Events of ONE provider attempt. Stale events (after a switch / terminal / cancel) are dropped. */
        private inner class Stage(val isGemini: Boolean, val canFallBack: Boolean) {
            private val over = AtomicBoolean(false)
            @Volatile private var delivered = false      // text or a tool call already went to the listener

            fun onEvent(ev: StreamEvent) {
                if (cancelled.get() || over.get()) return
                when (ev) {
                    is StreamEvent.TextDelta, is StreamEvent.ToolCallRequest -> {
                        delivered = true
                        out.onEvent(ev)
                    }
                    is StreamEvent.Finished -> {
                        if (!over.compareAndSet(false, true)) return
                        deliverTerminal(ev)
                    }
                    is StreamEvent.Error -> {
                        if (!over.compareAndSet(false, true)) return
                        onError(ev)
                    }
                }
            }

            private fun onError(ev: StreamEvent.Error) {
                val transient = isTransient(ev)
                if (isGemini && transient) markGeminiFailed()
                val mayFallBack = isGemini && canFallBack && transient && !delivered && !cancelled.get()
                if (mayFallBack) {
                    Log.i(TAG, "Gemini failed (${ev.kind}); retrying this round on Groq")
                    startGroq()
                } else {
                    deliverTerminal(ev)
                }
            }
        }
    }

    companion object {
        private const val TAG = "OnlineRouter"
        const val GEMINI_COOLDOWN_MS = 45_000L

        private val CODE_REGEX = Regex("^(?:http_|api_error_)(\\d{3})$")
        private val TRANSIENT_HTTP = setOf(429, 500, 502, 503, 504)

        /**
         * Temporary failure that another provider may fix. Authentication / configuration problems (400, 401, 403,
         * missing key, blocked prompt, bad request) are deliberately NOT transient.
         */
        internal fun isTransient(ev: StreamEvent.Error): Boolean = when (ev.kind) {
            ErrorKind.NETWORK, ErrorKind.TIMEOUT -> true
            ErrorKind.HTTP -> {
                val code = ev.detail?.let { CODE_REGEX.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                code != null && code in TRANSIENT_HTTP
            }
            ErrorKind.PROTOCOL -> ev.detail == "empty" || ev.detail == "finish_MAX_TOKENS"
            ErrorKind.UNAVAILABLE, ErrorKind.UNKNOWN -> false
        }
    }
}

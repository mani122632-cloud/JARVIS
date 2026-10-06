package com.jarvis.assistant.brain.llm

import com.jarvis.assistant.brain.BrainResult
import com.jarvis.assistant.brain.JarvisBrain
import com.jarvis.assistant.conversation.ConversationContext

/**
 * Receives the model's reply while it is still being generated. Called on the provider's worker thread,
 * never on the main thread.
 */
interface LlmStreamListener {
    /** A piece of the reply text. Return false to stop reading the stream (the call then returns what arrived so far). */
    fun onTextDelta(delta: String): Boolean

    /** The model started a tool call (its arguments are only usable once the call has returned). */
    fun onToolCallStarted()
}

/**
 * Lets another thread abort a running LLM request: [cancel] closes the connection, so a blocking read ends at once
 * and the server stops generating. This is what keeps ONE active LLM request at a time.
 */
class LlmCancel {
    @Volatile var isCancelled: Boolean = false
        private set
    @Volatile private var closer: (() -> Unit)? = null

    fun attach(close: () -> Unit) {
        closer = close
        if (isCancelled) runCloser()
    }

    fun detach() { closer = null }

    fun cancel() {
        isCancelled = true
        runCloser()
    }

    private fun runCloser() {
        try { closer?.invoke() } catch (t: Throwable) { /* already closed */ }
    }
}

/**
 * Where the Brain hands the speakable parts of a reply while the LLM is still writing it. Main thread only.
 * Segments arrive in order, each exactly once, already cleaned and cut at natural boundaries.
 */
interface ReplyStreamSink {
    fun onSegment(text: String)

    /**
     * Called right before a [BrainResult.Conversation] whose text was ALREADY delivered through [onSegment]:
     * the caller must not speak that result again. For every other result nothing was said twice and the
     * caller speaks the result as usual.
     */
    fun onFinalReplySpoken()
}

/**
 * Optional capability of a [JarvisBrain]: answer while streaming. A caller that does not know it keeps using
 * [JarvisBrain.thinkAsync], so nothing else has to change.
 */
interface StreamingJarvisBrain : JarvisBrain {
    /** Same contract as thinkAsync (callback exactly once, on the main thread), plus the live [sink]. */
    fun thinkStreamAsync(text: String, context: ConversationContext, sink: ReplyStreamSink, onResult: (BrainResult) -> Unit)

    /** Aborts the running streamed turn, if any: the connection is closed and no callback follows. Main thread. */
    fun cancelStreaming()
}

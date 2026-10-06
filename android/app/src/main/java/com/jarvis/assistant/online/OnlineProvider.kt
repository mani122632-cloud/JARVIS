package com.jarvis.assistant.online

/**
 * Provider-independent contract of the Online Brain (Stage 1). No real provider, endpoint or API key exists in
 * this stage: a later stage implements [OnlineProvider] and registers it in [OnlineProviderRegistry].
 * Nothing here is Android specific and no user text is ever logged by these types.
 */

/** Handle of a running request. [cancel] is idempotent, thread-safe and never throws. */
interface Cancellable {
    fun cancel()
    val isCancelled: Boolean

    companion object {
        /** A handle that is already finished (nothing to cancel). */
        val NONE: Cancellable = object : Cancellable {
            override fun cancel() = Unit
            override val isCancelled: Boolean get() = true
        }
    }
}

enum class ChatRole(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }

/**
 * One message of the history.
 *  - USER / SYSTEM: [content].
 *  - ASSISTANT: [content] (may be empty) and optionally [toolCalls] it asked for.
 *  - TOOL: the structured result ([content], JSON) of the call whose id is [toolCallId].
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String = "",
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null
)

/** A tool the model may call. [parametersJson] is a JSON Schema (object) serialized as a string. */
data class ToolSpec(val name: String, val description: String, val parametersJson: String)

/** A tool call requested by the model. [id] is the `tool_call_id`; [argumentsJson] is the raw JSON object text. */
data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)

data class ChatRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val stream: Boolean = true
)

enum class ErrorKind { NETWORK, TIMEOUT, HTTP, PROTOCOL, UNAVAILABLE, UNKNOWN }

/** What a provider emits while streaming one model round. */
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()

    /** The model asks for a tool; [call] carries the `tool_call_id`. */
    data class ToolCallRequest(val call: com.jarvis.assistant.online.ToolCallRequest) : StreamEvent()

    /** Terminal: the round ended normally. */
    data class Finished(val reason: String? = null) : StreamEvent()

    /** Terminal: the round failed. [detail] must never contain user text. */
    data class Error(val kind: ErrorKind, val detail: String? = null) : StreamEvent()
}

fun interface StreamListener {
    /** May be called from any background thread; never from the caller of [OnlineProvider.stream]. */
    fun onEvent(event: StreamEvent)
}

interface OnlineProvider {
    val id: String

    /** Configured and usable (the network check is done separately by the OnlineBrain). */
    fun isAvailable(): Boolean

    /**
     * Starts ONE model round without blocking the caller. Must emit zero or more TextDelta / ToolCallRequest
     * events and then exactly one terminal event (Finished or Error), unless cancelled (then nothing more).
     */
    fun stream(request: ChatRequest, listener: StreamListener): Cancellable
}

/** Dependency injection point for a future provider. Empty = Online is off (the default in Stage 1). */
object OnlineProviderRegistry {
    @Volatile var provider: OnlineProvider? = null
}

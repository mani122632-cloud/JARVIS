package com.jarvis.assistant.brain.llm

import com.jarvis.assistant.command.ToolSpec

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * A tool the LLM asked for. [arguments] are already flattened to strings. [malformed] = the provider could not
 * read the arguments as JSON; such a call is never executed, the LLM gets a FAILED result instead.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val rawArguments: String = "",
    val malformed: Boolean = false
)

/** What a tool call produced, sent back to the LLM so it can answer truthfully. */
data class ToolCallResult(
    val callId: String,
    val name: String,
    val success: Boolean,
    val message: String
)

data class LlmMessage(
    val role: LlmRole,
    val content: String,
    /** Only for ASSISTANT: the tool calls that message made. */
    val toolCalls: List<ToolCallRequest> = emptyList(),
    /** Only for TOOL: which call this answers. */
    val toolCallId: String? = null,
    val toolName: String? = null
)

/** Provider-neutral request: the full message list (system prompt first) and the tools the LLM may call. */
data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Float = 0.3f,
    val maxTokens: Int = 300
)

enum class LlmFailureKind { NOT_CONFIGURED, NO_NETWORK, TIMEOUT, AUTH, RATE_LIMIT, HTTP_ERROR, MALFORMED, UNKNOWN }

sealed class LlmResponse {
    /** [text] may be blank when the model only called tools. */
    data class Success(val text: String, val toolCalls: List<ToolCallRequest> = emptyList()) : LlmResponse()

    /** Never carries user text or secrets in [detail]. */
    data class Failure(val kind: LlmFailureKind, val detail: String = "") : LlmResponse()
}

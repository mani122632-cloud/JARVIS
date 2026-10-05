package com.jarvis.assistant.conversation

/**
 * A tool request the LLM brain could not finish ("آلارم بذار" -> "چه ساعتی ارباب؟"): the tool's LLM-facing name,
 * what is already known, and what is still missing. Shown to the LLM on the next turn so it can complete the call.
 * Lives in [ConversationContext] (RAM, one session).
 */
data class PendingToolIntent(
    val tool: String,
    val knownArgs: Map<String, String> = emptyMap(),
    val missing: List<String> = emptyList()
)

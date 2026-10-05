package com.jarvis.assistant.conversation

/**
 * Short-term memory of ONE session: the last few things the user said and JARVIS answered, plus the session
 * state. Strictly bounded, in RAM only, cleared when a session begins and when it ends. It is NOT the
 * persistent [com.jarvis.assistant.memory.JarvisMemory] and never writes to it. Its text is never logged.
 * Main thread only.
 */
class ConversationContext(private val maxEntries: Int = MAX_ENTRIES) {

    private val users = ArrayDeque<String>()
    private val responses = ArrayDeque<String>()

    /** Mirrors the controller's state, so the Brain can see where the session is. */
    var sessionState: SessionState = SessionState.IDLE
        internal set

    /** Number of user utterances in this session. */
    var turnCount: Int = 0
        private set

    /** Most recent last (oldest dropped beyond the limit). */
    val recentUserUtterances: List<String> get() = users.toList()
    val recentResponses: List<String> get() = responses.toList()

    fun addUserUtterance(text: String) {
        turnCount++
        push(users, text)
    }

    fun addResponse(text: String) = push(responses, text)

    fun clear() {
        users.clear()
        responses.clear()
        turnCount = 0
    }

    private fun push(q: ArrayDeque<String>, text: String) {
        if (text.isBlank()) return
        q.addLast(text)
        while (q.size > maxEntries) q.removeFirst()
    }

    companion object {
        const val MAX_ENTRIES = 6
    }
}

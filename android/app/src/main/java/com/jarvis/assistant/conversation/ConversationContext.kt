package com.jarvis.assistant.conversation

/**
 * Short-term memory of ONE session: the last few things the user said and JARVIS answered, the session
 * state, and the request that is waiting for the user's answer ([pending]). Strictly bounded, in RAM only, cleared when a session begins and when it ends. It is NOT the
 * persistent [com.jarvis.assistant.memory.JarvisMemory] and never writes to it. Its text is never logged.
 * Main thread only.
 */
class ConversationContext(private val maxEntries: Int = MAX_ENTRIES) {

    private val users = ArrayDeque<String>()
    private val responses = ArrayDeque<String>()
    private val log = ArrayDeque<Turn>()

    /** One line of the ordered transcript (user or JARVIS). */
    data class Turn(val fromUser: Boolean, val text: String)

    /** Ordered recent transcript (oldest first), bounded; what the LLM brain uses as conversation history. */
    val turns: List<Turn> get() = log.toList()

    /** The unfinished tool request the LLM brain asked a question about (null = none). Cleared with the session. */
    var llmPending: PendingToolIntent? = null

    /** Mirrors the controller's state, so the Brain can see where the session is. */
    var sessionState: SessionState = SessionState.IDLE
        internal set

    /**
     * The incomplete Alarm / Timer request JARVIS asked a question about (null = nothing is waiting). Set and
     * cleared by the Brain; dropped with the rest of the session.
     */
    var pending: PendingIntent? = null

    /** Number of user utterances in this session. */
    var turnCount: Int = 0
        private set

    /** Most recent last (oldest dropped beyond the limit). */
    val recentUserUtterances: List<String> get() = users.toList()
    val recentResponses: List<String> get() = responses.toList()

    fun addUserUtterance(text: String) {
        turnCount++
        push(users, text)
        pushTurn(Turn(true, text))
    }

    fun addResponse(text: String) {
        push(responses, text)
        pushTurn(Turn(false, text))
    }

    fun clear() {
        users.clear()
        responses.clear()
        log.clear()
        pending = null
        llmPending = null
        turnCount = 0
    }

    private fun pushTurn(turn: Turn) {
        if (turn.text.isBlank()) return
        log.addLast(turn)
        while (log.size > maxEntries * 2) log.removeFirst()
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

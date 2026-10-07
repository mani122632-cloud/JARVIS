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

    /** Mirrors the controller's state, so the Brain can see where the session is. */
    var sessionState: SessionState = SessionState.IDLE
        internal set

    /**
     * The incomplete Alarm / Timer request JARVIS asked a question about (null = nothing is waiting). Set and
     * cleared by the Brain; dropped with the rest of the session.
     */
    var pending: PendingIntent? = null

    /** Open multi-step "play a song" question (null = none). Same lifetime as [pending]. */
    var mediaStage: MediaStage? = null
    /** Language answered in the media question ("fa" / "en"), or null. */
    var mediaLanguage: String? = null
    /** Failed answers to the current media question. */
    var mediaAttempts: Int = 0

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
        pending = null
        mediaStage = null
        mediaLanguage = null
        mediaAttempts = 0
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

/** Where the "آهنگ رو پخش کن" dialog is: waiting for the language, then for the song name. */
enum class MediaStage { ASK_LANGUAGE, ASK_NAME }

package com.jarvis.assistant.conversation

/** State of one conversation session (after the wake word). IDLE = no session; the wake word is listening. */
enum class SessionState { IDLE, ACTIVE_LISTENING, PROCESSING, SPEAKING, ENDING }

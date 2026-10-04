package com.jarvis.assistant.nlu

/** Confidence levels shared by the parser, the processor and the conversation controller. */
object CommandConfidence {
    /** Below this a command is never executed; JARVIS says "متوجه نشدم." instead. */
    const val THRESHOLD = 0.7f

    /**
     * A still-growing partial recognition result may be executed early only at or above this level
     * (so "صدا رو روی ۵" is never taken for 5% while "۵۰ درصد" is still being spoken).
     */
    const val FAST_PATH = 0.9f
}

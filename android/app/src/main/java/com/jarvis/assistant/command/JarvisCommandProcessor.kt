package com.jarvis.assistant.command

import com.jarvis.assistant.nlu.CommandConfidence
import com.jarvis.assistant.nlu.CommandIntentParser
import com.jarvis.assistant.speech.JarvisPhrases

/**
 * Text of one spoken command -> what JARVIS should say and do. Pure and synchronous (no Android, no I/O),
 * so it is safe to call for every partial recognition result.
 *
 * [Result.handled] is true only for a known command at or above [CommandConfidence.THRESHOLD];
 * otherwise [Result.action] is null and nothing may be executed.
 */
class JarvisCommandProcessor(private val parser: CommandIntentParser = CommandIntentParser()) {

    data class Result(
        val handled: Boolean,
        val action: JarvisAction?,
        /** Said before the action runs ("حتماً.") or instead of it ("متوجه نشدم."). */
        val responseText: String,
        val confidence: Float
    ) {
        /** Sure enough to run from a partial result, before the recognizer has finished. */
        val isHighConfidence: Boolean get() = handled && confidence >= CommandConfidence.FAST_PATH
    }

    fun process(text: String): Result {
        val parsed = parser.parse(text)
        val ok = parsed.action !is JarvisAction.Unknown && parsed.confidence >= CommandConfidence.THRESHOLD
        return if (ok) Result(true, parsed.action, JarvisPhrases.SURE, parsed.confidence)
        else Result(false, null, JarvisPhrases.NOT_UNDERSTOOD, parsed.confidence)
    }
}

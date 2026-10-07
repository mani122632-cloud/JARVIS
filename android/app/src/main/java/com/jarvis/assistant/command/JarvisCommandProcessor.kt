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
        /** Said before the action runs ("حتماً.") or instead of it ("متوجه نشدم."). Empty: the action speaks for itself. */
        val responseText: String,
        val confidence: Float,
        /** True: only a FINAL recognition result may run it (SMS text must be complete). */
        val deferToFinal: Boolean = false
    ) {
        /** Sure enough to run from a partial result, before the recognizer has finished. */
        val isHighConfidence: Boolean get() = handled && !deferToFinal && confidence >= CommandConfidence.FAST_PATH
    }

    fun process(text: String): Result {
        // SMS first (fast path, no LLM). Never from a partial result: a half-heard message must not be sent.
        SmsCommandParser.parse(text)?.let { return Result(true, it, "", 1f, deferToFinal = true) }
        // Call contact (fast path, no LLM). CallTool refuses a missing / unknown / ambiguous name with a question.
        // Final result only: a half-heard name must not dial the wrong contact.
        CallCommandParser.parse(text)?.let { return Result(true, it, "", 1f, deferToFinal = true) }
        // Browser Search (explicit "سرچ کن ..."): opens Chrome, separate from Web Answer. Final result only.
        BrowserSearchCommandParser.parse(text)?.let { return Result(true, it, "", 1f, deferToFinal = true) }
        // Media ("آهنگ X رو پخش کن", "ویدئوی X رو باز کن", "یوتیوب رو باز کن"): real Intent, final result only.
        MediaCommandParser.parse(text)?.let { return Result(true, it, "", 1f, deferToFinal = true) }
        val parsed = parser.parse(text)
        val ok = parsed.action !is JarvisAction.Unknown && parsed.confidence >= CommandConfidence.THRESHOLD
        // Alarm / Timer / "needs info" speak their own result or question, so no "حتماً." in front of them.
        return if (ok) Result(true, parsed.action, if (parsed.action.speaksOwnResult) "" else JarvisPhrases.SURE, parsed.confidence)
        else Result(false, null, JarvisPhrases.NOT_UNDERSTOOD, parsed.confidence)
    }
}

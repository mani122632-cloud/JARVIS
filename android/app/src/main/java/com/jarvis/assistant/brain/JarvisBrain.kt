package com.jarvis.assistant.brain

import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.conversation.ConversationContext
import com.jarvis.assistant.speech.JarvisPhrases

enum class BrainKind { COMMAND, CONVERSATION, UNKNOWN }

/**
 * What the Brain decided about one Persian utterance. [responseText] is always what JARVIS says (through
 * the offline TTS); only [Command] carries something to execute.
 */
sealed class BrainResult {
    abstract val kind: BrainKind
    abstract val responseText: String

    /** A known device command: say [responseText] ("حتماً."), then run [action]. */
    data class Command(
        val action: JarvisAction,
        override val responseText: String,
        val confidence: Float
    ) : BrainResult() {
        override val kind: BrainKind get() = BrainKind.COMMAND
    }

    /** Small talk or a memory answer: say [responseText]; no Action. */
    data class Conversation(override val responseText: String) : BrainResult() {
        override val kind: BrainKind get() = BrainKind.CONVERSATION
    }

    /** Not understood: say "متوجه نشدم."; nothing is executed. */
    data class Unknown(override val responseText: String = JarvisPhrases.NOT_UNDERSTOOD) : BrainResult() {
        override val kind: BrainKind get() = BrainKind.UNKNOWN
    }
}

/**
 * JARVIS Brain (Stage 46.4): Persian text in, structured decision out.
 *
 *   Voice Input -> Brain -> Command Parser / Conversation -> Action Executor -> TTS
 *
 * The Brain decides; it never speaks and never executes. Implementations must not throw.
 */
interface JarvisBrain {
    /**
     * Decides what to do with a COMPLETE utterance. May have side effects (explicit memory commands), so the
     * caller invokes it once per utterance, never for partial recognition results.
     */
    fun think(text: String): BrainResult

    /**
     * Same decision inside a multi-turn session: the command system is still asked first, but a sentence that is
     * neither a command nor known small talk gets a short natural reply instead of [BrainResult.Unknown].
     * [context] is the bounded history of this session.
     */
    fun think(text: String, context: ConversationContext): BrainResult = think(text)

    /**
     * Side-effect-free check for a still-growing partial result: true only for a complete device command
     * that is safe to run before the recognizer has finished.
     */
    fun isConfidentCommand(partialText: String): Boolean

    /**
     * Side-effect-free: of several recognizer alternatives for the SAME utterance, the first one the Brain can
     * actually handle (memory command, device command, small talk); otherwise the first alternative.
     */
    fun pickBest(candidates: List<String>): String = candidates.firstOrNull().orEmpty()
}

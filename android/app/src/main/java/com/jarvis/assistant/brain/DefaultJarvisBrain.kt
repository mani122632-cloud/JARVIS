package com.jarvis.assistant.brain

import android.util.Log
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisCommandProcessor
import com.jarvis.assistant.memory.JarvisMemory
import com.jarvis.assistant.memory.MemoryException

/**
 * The Stage 46.4 Brain. It sits ON TOP of the Stage 46.3 [JarvisCommandProcessor] (which wraps
 * CommandIntentParser); it does not replace it.
 *
 * Order for a complete utterance:
 *  1. explicit memory command (remember / recall / forget / clear)  -> CONVERSATION
 *  2. device command known to the processor, confidence >= threshold -> COMMAND
 *  3. fixed small talk (سلام / خوبی؟ / اسمت چیه؟)                    -> CONVERSATION
 *  4. anything else                                                  -> UNKNOWN ("متوجه نشدم.", nothing runs)
 *
 * Memory is written ONLY in step 1 for an explicit "remember" command; ordinary sentences are never stored.
 * User text is never logged. Never throws.
 */
class DefaultJarvisBrain(
    private val processor: JarvisCommandProcessor,
    private val memory: JarvisMemory,
    private val conversation: BasicConversation = BasicConversation()
) : JarvisBrain {

    /** Key of the last fact remembered or recalled, so "این رو فراموش کن" knows what "این" is. */
    private var lastKey: String? = null

    override fun think(text: String): BrainResult = try {
        thinkUnsafe(text)
    } catch (e: Exception) {
        Log.e(TAG, "Brain failed; treating the utterance as unknown", e)
        BrainResult.Unknown()
    }

    private fun thinkUnsafe(text: String): BrainResult {
        MemoryCommandParser.parse(text)?.let { return handleMemory(it) }

        val result = processor.process(text)
        val action = result.action
        if (result.handled && action != null && action !is JarvisAction.Unknown) {
            return BrainResult.Command(action, result.responseText, result.confidence)
        }

        conversation.reply(text)?.let { return BrainResult.Conversation(it) }
        return BrainResult.Unknown()
    }

    override fun isConfidentCommand(partialText: String): Boolean = try {
        MemoryCommandParser.parse(partialText) == null && processor.process(partialText).isHighConfidence
    } catch (e: Exception) {
        false
    }

    override fun pickBest(candidates: List<String>): String {
        val usable = candidates.filter { it.isNotBlank() }
        if (usable.isEmpty()) return candidates.firstOrNull().orEmpty()
        for (c in usable) {
            val handled = try {
                MemoryCommandParser.parse(c) != null || processor.process(c).handled || conversation.reply(c) != null
            } catch (e: Exception) { false }
            if (handled) return c
        }
        return usable.first()
    }

    // ---- memory ---------------------------------------------------------------------------------

    private fun handleMemory(intent: MemoryIntent): BrainResult = try {
        BrainResult.Conversation(runMemory(intent))
    } catch (e: MemoryException) {
        Log.w(TAG, "Memory operation failed: ${e.message}")
        BrainResult.Conversation(MEMORY_ERROR)
    }

    @Throws(MemoryException::class)
    private fun runMemory(intent: MemoryIntent): String = when (intent) {
        is MemoryIntent.Remember -> {
            memory.remember(intent.key, intent.value)
            lastKey = intent.key
            "باشه، به خاطر سپردم."
        }
        MemoryIntent.RememberUnclear -> "متوجه نشدم چه چیزی را به خاطر بسپارم. مثلاً بگویید: یادت باشه اسم من علی هست."
        is MemoryIntent.Recall -> {
            lastKey = intent.key
            val value = memory.recall(intent.key)
            if (value == null) "هنوز ${intent.key} شما را نمی‌دانم."
            else "${intent.key} شما $value است."
        }
        is MemoryIntent.ForgetKey -> {
            if (lastKey == intent.key) lastKey = null
            if (memory.forget(intent.key)) "فراموش کردم." else "چیزی درباره‌اش به خاطر ندارم."
        }
        MemoryIntent.ForgetLast -> {
            val key = lastKey
            if (key == null) {
                "کدام مورد را فراموش کنم؟ مثلاً بگویید: اسم من را فراموش کن."
            } else {
                lastKey = null
                if (memory.forget(key)) "فراموش کردم." else "چیزی درباره‌اش به خاطر ندارم."
            }
        }
        MemoryIntent.ClearAll -> {
            memory.clear()
            lastKey = null
            "همه چیز را فراموش کردم."
        }
    }

    private companion object {
        const val TAG = "JarvisBrain"
        const val MEMORY_ERROR = "نتوانستم حافظه را به‌روزرسانی کنم."
    }
}

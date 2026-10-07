package com.jarvis.assistant.brain

import android.util.Log
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisCommandProcessor
import com.jarvis.assistant.command.SlotTarget
import com.jarvis.assistant.command.MediaCommandParser
import com.jarvis.assistant.command.MediaTool
import com.jarvis.assistant.conversation.ConversationContext
import com.jarvis.assistant.conversation.MediaStage
import com.jarvis.assistant.conversation.PendingIntent
import com.jarvis.assistant.memory.JarvisMemory
import com.jarvis.assistant.memory.MemoryException
import com.jarvis.assistant.nlu.EndConversationDetector
import com.jarvis.assistant.nlu.PersianNormalizer
import com.jarvis.assistant.speech.JarvisPhrases

/**
 * The JARVIS Brain: Persian text in, structured decision out. It sits ON TOP of the [JarvisCommandProcessor]
 * (which wraps the NLU); it does not replace it, and it separates the layers:
 *
 *   INTENT      what the user wants          (END_CONVERSATION, alarm, timer, device command, memory, small talk)
 *   PARAMETERS  hour, duration, day ...      (extracted by the NLU; missing ones are asked for)
 *   CONTEXT     what is still open           (ConversationContext.pending: "آلارم بذار" -> "چه ساعتی؟" -> "هفت صبح")
 *   TOOL        how it is done on the phone  (JarvisActionExecutor -> JarvisTool, outside the Brain)
 *
 * Order for a complete utterance:
 *  0. an answer to a pending question ("هفت صبح") completes the pending Alarm / Timer       -> COMMAND
 *     ("ولش کن" cancels it)
 *  1. explicit memory command (remember / recall / forget / clear)                           -> CONVERSATION
 *  2. device command known to the processor, confidence >= threshold                         -> COMMAND
 *  3. END_CONVERSATION intent (structure based, see EndConversationDetector)                  -> END_CONVERSATION
 *  4. Alarm / Timer without a time                                                           -> CLARIFY (question)
 *  5. offline small talk / requests that need live data                                      -> CONVERSATION
 *  6. anything else                                                                          -> UNKNOWN ("متوجه نشدم.", nothing runs)
 *
 * A device command ALWAYS wins over an ending, so "تایمر ۵ دقیقه" is never mistaken for "تمام"; nothing here
 * touches the network. Memory is written ONLY in step 1 for an explicit "remember" command. User text is never
 * logged. Never throws.
 *
 * A future LLM Brain implements the same [JarvisBrain] interface and returns the same [BrainResult]s.
 */
class DefaultJarvisBrain(
    private val processor: JarvisCommandProcessor,
    private val memory: JarvisMemory,
    private val conversation: OfflineConversationBrain = OfflineConversationBrain(),
    private val slots: SlotFiller = SlotFiller(),
    /**
     * Online Brain Stage 1: true only while an online provider is configured AND reachable. While it is false
     * (the default) this Brain behaves exactly as before and never returns [BrainResult.Escalate].
     */
    private val canEscalate: () -> Boolean = { false }
) : JarvisBrain {

    /** Key of the last fact remembered or recalled, so "این رو فراموش کن" knows what "این" is. */
    private var lastKey: String? = null

    /** The session context of the last multi-turn [think]; lets [pickBest] know that a media question is open. */
    private var activeContext: ConversationContext? = null

    override fun think(text: String): BrainResult = try {
        decide(text, null)
    } catch (e: Exception) {
        Log.e(TAG, "Brain failed; treating the utterance as unknown", e)
        BrainResult.Unknown()
    }

    /** Steps 0-6 above. [ctx] is null for the single-turn [think]: then nothing can be pending or stored. */
    private fun decide(text: String, ctx: ConversationContext?): BrainResult {
        // 0. A question is open: the utterance is most likely its answer.
        val pending = ctx?.pending
        if (ctx != null && pending != null) {
            if (isCancel(text) && EndConversationDetector.detect(text) == null) {
                ctx.pending = null
                return BrainResult.Conversation(JarvisPhrases.CANCELLED)
            }
        }

        // 0b. The song dialog is open: the utterance answers it (or cancels it).
        if (ctx != null && ctx.mediaStage != null) handleMediaAnswer(text, ctx)?.let { return it }

        // 1. Explicit memory commands.
        MemoryCommandParser.parse(text)?.let { return handleMemory(it) }

        if (ctx != null && pending != null) {
            slots.fill(pending, text)?.let { action ->
                ctx.pending = null
                return BrainResult.Command(action, "", ANSWER_CONFIDENCE)
            }
            if (!isNewRequest(text)) {
                // Not an answer and not a new request: ask once more, then give up.
                if (pending.attempts >= MAX_RETRIES) {
                    ctx.pending = null
                    return BrainResult.Conversation(JarvisPhrases.NOT_UNDERSTOOD)
                }
                ctx.pending = pending.copy(attempts = pending.attempts + 1)
                return BrainResult.Clarify(askAgain(pending.target))
            }
            ctx.pending = null          // a new request replaces the open question; handled below
        }

        // 2. Device commands.
        val result = processor.process(text)
        val action = result.action
        val command: JarvisAction? =
            if (result.handled && action != null && action !is JarvisAction.Unknown) action else null
        // "آهنگ رو پخش کن" without a name: ask the language first, then the name (needs a session).
        if (ctx != null && MediaCommandParser.isNamelessMusic(command)) {
            ctx.pending = null
            ctx.mediaStage = MediaStage.ASK_LANGUAGE
            ctx.mediaLanguage = null
            ctx.mediaAttempts = 0
            return BrainResult.Clarify(ASK_SONG_LANGUAGE)
        }
        if (command != null && command !is JarvisAction.DismissAssistant && command !is JarvisAction.NeedsInfo) {
            return BrainResult.Command(command, result.responseText, result.confidence)
        }

        // 3. END_CONVERSATION intent ("خداحافظ", "من دیگه میرم", "کافیه" ...).
        EndConversationDetector.detect(text)?.let { return BrainResult.EndConversation(conversation.farewell(it.goodNight)) }

        // 4. Alarm / Timer without a time: ask, and remember what was already said.
        if (command is JarvisAction.NeedsInfo) {
            ctx?.pending = PendingIntent(command.target, command.dayOffset, command.period, command.preferMorning)
            return BrainResult.Clarify(question(command.target))
        }

        // "ولش کن" / "بیخیال" / "هیچی": a dismiss command; it may still get a spoken reply.
        if (command != null) {
            conversation.reply(text)?.let { return BrainResult.Conversation(it) }
            return BrainResult.Command(command, result.responseText, result.confidence)
        }

        // 5. Small talk / online-only requests.
        conversation.reply(text)?.let { return BrainResult.Conversation(it) }

        // 6.
        return BrainResult.Unknown()
    }

    private fun clearMedia(ctx: ConversationContext) {
        ctx.mediaStage = null
        ctx.mediaLanguage = null
        ctx.mediaAttempts = 0
    }

    /**
     * Answer to the open song question. Returns null only when the utterance is a different, complete request
     * (the dialog is then dropped and the normal order continues).
     */
    private fun handleMediaAnswer(text: String, ctx: ConversationContext): BrainResult? {
        val stage = ctx.mediaStage ?: return null
        // "لغو" ends the whole media dialog (stage, language, attempts) before anything else is looked at.
        if (isMediaCancel(text)) {
            clearMedia(ctx)
            return BrainResult.Conversation(JarvisPhrases.CANCELLED)
        }
        val ending = EndConversationDetector.detect(text)
        if (ending != null) { clearMedia(ctx); return null }
        return when (stage) {
            MediaStage.ASK_LANGUAGE -> {
                val lang = songLanguage(text)
                if (lang != null) {
                    ctx.mediaLanguage = lang
                    ctx.mediaStage = MediaStage.ASK_NAME
                    ctx.mediaAttempts = 0
                    return BrainResult.Clarify(ASK_SONG_NAME)
                }
                if (isNewRequest(text)) { clearMedia(ctx); return null }
                if (ctx.mediaAttempts >= MAX_RETRIES) {
                    clearMedia(ctx)
                    return BrainResult.Conversation(JarvisPhrases.NOT_UNDERSTOOD)
                }
                ctx.mediaAttempts++
                BrainResult.Clarify(ASK_SONG_LANGUAGE)
            }
            MediaStage.ASK_NAME -> {
                val name = mediaTokens(text)
                    .filter { it !in SONG_FILLERS }.joinToString(" ").trim()
                if (name.isEmpty()) {
                    if (ctx.mediaAttempts >= MAX_RETRIES) {
                        clearMedia(ctx)
                        return BrainResult.Conversation(JarvisPhrases.NOT_UNDERSTOOD)
                    }
                    ctx.mediaAttempts++
                    return BrainResult.Clarify(ASK_SONG_NAME)
                }
                val lang = ctx.mediaLanguage ?: "fa"
                clearMedia(ctx)
                val action = JarvisAction.ToolCall(MediaTool.NAME, mapOf("kind" to MediaTool.KIND_MUSIC, "query" to name, "lang" to lang))
                BrainResult.Command(action, "", ANSWER_CONFIDENCE)
            }
        }
    }

    /** "fa" / "en" / null. Own tokenizer: Latin letters must survive ("english"). */
    private fun songLanguage(text: String): String? {
        val t = mediaTokens(text)
        val fa = t.any { it.startsWith("فارسی") || it.startsWith("پارسی") || it == "farsi" || it == "persian" || it == "fa" }
        val en = t.any {
            it.startsWith("انگلیسی") || it.startsWith("اینگلیسی") || it.startsWith("انگلیش") || it.startsWith("اینگلیش") ||
                it == "english" || it == "en"
        }
        return when { fa && !en -> "fa"; en && !fa -> "en"; else -> null }
    }

    /** Lower-case words; ZWNJ / punctuation are separators; Arabic ي ك -> ی ک; Latin and digits are kept. */
    private fun mediaTokens(text: String): List<String> {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch == 'ي' -> sb.append('ی')
                ch == 'ك' -> sb.append('ک')
                ch in '\u064B'..'\u065F' || ch == '\u0670' -> { }
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }
    }

    /** "لغو" / "ولش کن" / "بیخیال" ... while the media question is open. */
    private fun isMediaCancel(text: String): Boolean {
        val t = mediaTokens(text)
        if (t.isEmpty() || t.size > 4) return false
        if (t.any { it in MEDIA_CANCEL_WORDS }) return true
        return "ول" in t && "کن" in t
    }

    private fun question(target: SlotTarget): String =
        if (target == SlotTarget.ALARM) JarvisPhrases.ASK_ALARM_TIME else JarvisPhrases.ASK_TIMER_DURATION

    private fun askAgain(target: SlotTarget): String =
        if (target == SlotTarget.ALARM) JarvisPhrases.ASK_ALARM_TIME_AGAIN else JarvisPhrases.ASK_TIMER_DURATION_AGAIN

    /** Cancels an open question: "ولش کن", "بیخیال", "لغو", "نمیخوام", "هیچی". */
    private fun isCancel(text: String): Boolean {
        val tokens = PersianNormalizer.tokens(text)
        return tokens.size <= 4 && tokens.any { it in CANCEL_WORDS }
    }

    /** True when the utterance is a command or an ending by itself, so it must not be treated as a failed answer. */
    private fun isNewRequest(text: String): Boolean {
        val r = processor.process(text)
        val a = r.action
        return (r.handled && a != null && a !is JarvisAction.Unknown) || EndConversationDetector.detect(text) != null
    }

    /**
     * Multi-turn variant. Same order as [think] plus the open question in [context]; only the very last step
     * differs: instead of UNKNOWN, a short natural reply from [OfflineConversationBrain.chat].
     * Input cleanup only removes leading discourse words ("راستی برو اینستاگرام"); the command parser itself is
     * not touched and no second parser exists.
     */
    override fun think(text: String, context: ConversationContext): BrainResult = try {
        activeContext = context
        val cleaned = stripLeadIns(text)
        val result = decide(cleaned, context)
        if (result is BrainResult.Unknown && cleaned.isNotBlank()) {
            val offline =
                if (looksLikeCommandAttempt(cleaned)) BrainResult.Conversation(JarvisPhrases.NOT_UNDERSTOOD)
                else BrainResult.Conversation(conversation.chat(cleaned, context.recentResponses))
            // Not a local command, not small talk: a complex / conversational request.
            if (shouldEscalate()) BrainResult.Escalate(cleaned, offline) else offline
        } else if (result is BrainResult.Conversation && cleaned.isNotBlank() && shouldEscalate() &&
            MemoryCommandParser.parse(cleaned) == null &&
            conversation.classify(cleaned) == OfflineConversationBrain.Topic.ONLINE_REQUIRED
        ) {
            // "Needs live data" (weather, news, ...): offline can only say "I must be online"; try online first.
            BrainResult.Escalate(cleaned, result)
        } else result
    } catch (e: Exception) {
        Log.e(TAG, "Brain failed; treating the utterance as unknown", e)
        BrainResult.Unknown()
    }

    private fun shouldEscalate(): Boolean = try { canEscalate() } catch (e: Exception) { false }

    /** Drops leading "راستی / خب / حالا ..." so a command said mid-conversation reaches the parser unchanged. */
    private fun stripLeadIns(text: String): String {
        val tokens = PersianNormalizer.tokens(text)
        var i = 0
        while (i < tokens.size && tokens[i] in LEAD_INS) i++
        if (i == 0 || i >= tokens.size) return text
        return tokens.drop(i).joinToString(" ")
    }

    /** An unparsed sentence that starts like an order must not get a chatty answer. */
    private fun looksLikeCommandAttempt(text: String): Boolean {
        val tokens = PersianNormalizer.tokens(text)
        return tokens.size <= 5 && tokens.any { it in COMMAND_VERBS }
    }

    override fun isConfidentCommand(partialText: String): Boolean = try {
        val r = processor.process(partialText)
        val a = r.action
        // Alarm / timer / questions / endings are never run from a still-growing partial result: "ساعت ۷" may become
        // "ساعت ۷ و نیم", "۲۰ دقیقه" may become "۲۰ دقیقه و نیم".
        val partialSafe = a != null && a !is JarvisAction.CreateAlarm && a !is JarvisAction.CreateTimer &&
            a !is JarvisAction.NeedsInfo && a !is JarvisAction.ToolCall && a !is JarvisAction.DismissAssistant
        MemoryCommandParser.parse(partialText) == null && r.isHighConfidence && partialSafe &&
            EndConversationDetector.detect(partialText) == null
    } catch (e: Exception) {
        false
    }

    override fun pickBest(candidates: List<String>): String {
        val usable = candidates.filter { it.isNotBlank() }
        if (usable.isEmpty()) return candidates.firstOrNull().orEmpty()
        // A media question is open: the right alternative is the one that answers it, not one that happens to be a command.
        when (activeContext?.mediaStage) {
            MediaStage.ASK_LANGUAGE ->
                usable.firstOrNull { songLanguage(it) != null || isMediaCancel(it) }?.let { return it }
            MediaStage.ASK_NAME -> {
                usable.firstOrNull { isMediaCancel(it) }?.let { return it }
                return usable.first()
            }
            null -> { }
        }
        for (c in usable) {
            val handled = try {
                MemoryCommandParser.parse(c) != null || processor.process(c).handled ||
                    EndConversationDetector.detect(c) != null || conversation.classify(c) != null
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
        val LEAD_INS = setOf("راستی", "خب", "خو", "حالا", "ببین", "راستش", "میگم", "آها", "اها", "اوه", "عه", "هی", "جارویس", "ارباب")
        val COMMAND_VERBS = setOf(
            "برو", "باز", "بازکن", "ببند", "روشن", "خاموش", "بزن", "بذار", "بزار", "بگذار", "زیاد", "کم", "بیار", "اجرا"
        )
        const val MEMORY_ERROR = "نتوانستم حافظه را به‌روزرسانی کنم."
        const val MAX_RETRIES = 1
        const val ASK_SONG_LANGUAGE = "اسم آهنگ رو فارسی می‌گی یا انگلیسی؟"
        const val ASK_SONG_NAME = "اسم آهنگ رو بگو"
        val SONG_FILLERS = setOf(
            "آهنگ", "اهنگ", "موزیک", "اسمش", "اسم", "رو", "را", "پخش", "کن", "بذار", "بزن", "لطفا", "یه", "یک", "اسمشو", "اینه", "هست", "است",
            "song", "music", "play"
        )
        val MEDIA_CANCEL_WORDS = setOf("لغو", "کنسل", "ولش", "بیخیال", "نمیخوام", "منصرف", "cancel", "stop", "فراموشش", "هیچی", "بیخیالش")
        const val ANSWER_CONFIDENCE = 0.95f
        val CANCEL_WORDS = setOf("لغو", "کنسل", "ولش", "بیخیال", "نمیخوام", "هیچی", "فراموشش", "منصرف")
    }
}

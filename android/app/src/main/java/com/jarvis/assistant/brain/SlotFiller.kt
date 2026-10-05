package com.jarvis.assistant.brain

import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.SlotTarget
import com.jarvis.assistant.conversation.PendingIntent
import com.jarvis.assistant.nlu.PersianNormalizer
import com.jarvis.assistant.nlu.PersianTimeParser

/**
 * Turns the user's ANSWER to a clarifying question into the finished action.
 *
 *   "یه آلارم بذار" -> "چه ساعتی ارباب؟" -> "هفت صبح"  => CreateAlarm(7, 0)
 *   "یه تایمر بذار" -> "برای چند دقیقه؟"  -> "بیست"     => CreateTimer(1200)
 *
 * The answer is read with the same [PersianTimeParser] as a full sentence, but a bare number is accepted
 * ("هفت", "هفت و نیم", "۷:۳۰", "بیست" = minutes), and what was already said ([PendingIntent]: day, part of day)
 * is applied. Returns null when the text holds no usable answer. Pure.
 */
class SlotFiller(private val time: PersianTimeParser = PersianTimeParser()) {

    fun fill(pending: PendingIntent, text: String): JarvisAction? {
        val tokens = PersianNormalizer.tokens(text)
        if (tokens.isEmpty()) return null
        return when (pending.target) {
            SlotTarget.TIMER -> {
                val seconds = time.parseDuration(tokens, bareUnitSeconds = 60)
                if (seconds != null && seconds in 1..MAX_TIMER_SECONDS) JarvisAction.CreateTimer(seconds) else null
            }
            SlotTarget.ALARM -> fillAlarm(pending, tokens)
        }
    }

    private fun fillAlarm(pending: PendingIntent, tokens: List<String>): JarvisAction? {
        // "پنج دقیقه دیگه"
        val duration = time.parseDuration(tokens)
        if (duration != null && time.isRelative(tokens) && duration in 1..MAX_TIMER_SECONDS) {
            val at = time.afterDuration(duration)
            return JarvisAction.CreateAlarm(at.hour, at.minute, 0)
        }
        val dayOffset = time.dayOffset(tokens) ?: pending.dayOffset
        val clock = time.parseClock(
            tokens,
            bare = true,
            defaultPeriod = pending.period,
            preferMorning = pending.preferMorning || dayOffset >= 1
        ) ?: return null
        return JarvisAction.CreateAlarm(clock.hour, clock.minute, dayOffset)
    }

    private companion object {
        const val MAX_TIMER_SECONDS = 86_400
    }
}

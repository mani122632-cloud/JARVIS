package com.jarvis.assistant.conversation

import com.jarvis.assistant.command.DayPeriod
import com.jarvis.assistant.command.SlotTarget

/**
 * An Alarm / Timer request that is still missing its time. JARVIS has asked ("چه ساعتی ارباب؟") and the NEXT
 * utterance is expected to be the answer ("هفت صبح"). Holds what was already said so nothing is asked twice:
 * "فردا صبح بیدارم کن" -> [dayOffset] 1, [period] AM, only the hour is missing.
 *
 * Lives in [ConversationContext] (RAM, one session). [attempts] counts how often the answer could not be
 * understood; the Brain gives up after a retry instead of asking forever.
 */
data class PendingIntent(
    val target: SlotTarget,
    val dayOffset: Int = 0,
    val period: DayPeriod? = null,
    val preferMorning: Boolean = false,
    val attempts: Int = 0
)

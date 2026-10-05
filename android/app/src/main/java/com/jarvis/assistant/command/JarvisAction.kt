package com.jarvis.assistant.command

enum class TorchMode { ON, OFF, TOGGLE }

/** How the media volume should change. */
sealed interface VolumeChange {
    object Up : VolumeChange
    object Down : VolumeChange
    /** Absolute level, 0..100. */
    data class Percent(val percent: Int) : VolumeChange
}

/** Part of the day a spoken hour belongs to ("هفت صبح", "ده شب"). */
enum class DayPeriod { AM, NOON, AFTERNOON, NIGHT }

/** What JARVIS still has to ask the user before an Alarm / Timer can be created. */
enum class SlotTarget { ALARM, TIMER }

/**
 * Everything JARVIS can do after a command. Pure data: no Android types, so the parser stays unit-testable.
 *
 * Layers (Stage 46.5): the NLU decides the INTENT and extracts its PARAMETERS -> one of these actions;
 * the [ConversationContext][com.jarvis.assistant.conversation.ConversationContext] keeps incomplete parameters
 * between turns; a [JarvisTool] (see ToolRegistry) EXECUTES the action. A future LLM brain only has to produce
 * the same actions (or a generic [ToolCall]).
 *
 * Adding an action: (1) add a subclass here, (2) add a rule to `CommandIntentParser`, (3) handle it in a
 * `JarvisTool` registered with the executor (or in `JarvisActionExecutor.dispatch`, whose `when` is exhaustive).
 */
sealed class JarvisAction {

    /**
     * True when the executor itself says the result ("آلارم ساعت هفت تنظیم شد."), so the Brain must not
     * say "حتماً." first.
     */
    open val speaksOwnResult: Boolean get() = false

    /** [packageNames]: candidate package ids, the first installed one is launched. */
    data class OpenApp(val appId: String, val label: String, val packageNames: List<String>) : JarvisAction()
    object OpenSettings : JarvisAction()
    object OpenWifiSettings : JarvisAction()
    object OpenBluetoothSettings : JarvisAction()
    data class ToggleFlashlight(val mode: TorchMode) : JarvisAction()
    data class SetVolume(val change: VolumeChange) : JarvisAction()

    /**
     * [hour] 0..23, [minute] 0..59. [dayOffset]: 0 = "the next time it is that hour" (today, or tomorrow if it
     * has already passed), 1 = explicitly tomorrow ("فردا ساعت ۷").
     */
    data class CreateAlarm(val hour: Int, val minute: Int, val dayOffset: Int = 0) : JarvisAction() {
        override val speaksOwnResult: Boolean get() = true
    }

    /** [seconds] 1..86400. */
    data class CreateTimer(val seconds: Int) : JarvisAction() {
        override val speaksOwnResult: Boolean get() = true
    }

    /**
     * An Alarm / Timer was asked for but the time is missing ("آلارم بذار"). Never executed: the Brain turns it
     * into a question and remembers the partial information in the conversation context.
     * [dayOffset] / [period] / [preferMorning] carry what was already said ("فردا صبح بیدارم کن").
     */
    data class NeedsInfo(
        val target: SlotTarget,
        val dayOffset: Int = 0,
        val period: DayPeriod? = null,
        val preferMorning: Boolean = false
    ) : JarvisAction() {
        override val speaksOwnResult: Boolean get() = true
    }

    /** Opens the clock app's timer / alarm screen ("تایمرها را نشان بده"). Only for an explicit "show" request. */
    object OpenTimerScreen : JarvisAction()
    object OpenAlarmScreen : JarvisAction()
    object GoHome : JarvisAction()
    object GoBack : JarvisAction()
    object DismissAssistant : JarvisAction()

    /**
     * Generic call of a registered tool by name (for future tools and a future LLM brain), e.g.
     * `ToolCall("alarm", mapOf("hour" to "7", "minute" to "30"))`.
     */
    data class ToolCall(val tool: String, val args: Map<String, String> = emptyMap()) : JarvisAction() {
        override val speaksOwnResult: Boolean get() = true
    }

    /** Nothing recognized. Never executed. */
    data class Unknown(val rawText: String) : JarvisAction()
}

package com.jarvis.assistant.nlu

import com.jarvis.assistant.command.JarvisAction

/**
 * The intents the NLU layer reports (Stage 46.6). Natural variants of one request all map to one intent:
 * "به علی زنگ بزن" / "به علی تماس بگیر" / "با علی تماس بگیر" -> [CALL_CONTACT] with slot contact_name = علی.
 */
enum class NluIntent {
    CALL_CONTACT,
    OPEN_APP,
    TOGGLE_FLASHLIGHT,
    SET_VOLUME,
    OPEN_SETTINGS,
    GO_HOME,
    GO_BACK,
    OPEN_WIFI_SETTINGS,
    OPEN_BLUETOOTH_SETTINGS,

    /** A command the parser understands but that is not one of the intents above (alarm, timer, dismiss...). */
    OTHER,

    /** Nothing matched. */
    UNKNOWN
}

/** Slot names (keys of [NluResult.slots]) and their fixed values. */
object NluSlot {
    /** CALL_CONTACT: the spoken contact name, e.g. "علی", "مامان", "دکتر احمدی". */
    const val CONTACT_NAME = "contact_name"

    /** CALL_CONTACT: a spoken phone number (digits only) when the user said a number instead of a name. */
    const val PHONE_NUMBER = "phone_number"

    /** OPEN_APP: registry id and display label of the app. */
    const val APP_ID = "app_id"
    const val APP_NAME = "app_name"

    /** TOGGLE_FLASHLIGHT: [MODE_ON] / [MODE_OFF] / [MODE_TOGGLE]. */
    const val MODE = "mode"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"
    const val MODE_TOGGLE = "toggle"

    /** SET_VOLUME: [ACTION_SET] (with [PERCENT]) / [ACTION_UP] / [ACTION_DOWN]. */
    const val ACTION = "action"
    const val ACTION_SET = "set"
    const val ACTION_UP = "up"
    const val ACTION_DOWN = "down"
    const val PERCENT = "percent"
}

/**
 * Intent + Slots + Confidence for one Persian utterance.
 *
 * [missingSlots] lists required slots the sentence did not contain (e.g. "زنگ بزن" -> contact_name), so a later
 * stage can ask for them. [action] is the matching legacy [JarvisAction] when one exists (null for CALL_CONTACT
 * and UNKNOWN), so the existing executor keeps working unchanged.
 */
data class NluResult(
    val intent: NluIntent,
    val slots: Map<String, String>,
    val confidence: Float,
    val normalized: String,
    val missingSlots: List<String> = emptyList(),
    val action: JarvisAction? = null
) {
    /** Confident enough to act on (same threshold as the rest of the command pipeline). */
    val isConfident: Boolean
        get() = intent != NluIntent.UNKNOWN && confidence >= CommandConfidence.THRESHOLD

    fun slot(name: String): String? = slots[name]
}

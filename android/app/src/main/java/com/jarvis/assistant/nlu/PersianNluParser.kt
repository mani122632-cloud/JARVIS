package com.jarvis.assistant.nlu

import com.jarvis.assistant.command.AppRegistry
import java.time.LocalTime

/**
 * Persian sentence -> [NluResult] (Intent + Slots + Confidence), Stage 46.6.
 *
 * Runs the two offline, deterministic parsers side by side and keeps the more confident one (ties go to the call
 * parser, because "به تلگرام زنگ بزن" is a call and not "open Telegram"):
 *
 *   [CallContactParser]    CALL_CONTACT      (contact_name | phone_number)
 *   [CommandIntentParser]  OPEN_APP · TOGGLE_FLASHLIGHT · SET_VOLUME · OPEN_SETTINGS · GO_HOME · GO_BACK ·
 *                          OPEN_WIFI_SETTINGS · OPEN_BLUETOOTH_SETTINGS (+ OTHER: alarm / timer / dismiss)
 *
 * No AI, no network, no state. The existing [CommandIntentParser.parse] API is unchanged; this is the entry point
 * for callers that want the intent and its slots.
 */
class PersianNluParser(
    apps: AppRegistry = AppRegistry.default(),
    clock: () -> LocalTime = { LocalTime.now() }
) {

    private val commands = CommandIntentParser(apps, clock)
    private val calls = CallContactParser()

    fun parse(raw: String): NluResult {
        val cmd = commands.parse(raw)
        val call = calls.match(PersianNormalizer.tokens(raw))

        if (call != null && call.confidence >= cmd.confidence) {
            val slots = LinkedHashMap<String, String>()
            call.contactName?.let { slots[NluSlot.CONTACT_NAME] = it }
            call.phoneNumber?.let { slots[NluSlot.PHONE_NUMBER] = it }
            val missing = if (slots.isEmpty()) listOf(NluSlot.CONTACT_NAME) else emptyList()
            return NluResult(NluIntent.CALL_CONTACT, slots, call.confidence, cmd.normalized, missing, null)
        }
        return NluResult(cmd.intent, cmd.slots, cmd.confidence, cmd.normalized, emptyList(), cmd.action)
    }
}

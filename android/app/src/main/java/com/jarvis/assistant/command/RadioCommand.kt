package com.jarvis.assistant.command

/** Reserved [JarvisAction.OpenAppByName] queries that mean "switch Wi-Fi / Bluetooth on / off" (parser -> executor). */
object RadioCommand {
    private const val PREFIX = "__jarvis_radio_"
    fun query(wifi: Boolean, on: Boolean) = PREFIX + (if (wifi) "wifi" else "bt") + (if (on) "_on__" else "_off__")
    /** (isWifi, enable) or null when [q] is an ordinary app name. */
    fun parse(q: String): Pair<Boolean, Boolean>? = when (q) {
        query(true, true) -> true to true
        query(true, false) -> true to false
        query(false, true) -> false to true
        query(false, false) -> false to false
        else -> null
    }
}

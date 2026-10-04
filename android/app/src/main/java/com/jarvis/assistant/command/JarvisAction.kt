package com.jarvis.assistant.command

enum class TorchMode { ON, OFF, TOGGLE }

/** How the media volume should change. */
sealed interface VolumeChange {
    object Up : VolumeChange
    object Down : VolumeChange
    /** Absolute level, 0..100. */
    data class Percent(val percent: Int) : VolumeChange
}

/**
 * Everything JARVIS can do after a command (Stage 46.3). Pure data: no Android types, so the parser stays
 * unit-testable.
 *
 * Adding an action: (1) add a subclass here, (2) add a rule to `CommandIntentParser`, (3) handle it in
 * `JarvisActionExecutor.dispatch` — the `when` there is exhaustive, so the compiler flags a missing branch.
 */
sealed class JarvisAction {
    /** [packageNames]: candidate package ids, the first installed one is launched. */
    data class OpenApp(val appId: String, val label: String, val packageNames: List<String>) : JarvisAction()
    object OpenSettings : JarvisAction()
    object OpenWifiSettings : JarvisAction()
    object OpenBluetoothSettings : JarvisAction()
    data class ToggleFlashlight(val mode: TorchMode) : JarvisAction()
    data class SetVolume(val change: VolumeChange) : JarvisAction()
    /** [hour] 0..23, [minute] 0..59. */
    data class CreateAlarm(val hour: Int, val minute: Int) : JarvisAction()
    /** [seconds] 1..86400. */
    data class CreateTimer(val seconds: Int) : JarvisAction()
    object GoHome : JarvisAction()
    object GoBack : JarvisAction()
    object DismissAssistant : JarvisAction()
    /** Nothing recognized. Never executed. */
    data class Unknown(val rawText: String) : JarvisAction()
}

package com.jarvis.assistant.command

/** What JARVIS should do after understanding a command. Executed by [JarvisActionExecutor]. */
sealed class JarvisAction {
    /** Launch an installed app. [label] is the spoken name, used for "not installed" messages. */
    data class OpenApp(val packageName: String, val label: String) : JarvisAction()

    /** The user wants to stop ("بیخیال"). Nothing to execute; the conversation ends. */
    object DismissAssistant : JarvisAction()
}

/**
 * @param handled false when the command was not understood (then [action] is null).
 * @param responseText short sentence spoken before the action runs.
 */
data class JarvisCommandResult(
    val handled: Boolean,
    val responseText: String,
    val action: JarvisAction? = null
)

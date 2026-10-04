package com.jarvis.assistant.command

/**
 * Turns recognized text into a [JarvisCommandResult]. Pure logic: no Android, no side effects.
 * Only commands that are really implemented are recognized; everything else gets the fallback.
 */
class JarvisCommandProcessor {

    fun process(command: String): JarvisCommandResult {
        val text = normalize(command)
        if (text.isEmpty()) return notUnderstood()

        if (DISMISS_WORDS.any { text == it || text.contains(it) }) {
            return JarvisCommandResult(true, "باشه.", JarvisAction.DismissAssistant)
        }

        val compact = text.replace(" ", "")
        val mentionsInstagram = INSTAGRAM_WORDS.any { compact.contains(it) }
        val otherVerb = text.split(" ").any { it in NON_OPEN_WORDS }
        if (mentionsInstagram && !otherVerb) {
            return JarvisCommandResult(
                handled = true,
                responseText = "حتماً.",
                action = JarvisAction.OpenApp(INSTAGRAM_PACKAGE, "اینستاگرام")
            )
        }
        return notUnderstood()
    }

    private fun notUnderstood() = JarvisCommandResult(false, "متوجه نشدم.", null)

    /** Unifies Arabic/Persian letters, drops diacritics, punctuation and half-spaces. */
    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace('ي', 'ی').replace('ك', 'ک').replace('ى', 'ی')
            .replace(Regex("[\u064B-\u065F\u0670]"), "")
            .replace('\u200c', ' ')
            .replace(Regex("[\\p{Punct}،؛؟!]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        val INSTAGRAM_WORDS = listOf("اینستاگرام", "اینستگرام", "اینستا", "instagram", "insta")
        // "اینستاگرام رو ببند" must not open it.
        val NON_OPEN_WORDS = setOf("ببند", "ببندید", "حذف", "پاک", "نصب", "آنینستال")
        val DISMISS_WORDS = listOf("بیخیال", "هیچی", "کافیه", "خداحافظ", "لغو")
    }
}

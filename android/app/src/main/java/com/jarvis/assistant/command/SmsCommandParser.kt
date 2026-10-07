package com.jarvis.assistant.command

/**
 * Persian SMS command -> `ToolCall("send_sms", {name, text})`. Pure (no Android), runs before the general parser.
 *
 *  - «به [مخاطب] پیام بده، بگو [متن]»
 *  - «به [مخاطب] بگو [متن]»
 *  - «برای [مخاطب] پیام بفرست که [متن]»
 *  - «به [مخاطب] اس‌ام‌اس بده و بگو [متن]»
 *
 * A missing text yields a call WITHOUT "text" (SmsTool then asks for it, nothing is sent).
 */
object SmsCommandParser {

    private const val ZW = "[\\u200c ]?"
    private val OPT = RegexOption.DOT_MATCHES_ALL

    private val SMS_NOUN = "(?:پیام(?:ک)?|اس${ZW}ام${ZW}اس|اسمس|sms)"
    private val SEND_VERB = "(?:بده|بفرست|بزن|بنویس|ارسال\\s+کن|بفرستید|بدید)"

    /** «به/برای X پیام/اس‌ام‌اس بده|بفرست ... [بگو|بنویس|که] متن» */
    private val withNoun = Regex(
        "^(?:لطفا\\s+)?(?:به|برای)\\s+(.+?)\\s+$SMS_NOUN\\s+$SEND_VERB" +
            "(?:\\s*[,،.:]?\\s*(?:و\\s+)?(?:(?:بگو|بنویس)(?:\\s+که)?|که))?\\s*[,،.:]?\\s*(.*)$",
        setOf(OPT, RegexOption.IGNORE_CASE)
    )

    /** «به X بگو متن» */
    private val sayTo = Regex(
        "^(?:لطفا\\s+)?به\\s+(.+?)\\s+(?:بگو|بنویس)(?:\\s+که)?\\s*[,،.:]?\\s*(.*)$", OPT
    )

    private val NOT_A_CONTACT = setOf("من", "ما", "او", "اون", "ایشون", "شما", "تو", "خودم", "جارویس", "ژارویس")

    fun parse(raw: String): JarvisAction.ToolCall? {
        val s = prepare(raw)
        if (s.isEmpty()) return null
        val m = withNoun.find(s) ?: sayTo.find(s) ?: return null
        val name = m.groupValues[1].trim().trim('،', ',', '.', ':')
        val text = m.groupValues[2].trim().trim('،', ',').trim()
        if (name.isEmpty() || name.split(Regex("\\s+")).size > 4) return null
        if (ContactLookupNormalizer.norm(name) in NOT_A_CONTACT) return null
        val args = LinkedHashMap<String, String>()
        args["name"] = name
        if (text.isNotEmpty()) args["text"] = text
        return JarvisAction.ToolCall(SmsTool.NAME, args)
    }

    private fun prepare(raw: String): String = buildString(raw.length) {
        for (ch in raw.trim()) append(
            when (ch) { 'ي', 'ى' -> 'ی'; 'ك' -> 'ک'; else -> ch }
        )
    }.replace(Regex("\\s+"), " ")

    private object ContactLookupNormalizer {
        fun norm(s: String) = ContactLookup.normalize(s)
    }
}

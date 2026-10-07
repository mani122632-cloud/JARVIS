package com.jarvis.assistant.command

import com.jarvis.assistant.nlu.PersianNormalizer

/**
 * Offline fast path for "به علی زنگ بزن" / "با مامان تماس بگیر" / "call Ali" -> ToolCall("call_contact", name).
 * Pure text -> action. Never dials: CallTool looks the contact up and refuses (with a question) when the name is
 * missing, unknown or ambiguous. Alarm / timer sentences ("ساعت ۷ زنگ بزن") are NOT calls and return null.
 */
object CallCommandParser {

    fun parse(raw: String): JarvisAction? {
        val t = PersianNormalizer.tokens(raw)
        if (t.isEmpty()) return null
        if (t.any { it in NEGATIONS || it in NOT_A_CALL || it.any { c -> c.isDigit() } }) return null

        val ring = t.contains("زنگ") && t.any { it in RING_VERBS }
        val contact = t.contains("تماس") && t.any { it in CONTACT_VERBS }
        val dial = t.contains("شماره") && t.any { it == "بگیر" || it == "بگیری" || it == "بگیرید" }
        val english = t.contains("call") || t.contains("phone")
        if (!ring && !contact && !dial && !english) return null

        val name = t.filter { it !in NOISE }
        if (name.size > 4) return null            // a long sentence is not a call command: leave it to the online brain
        val args = if (name.isEmpty()) emptyMap() else mapOf("name" to name.joinToString(" "))
        return JarvisAction.ToolCall(CallTool.NAME, args)
    }

    private val NEGATIONS = setOf("نکن", "نه", "نمیخوام", "نزن", "نگیر")
    private val NOT_A_CALL = setOf(
        "آلارم", "الارم", "alarm", "تایمر", "timer", "ساعت", "دقیقه", "ثانیه", "فردا", "صبح", "شب", "ظهر", "عصر",
        "بیدارم", "بیدار", "پیام", "پیامک", "اس", "sms"
    )
    private val RING_VERBS = setOf("بزن", "بزنی", "بزنید", "بزنم", "بزن‌")
    private val CONTACT_VERBS = setOf("بگیر", "بگیری", "بگیرید", "برقرار", "بده", "بدی", "بدید")
    private val NOISE = setOf(
        "زنگ", "تماس", "شماره", "تلفن", "موبایل", "بزن", "بزنی", "بزنید", "بزنم", "بگیر", "بگیری", "بگیرید",
        "برقرار", "بده", "بدی", "بدید", "کن", "بکن", "کنید", "call", "phone", "to",
        "به", "با", "برای", "رو", "را", "ی", "یه", "یک", "لطفا", "من", "منو", "ممنون", "مرسی", "جارویس", "جارویز",
        "هی", "ارباب", "بله", "میشه", "میتونی", "تونی", "می", "شه", "ممکنه", "جان", "عزیزم", "مخاطب", "اون", "این"
    )
}

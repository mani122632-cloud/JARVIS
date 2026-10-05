package com.jarvis.assistant.brain

import com.jarvis.assistant.conversation.ConversationContext
import com.jarvis.assistant.nlu.PersianNormalizer
import com.jarvis.assistant.speech.JarvisPhrases

/**
 * Offline small talk (rule based, NOT a language model, no network). An utterance is matched by intent over
 * its normalized tokens; each intent has a few Persian variants, and the one that was not said recently in
 * this session is preferred (see [ConversationContext]). No match = null (the Brain then says "متوجه نشدم.").
 *
 * JARVIS only claims what it really is: an offline voice assistant on this phone. It never says it is
 * ChatGPT / an online AI, and offers only abilities it has (apps, flashlight, volume, timer, alarm, home).
 * Add a phrase: put its normalized form (see [PersianNormalizer]) in one of the sets in the companion.
 */
class BasicConversation {

    private var cursor = 0

    /** Farewell detection for the whole utterance (خداحافظ / فعلاً / تمام / دیگه کاری ندارم ...). */
    fun isFarewell(text: String): Boolean {
        val tokens = PersianNormalizer.tokens(text).filter { it !in FILLER && it !in THANKS }
        if (tokens.isEmpty()) return false
        return tokens.joinToString(" ") in FAREWELLS
    }

    fun farewell(context: ConversationContext? = null): String = pick(GOODBYES, context)

    fun reply(text: String, context: ConversationContext? = null): String? {
        val all = PersianNormalizer.tokens(text)
        val tokens = all.filter { it !in FILLER }
        // Only filler said ("جارویس" / "بله" / "ارباب"): the user is calling JARVIS, answer like the activation.
        if (tokens.isEmpty()) return if (all.isNotEmpty()) JarvisPhrases.ACK else null
        val phrase = tokens.joinToString(" ")
        val set = tokens.toSet()
        val small = tokens.size <= MAX_SMALL_TALK_TOKENS

        return when {
            // honest identity first: "تو ChatGPT هستی؟"
            ("chatgpt" in set || ("چت" in set && "جی" in set) || "gpt" in set) -> pick(NOT_CHATGPT, context)

            phrase in GREETINGS || (tokens.size <= 3 && tokens.all { it in GREETING_WORDS }) ->
                pick(GREETING_REPLIES, context)

            set.any { it in THANKS } && small && tokens.all { it in THANKS || it in POLITE } -> pick(THANKS_REPLIES, context)

            phrase in WHATS_UP || ("چه" in set && "خبر" in set && small) -> pick(WHATS_UP_REPLIES, context)

            phrase in HOW_ARE_YOU || ("حالت" in set || "حالتون" in set || "حالتان" in set) && set.any { it in HOW_WORDS } ||
                (small && ("خوبی" in set || "خوبید" in set || "خوبین" in set)) -> pick(HOW_ARE_YOU_REPLIES, context)

            "من" !in set && (phrase in WHO_ARE_YOU ||
                ((set.contains("اسمت") || set.contains("نامت") || (set.contains("تو") && (set.contains("اسم") || set.contains("نام")))) &&
                    set.any { it in WHAT_WORDS })) -> pick(MY_NAME_REPLIES, context)

            // "چه کارهایی می‌تونی انجام بدی؟" / "چکار بلدی؟" / "چه کاری از دستت برمیاد؟"
            tokens.any { it.startsWith("کار") || it == "چیکار" || it == "چکار" } &&
                set.any { it in ABILITY_WORDS } -> JarvisPhrases.CAPABILITIES

            // "امروز خسته‌ام" / "خیلی خسته ام" / "خستم" / "خسته شدم"
            tokens.any { it.startsWith("خسته") || it == "خستم" } && "نیستم" !in set && "نیست" !in set -> pick(TIRED_REPLIES, context)

            // "حوصله ندارم" / "حوصله‌ام سر رفته" / "بی‌حوصله‌ام"
            tokens.any { it.startsWith("حوصله") || it.startsWith("بیحوصله") } ||
                ("بی" in set && tokens.any { it.startsWith("حوصله") }) -> pick(BORED_REPLIES, context)

            (tokens.any { it in SAD_WORDS } || phrase in SAD_PHRASES) && "نیستم" !in set && "نیست" !in set -> pick(SAD_REPLIES, context)

            // "یه چیزی بگو" / "یه حرفی بزن" / "یه چیز جالب بگو"
            (set.any { it in SAY_WORDS } && set.any { it in SOMETHING_WORDS }) && small -> pick(SOMETHING_REPLIES, context)

            // "کمکم کن" / "کمک" / "می‌تونی کمکم کنی؟"
            tokens.any { it.startsWith("کمک") } && small -> pick(HELP_REPLIES, context)

            small && (set.any { it in USER_FINE } || phrase in USER_FINE_PHRASES) -> pick(USER_FINE_REPLIES, context)

            phrase in ACKS -> pick(ACK_REPLIES, context)
            else -> null
        }
    }

    /** First variant not said recently in this session; rotates so a fresh session does not always start the same. */
    private fun pick(variants: List<String>, context: ConversationContext?): String {
        val recent = context?.recentResponses.orEmpty().toSet()
        val start = cursor++ % variants.size
        for (i in variants.indices) {
            val v = variants[(start + i) % variants.size]
            if (v !in recent) return v
        }
        return variants[start]
    }

    private companion object {
        const val MAX_SMALL_TALK_TOKENS = 5

        /** Wake-word leftovers and politeness that do not change the meaning. */
        val FILLER = setOf("جارویس", "هی", "لطفا", "ارباب", "بله", "راستی", "ببین", "آقا", "جان")
        val POLITE = setOf("خیلی", "واقعا", "جدا", "بابت", "همه", "چیز", "از", "ازت", "ازتون", "تو", "شما")
        val THANKS = setOf("ممنون", "مرسی", "سپاس", "متشکرم", "مچکرم", "دمت", "گرم", "دستت", "درد", "نکنه", "تشکر", "ممنونم")

        val GREETING_WORDS = setOf("سلام", "درود", "علیکم")
        val GREETINGS = setOf("سلام", "درود", "سلام علیکم", "صبح بخیر", "ظهر بخیر", "عصر بخیر", "وقت بخیر", "سلام صبح بخیر")
        val HOW_WORDS = setOf("چطوره", "چطور", "چطوری", "خوبه", "چطوره؟", "چطورید", "چطورین", "چطوره")
        val WHAT_WORDS = setOf("چیه", "چیست", "چی", "هست", "چیه؟")
        val ABILITY_WORDS = setOf("تونی", "میتونی", "بلدی", "بلد", "انجام", "برمیاد", "برمی", "توانی", "میتوانی")
        val HOW_ARE_YOU = setOf(
            "خوبی", "خوبید", "خوبین", "حالت چطوره", "حالت چطور است", "چطوری", "حال شما چطوره", "حالتون چطوره",
            "تو خوبی", "حالت خوبه", "حالت چطوره امروز", "حال تو چطوره", "تو چطوری", "احوالت", "احوال شما", "احوالت چطوره"
        )
        val WHATS_UP = setOf("چه خبر", "چه خبرا", "چه خبرها", "خبر چه", "چه خبر از تو", "چه خبر ها")
        val WHO_ARE_YOU = setOf(
            "اسمت چیه", "اسمت چی هست", "اسمت چیست", "اسم تو چیه", "اسم تو چی هست", "اسم تو چیست",
            "نامت چیه", "نام تو چیست", "تو کی هستی", "کی هستی", "تو چی هستی", "شما کی هستید"
        )
        val SAD_WORDS = setOf("ناراحتم", "ناراحت", "غمگینم", "غمگین", "افسردهام", "دلگرفتهام")
        val SAD_PHRASES = setOf("دلم گرفته", "حالم بده", "حالم خوب نیست", "حالم خرابه", "دلم گرفته ام")
        val SAY_WORDS = setOf("بگو", "بزن", "تعریف", "بگین", "بگید")
        val SOMETHING_WORDS = setOf("چیزی", "چیز", "حرفی", "حرف", "جالب", "خنده")
        val USER_FINE = setOf("خوبم", "عالیم", "سرحالم", "خوشحالم")
        val USER_FINE_PHRASES = setOf("منم خوبم", "من خوبم", "بد نیستم", "بدک نیستم", "من هم خوبم")
        val ACKS = setOf("باشه", "اوکی", "خب", "آره", "اره", "آهان", "اها", "حله", "عالیه", "خوبه", "اوهوم")

        /** The whole utterance (minus filler / thanks) must be one of these. Short on purpose: no false endings. */
        val FAREWELLS = setOf(
            "خداحافظ", "فعلا", "تمام", "تمومه", "تموم", "دیگه کاری ندارم", "دیگه باهات کاری ندارم",
            "کاری ندارم", "باهات کاری ندارم", "دیگه کاری نیست", "همین بود", "خدانگهدار", "بای", "فعلا خداحافظ",
            "خداحافظ فعلا", "خدافظ", "خدا حافظ", "دیگه کاری نداریم", "تا بعد", "بعدا میبینمت", "شب بخیر"
        )

        // ---- variants (first of each group may be a cached fixed phrase of JarvisPhrases) ----
        val GREETING_REPLIES = listOf(JarvisPhrases.GREETING, "سلام. در خدمتم.", "سلام ارباب، بفرمایید.")
        val THANKS_REPLIES = listOf("خواهش می‌کنم ارباب.", "قابلی ندارد.", "وظیفه‌ام است.")
        val WHATS_UP_REPLIES = listOf(
            "خبر تازه‌ای ندارم، همه‌چیز آرام است. شما چه خبر؟",
            "همه‌چیز روی روال است. شما چه خبر؟"
        )
        val HOW_ARE_YOU_REPLIES = listOf(
            "ممنون، آماده‌ام. شما چطورید؟",
            "من خوبم، ممنون که پرسیدید. شما حالتان چطور است؟",
            "همه‌چیز روی روال است. شما چطورید؟"
        )
        val MY_NAME_REPLIES = listOf(
            JarvisPhrases.MY_NAME,
            "من جارویس هستم، دستیار صوتی شما روی همین گوشی.",
            "اسم من جارویس است. می‌توانم گفت‌وگو کنم و چند کار ساده روی گوشی انجام بدهم."
        )
        val NOT_CHATGPT = listOf(
            "نه، من جارویس هستم؛ یک دستیار صوتی که روی همین گوشی کار می‌کند.",
            "من چت‌جی‌پی‌تی نیستم. من جارویس هستم و روی همین گوشی کار می‌کنم."
        )
        val TIRED_REPLIES = listOf(
            "متأسفم که خسته‌اید. کمی استراحت کنید، من همین‌جا هستم.",
            "خسته‌اید؟ شاید چند دقیقه استراحت کمک کند. اگر بخواهید برایتان تایمر می‌گذارم.",
            "امیدوارم زودتر سرحال شوید. اگر کاری از دستم برمی‌آید بگویید."
        )
        val BORED_REPLIES = listOf(
            "می‌فهمم. اگر بخواهید یک نکته‌ی کوتاه می‌گویم، فقط بگویید: یه چیزی بگو.",
            "گاهی همین‌طور می‌شود. با هم کمی حرف بزنیم؟",
            "اگر دوست دارید یک چیز جالب برایتان بگویم، بفرمایید."
        )
        val SAD_REPLIES = listOf(
            "متأسفم. اگر دوست دارید درباره‌اش حرف بزنید، گوش می‌دهم.",
            "امیدوارم زودتر بهتر شوید. اگر لازم بود با یک نفر نزدیک هم صحبت کنید."
        )
        val SOMETHING_REPLIES = listOf(
            "عسل هیچ‌وقت خراب نمی‌شود.",
            "اختاپوس سه قلب دارد.",
            "ستاره‌ی دریایی مغز ندارد.",
            "قطره قطره جمع گردد، وانگهی دریا شود.",
            "صدای نور از صدا سریع‌تر است؛ برای همین رعد را بعد از برق می‌شنویم."
        )
        val HELP_REPLIES = listOf(
            "البته. بگویید چه کاری لازم است؛ می‌توانم برنامه باز کنم، چراغ قوه و صدا را کنترل کنم و تایمر و آلارم بگذارم.",
            "در خدمتم. چه کاری از من می‌خواهید؟"
        )
        val USER_FINE_REPLIES = listOf("خوشحالم. در خدمتم.", "عالی است.")
        val ACK_REPLIES = listOf("بسیار خب.", "در خدمتم.")
        val GOODBYES = listOf(
            JarvisPhrases.GOODBYE,
            "خداحافظ. هر وقت لازم بود صدا بزنید.",
            "فعلاً ارباب. در خدمتم."
        )
    }
}

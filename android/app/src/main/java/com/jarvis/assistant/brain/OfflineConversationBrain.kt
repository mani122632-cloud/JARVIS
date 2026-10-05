package com.jarvis.assistant.brain

import com.jarvis.assistant.nlu.PersianNormalizer
import com.jarvis.assistant.speech.JarvisPhrases
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Offline conversational core (replaces the old BasicConversation lookup table).
 *
 * 100% local: no network, no API, no model file. Rule based on normalized Persian tokens.
 * It is only consulted by [DefaultJarvisBrain] AFTER memory commands and device commands, so a local
 * command (اینستاگرام رو باز کن، آلارم ساعت ۸ ...) never reaches this class.
 *
 *  - [classify] is side-effect free (safe for recognizer alternatives and partial results).
 *  - [reply] returns the Persian sentence to speak, or null when this is not small talk.
 *  - Questions that inherently need live data (weather, news, web search, prices) get
 *    [JarvisPhrases.ONLINE_REQUIRED]; that answer is given ONLY for such requests.
 *
 * Replies are statements, not questions: the conversation ends after the reply and the wake word resumes.
 * To add a phrase: extend the word sets in the companion, or add a [Topic] + its replies in [replies].
 */
class OfflineConversationBrain(
    private val today: () -> LocalDate = { LocalDate.now() }
) {

    enum class Topic {
        GREETING, GOOD_MORNING, GOOD_DAY, GOOD_NIGHT, GOODBYE, THANKS, HOW_ARE_YOU,
        MY_NAME, WHO_ARE_YOU, CAPABILITIES, OFFLINE_INFO, HOW_TO_USE, ARE_YOU_THERE,
        PRAISE, ABOUT_ME, AGE, WEEKDAY, CAPITAL_IRAN, ONLINE_REQUIRED
    }

    /** Rotates the reply variants so JARVIS does not repeat the same sentence every time. */
    private var turn = 0

    fun reply(text: String): String? = classify(text)?.let { respond(it) }

    fun classify(text: String): Topic? {
        val tokens = PersianNormalizer.tokens(text).filter { it !in FILLER }
        if (tokens.isEmpty()) return null
        val phrase = tokens.joinToString(" ")
        val set = tokens.toSet()
        val short = tokens.size <= 4

        // "چه خبر؟" is a greeting, not a news request.
        if (phrase in NEWS_GREETINGS) return Topic.HOW_ARE_YOU

        // Inherently online requests (weather, news, search, prices ...).
        if (tokens.any { isOnlineToken(it) } || ONLINE_PHRASES.any { phrase.contains(it) }) return Topic.ONLINE_REQUIRED

        if (short && (tokens.any { it in GOODBYE_WORDS } || phrase in GOODBYE_PHRASES)) return Topic.GOODBYE
        if (short && (tokens.any { it in THANKS_WORDS } || THANKS_PHRASES.any { phrase.contains(it) })) return Topic.THANKS

        if (phrase == "شب بخیر") return Topic.GOOD_NIGHT
        if (phrase == "صبح بخیر") return Topic.GOOD_MORNING
        if (phrase == "ظهر بخیر" || phrase == "عصر بخیر" || phrase == "روز بخیر") return Topic.GOOD_DAY

        // "سلام، خوبی؟" is answered as a how-are-you.
        val howAreYou = phrase in HOW_ARE_YOU ||
            (set.any { it in HAL_WORDS } && set.any { it in HOW_WORDS }) ||
            (tokens.size <= 3 && set.any { it in HOW_SHORT })
        if (howAreYou) return Topic.HOW_ARE_YOU

        if (tokens.size <= 3 && tokens.all { it in GREETING_WORDS }) return Topic.GREETING

        // Name / identity ("اسم من چیه؟" is a memory question and is handled earlier; keep it out of here).
        if ("من" !in set) {
            val asksName = (set.any { it in NAME_OF_YOU } ||
                (set.contains("تو") && set.any { it == "اسم" || it == "نام" }) ||
                (set.contains("شما") && set.any { it == "اسم" || it == "نام" })) &&
                set.any { it in WHAT_WORDS }
            if (asksName) return Topic.MY_NAME
        }
        if (phrase in WHO_ARE_YOU ||
            (tokens.any { it.startsWith("معرف") } && set.any { it in YOURSELF }) ||
            (set.contains("درباره") && set.any { it in YOURSELF })
        ) return Topic.WHO_ARE_YOU

        // Abilities / how to talk to JARVIS. ("بدون اینترنت کار می‌کنی؟" must win over the generic "کار" rule.)
        if (set.any { it in OFFLINE_WORDS } && tokens.size <= 8) return Topic.OFFLINE_INFO
        val askedWork = tokens.any { it.startsWith("کار") || it == "چیکار" || it == "چکار" } &&
            set.any { it in ABILITY_WORDS }
        val askedList = set.contains("بلدی") && set.any { it in WHAT_ANY }
        if (askedWork || askedList || tokens.any { it.startsWith("قابلیت") || it == "امکانات" }) return Topic.CAPABILITIES
        if ((short && set.any { it in HELP_WORDS }) ||
            (set.any { it.startsWith("دستور") } && set.any { it in HOW_TO_WORDS })
        ) return Topic.HOW_TO_USE

        if (set.any { it in ARE_YOU_THERE_WORDS } && tokens.size <= 3) return Topic.ARE_YOU_THERE
        if (tokens.size <= 3 && tokens.any { it in PRAISE_WORDS }) return Topic.PRAISE
        if (tokens.any { it.startsWith("سازنده") || it == "ساختت" || it == "ساخته" } ||
            (set.contains("کی") && set.any { it.startsWith("ساخت") })
        ) return Topic.ABOUT_ME
        if (set.any { it in AGE_WORDS } && (set.any { it.startsWith("چند") || it == "چقدر" || it == "چقدره" } || set.contains("سال"))) return Topic.AGE

        // Offline-answerable general questions (device clock only; no live data).
        if (set.contains("امروز") && (tokens.any { it in DAY_QUESTION } || phrase.contains("چند شنبه"))) return Topic.WEEKDAY
        if (set.contains("پایتخت") && set.contains("ایران")) return Topic.CAPITAL_IRAN

        return null
    }

    /**
     * Last-resort short reply inside a multi-turn session, for a sentence that is neither a command nor known
     * small talk ("امروز حالم خیلی خوبه"). Deliberately tiny: a few mood/plan cues and neutral acknowledgements,
     * not a canned-answer database. [recent] = JARVIS's latest replies; a variant not said recently is preferred.
     */
    fun chat(text: String, recent: List<String>): String {
        val tokens = PersianNormalizer.tokens(text).filter { it !in FILLER }
        if (tokens.isEmpty()) return JarvisPhrases.ACK
        val set = tokens.toSet()
        val negated = set.any { it in NEGATION_WORDS }
        return when {
            set.any { it in NEGATIVE_MOOD } || (negated && set.any { it in POSITIVE_MOOD }) ->
                fresh(recent, "متأسفم ارباب. اگر کاری از دستم برمی‌آید بگویید.", "ناراحت شدم ارباب. امیدوارم زود بهتر شود.")
            set.any { it in POSITIVE_MOOD } ->
                fresh(recent, "خوشحالم ارباب.", "چه خوب! امیدوارم همین‌طور ادامه پیدا کند.")
            set.any { it in PLAN_WORDS } ->
                fresh(recent, "باشه ارباب. اگر خواستید برایتان آلارم بگذارم، بگویید.", "متوجه شدم ارباب. هر وقت لازم بود کمک می‌کنم.")
            set.any { it in QUESTION_WORDS } ->
                fresh(recent, "متأسفانه جواب دقیقی برای این ندارم ارباب.", "این را نمی‌دانم ارباب، ولی در کارهای گوشی کمکتان می‌کنم.")
            else ->
                fresh(recent, "بله ارباب، می‌شنوم.", "متوجهم ارباب.", "باشه ارباب، ادامه بدهید.")
        }
    }

    private fun fresh(recent: List<String>, vararg options: String): String =
        options.firstOrNull { it !in recent } ?: pick(*options)

    private fun respond(topic: Topic): String = when (topic) {
        Topic.GREETING -> pick(JarvisPhrases.GREETING, "سلام! در خدمتم.", "سلام ارباب، آماده‌ام.")
        Topic.GOOD_MORNING -> pick("صبح شما هم بخیر ارباب.", "صبح بخیر! امیدوارم روز خوبی داشته باشید.")
        Topic.GOOD_DAY -> pick("روز شما هم بخیر ارباب.", "سلام ارباب، روزتان بخیر.")
        Topic.GOOD_NIGHT -> pick("شب شما هم بخیر ارباب.", "شب بخیر! هر وقت لازم داشتید صدایم کنید.")
        Topic.GOODBYE -> pick(JarvisPhrases.GOODBYE, "به امید دیدار.", "هر وقت لازم شد صدایم کنید.")
        Topic.THANKS -> pick(JarvisPhrases.THANKS_REPLY, "وظیفه‌ام است.", "قابلی نداشت.")
        Topic.HOW_ARE_YOU -> pick(JarvisPhrases.FINE, "من خوبم، ممنون که پرسیدید.", "همه چیز روبه‌راه است. در خدمتم.")
        Topic.MY_NAME -> pick(JarvisPhrases.MY_NAME, "اسم من جارویس است.")
        Topic.WHO_ARE_YOU -> "من جارویس هستم، دستیار شخصی شما. دستورهای گوشی را بدون اینترنت اجرا می‌کنم و گفتگوی ساده هم دارم."
        Topic.CAPABILITIES -> JarvisPhrases.CAPABILITIES
        Topic.OFFLINE_INFO -> "بله، دستورهای گوشی و گفتگوی ساده را بدون اینترنت انجام می‌دهم. فقط برای اخبار، هوا و جستجو باید به اینترنت وصل باشم."
        Topic.HOW_TO_USE -> "مثلاً بگویید: اینستاگرام را باز کن، صدا را زیاد کن، فلش را روشن کن، یا برای ساعت هشت آلارم بگذار."
        Topic.ARE_YOU_THERE -> pick("بله ارباب، اینجا هستم.", "در خدمتم ارباب.")
        Topic.PRAISE -> pick("لطف دارید ارباب.", "ممنون، سعی‌ام را می‌کنم.")
        Topic.ABOUT_ME -> "من دستیار شخصی شما هستم و روی همین گوشی اجرا می‌شوم."
        Topic.AGE -> "سن مشخصی ندارم، من یک دستیار دیجیتال هستم."
        Topic.WEEKDAY -> "امروز ${persianDay(today().dayOfWeek)} است."
        Topic.CAPITAL_IRAN -> "پایتخت ایران تهران است."
        Topic.ONLINE_REQUIRED -> JarvisPhrases.ONLINE_REQUIRED
    }

    private fun pick(vararg options: String): String = options[Math.floorMod(turn++, options.size)]

    private fun persianDay(day: DayOfWeek): String = when (day) {
        DayOfWeek.SATURDAY -> "شنبه"
        DayOfWeek.SUNDAY -> "یکشنبه"
        DayOfWeek.MONDAY -> "دوشنبه"
        DayOfWeek.TUESDAY -> "سه‌شنبه"
        DayOfWeek.WEDNESDAY -> "چهارشنبه"
        DayOfWeek.THURSDAY -> "پنج‌شنبه"
        DayOfWeek.FRIDAY -> "جمعه"
    }

    private fun isOnlineToken(t: String): Boolean =
        t in ONLINE_WORDS || ONLINE_PREFIXES.any { t.startsWith(it) }

    private companion object {
        /** Wake-word leftovers and politeness that do not change the meaning. */
        val FILLER = setOf("جارویس", "جارویز", "هی", "لطفا", "ارباب", "بله", "آقا")

        // ---- multi-turn fallback cues (chat) ----
        val NEGATION_WORDS = setOf("نیست", "نیستم", "نیستی", "ندارم", "نداره", "نبود", "نمیشه")
        val POSITIVE_MOOD = setOf("خوبه", "خوبم", "خوب", "عالی", "عالیه", "عالیم", "خوشحال", "خوشحالم", "شادم", "باحال", "قشنگ", "خوشم")
        val NEGATIVE_MOOD = setOf(
            "خسته", "خستم", "ناراحت", "ناراحتم", "بد", "بدم", "بده", "حوصله", "کلافه", "کلافم",
            "عصبی", "عصبانی", "غمگین", "ناامید", "نگران", "نگرانم", "استرس"
        )
        val PLAN_WORDS = setOf("فردا", "امشب", "قراره", "باید", "بیدار")
        val QUESTION_WORDS = setOf("چرا", "چطور", "چگونه", "کجا", "کی", "چقدر", "چیه", "چیست", "کدوم")

        // ---- needs the internet ----
        val ONLINE_WORDS = setOf(
            "هوا", "هوای", "دما", "دمای", "هواشناسی", "گوگل", "جستجو", "سرچ", "search", "google",
            "قیمت", "دلار", "یورو", "طلا", "سکه", "بیتکوین", "ارز", "بورس", "ویکیپدیا", "ترجمه"
        )
        val ONLINE_PREFIXES = listOf("خبر", "اخبار")
        val ONLINE_PHRASES = listOf("نتیجه بازی", "از اینترنت", "توی اینترنت", "تو اینترنت", "در اینترنت", "نتیجه مسابقه")
        val NEWS_GREETINGS = setOf("چه خبر", "چه خبرا", "چه خبرها", "چخبر")

        // ---- small talk ----
        val GOODBYE_WORDS = setOf("خداحافظ", "خدافظ", "خداحافظی", "خدانگهدار", "بای", "فعلا")
        val GOODBYE_PHRASES = setOf("به امید دیدار", "تا بعد", "بعدا میبینمت")
        val THANKS_WORDS = setOf("ممنون", "ممنونم", "مرسی", "متشکرم", "تشکر", "سپاس", "مچکرم", "مخلصیم")
        val THANKS_PHRASES = listOf("دستت درد نکنه", "دستتون درد نکنه", "دست شما درد نکنه")
        val GREETING_WORDS = setOf("سلام", "درود", "علیکم", "علیک", "السلام", "های", "هلو")
        val HAL_WORDS = setOf("حالت", "حالتون", "حالتو", "احوالت", "احوال", "حال")
        val HOW_WORDS = setOf("چطوره", "چطور", "چطوری", "چطورین", "خوبه", "چیه")
        val HOW_SHORT = setOf("خوبی", "خوبین", "خوبید", "چطوری", "چطورین")
        val HOW_ARE_YOU = setOf("حال شما چطوره", "حالتون چطوره", "حالت چطور است", "حال تو چطوره")
        val NAME_OF_YOU = setOf("اسمت", "نامت", "اسمتو", "اسمتون", "نامتون")
        val WHAT_WORDS = setOf("چیه", "چیست", "چی", "هست")
        val WHO_ARE_YOU = setOf(
            "تو کی هستی", "کی هستی", "تو کیستی", "کیستی", "شما کی هستید", "کی هستید", "تو کی", "تو چی هستی", "چی هستی"
        )
        val YOURSELF = setOf("خودت", "خودتو", "خودتون", "خودتان", "خود")
        val ABILITY_WORDS = setOf("تونی", "میتونی", "بلدی", "بلد", "انجام", "برمیاد", "برمی", "توانی", "میتوانی", "کنی", "بدی")
        val WHAT_ANY = setOf("چی", "چه", "چیا", "چیزی", "چیزایی", "چیزهایی")
        val OFFLINE_WORDS = setOf("اینترنت", "آفلاین", "افلاین")
        val HELP_WORDS = setOf("راهنما", "راهنمایی", "کمک", "help")
        val HOW_TO_WORDS = setOf("چطور", "چجوری", "چگونه", "چطوری", "چه")
        val ARE_YOU_THERE_WORDS = setOf("هستی", "اونجایی", "اینجایی", "بیداری", "میشنوی")
        val PRAISE_WORDS = setOf("آفرین", "احسنت", "عالی", "عالیه", "باریکلا", "دمت")
        val AGE_WORDS = setOf("سنت", "سالته", "سن")
        val DAY_QUESTION = setOf("روزیه", "روزی", "روزه", "چندشنبه", "چندشنبس", "چندشنبست")
    }
}

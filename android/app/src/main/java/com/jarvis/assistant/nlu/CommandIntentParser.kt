package com.jarvis.assistant.nlu

import com.jarvis.assistant.command.AppEntry
import com.jarvis.assistant.command.AppRegistry
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.TorchMode
import com.jarvis.assistant.command.VolumeChange
import java.time.LocalTime

/**
 * Offline, deterministic Persian command parser (Stage 46.3). No AI / network / randomness.
 *
 * text -> PersianNormalizer -> every rule proposes a (action, confidence) -> the highest confidence wins
 * (ties: earlier rule). Nothing matched -> [JarvisAction.Unknown] with confidence 0.
 *
 * Add a command: write a `matchXxx(Input): Candidate?` and append it to [rules].
 * [clock] is injectable only so "ساعت ۸" (no صبح/شب) and "۵ دقیقه دیگه" can be tested.
 */
class CommandIntentParser(
    apps: AppRegistry = AppRegistry.default(),
    private val clock: () -> LocalTime = { LocalTime.now() }
) {

    /** [intent] / [slots] are the NLU view of [action] (Stage 46.6); both have defaults so older callers compile unchanged. */
    data class Parsed(
        val action: JarvisAction,
        val confidence: Float,
        val normalized: String,
        val intent: NluIntent = NluIntent.UNKNOWN,
        val slots: Map<String, String> = emptyMap()
    )

    /** Time extraction + Alarm/Timer intent detection (Stage 46.5); the old keyword rules lived here. */
    private val time = PersianTimeParser(clock)
    private val scheduling = SchedulingIntentParser(time)

    private class Input(val text: String, val tokens: List<String>, val compact: String)
    private class Candidate(
        val action: JarvisAction,
        val confidence: Float,
        val intent: NluIntent = NluIntent.OTHER,
        val slots: Map<String, String> = emptyMap()
    )

    /** App aliases in folded, space-free form (longest first): "اینستا گرام" and "اینستاگرام" are the same key. */
    private class AppKey(val app: AppEntry, val key: String)
    private val appKeys: List<AppKey> = apps.entries
        .flatMap { app -> app.aliases.map { AppKey(app, fold(PersianNormalizer.tokens(it).joinToString(""))) } }
        .filter { it.key.isNotEmpty() }
        .sortedByDescending { it.key.length }

    /** Folded spelling -> canonical spelling of every keyword the rules below know ("بلوتوس" -> "بلوتوث"). */
    private val lexicon: Map<String, String> = HashMap<String, String>().also { m ->
        for (w in KNOWN_WORDS) m.putIfAbsent(fold(w), w)
    }

    private val rules: List<(Input) -> Candidate?> = listOf(
        this::matchDismiss,
        this::matchNavigation,
        this::matchFlashlight,
        this::matchVolume,
        this::matchScheduling,
        this::matchSettings,
        this::matchApp
    )

    fun parse(raw: String): Parsed {
        val tokens = canonicalize(PersianNormalizer.tokens(raw))
        val text = tokens.joinToString(" ")
        if (tokens.isEmpty()) return Parsed(JarvisAction.Unknown(raw), 0f, text)
        val input = Input(text, tokens, fold(tokens.joinToString("")))
        var best: Candidate? = null
        for (rule in rules) {
            val c = rule(input) ?: continue
            if (best == null || c.confidence > best.confidence) best = c
        }
        return if (best != null) Parsed(best.action, best.confidence, text, best.intent, best.slots)
        else Parsed(JarvisAction.Unknown(raw), 0f, text)
    }

    // ---- token canonicalization ---------------------------------------------------------------------

    /**
     * Speech recognizers spell, glue and split words differently from run to run ("بازکن", "روشنکن", "صدارو",
     * "بلوتوس", "تنضیمات"). Every token is mapped to the spelling the rules use:
     *  1. folded (phonetic) lookup    2. glued clitic "<word>رو"    3. glued verb "<word>کن"
     *  4. a single-letter typo of a keyword of 5+ letters (only when exactly one keyword fits).
     * Tokens that are not close to any keyword are left alone, so unrelated speech is not turned into a command.
     */
    private fun canonicalize(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size + 3)
        for (tok in tokens) {
            val mapped = if (tok in KNOWN_WORDS || tok.any { it.isDigit() }) null else mapToken(tok)
            if (mapped == null) out += tok else out += mapped
        }
        return out
    }

    private fun lookupWord(w: String): String? = if (w in KNOWN_WORDS) w else lexicon[fold(w)]

    private fun mapToken(tok: String): List<String>? {
        lexicon[fold(tok)]?.let { return listOf(it) }
        for (clitic in GLUED_CLITICS) {
            if (tok.length > clitic.length + 2 && tok.endsWith(clitic)) {
                val base = lookupWord(tok.dropLast(clitic.length)) ?: continue
                return listOf(base, "رو")
            }
        }
        for (verb in GLUED_VERBS) {
            if (tok.length > verb.length + 1 && tok.endsWith(verb)) {
                val base = lookupWord(tok.dropLast(verb.length)) ?: continue
                return listOf(base, "کن")
            }
        }
        val f = fold(tok)
        if (f.length >= 5) {
            val hits = LinkedHashSet<String>()
            for ((k, canon) in lexicon) if (k.length >= 5 && kotlin.math.abs(k.length - f.length) <= 1 && editDistance(f, k) <= 1) hits += canon
            if (hits.size == 1) return listOf(hits.first())
        }
        return null
    }

    /** True when the whole utterance contains [key] (folded, space-free), exactly or with one typo (5+ letters). */
    private fun hasKeyword(inp: Input, vararg keys: String): Boolean {
        for (raw in keys) {
            val key = fold(raw)
            if (key.isEmpty()) continue
            if (inp.compact.contains(key)) return true
            if (key.length >= 5) {
                for (tok in inp.tokens) {
                    val f = fold(tok)
                    if (f.length >= 5 && kotlin.math.abs(f.length - key.length) <= 1 && editDistance(f, key) <= 1) return true
                }
            }
        }
        return false
    }

    /** True for a token that is just a piece of one of [keys] ("وای" / "فای" of "وایفای"). */
    private fun isPartOf(tok: String, vararg keys: String): Boolean {
        val f = fold(tok)
        return f.length >= 2 && keys.any { fold(it).contains(f) }
    }

    // ---- dismiss ------------------------------------------------------------------------------------

    private fun matchDismiss(inp: Input): Candidate? {
        val core = inp.tokens.filter { it !in FILLER }.joinToString(" ")
        return if (core in DISMISS_PHRASES) Candidate(JarvisAction.DismissAssistant, 0.95f) else null
    }

    // ---- navigation ---------------------------------------------------------------------------------

    private fun matchNavigation(inp: Input): Candidate? {
        val t = inp.tokens
        val verb = t.any { it in OPEN_VERBS || it in BACK_WORDS }
        val homeStrong = t.any { it in HOME_STRONG } ||
            (t.contains("صفحه") && t.any { it == "اصلی" || it == "اول" }) ||
            (t.contains("اصلی") && t.filter { it !in NOISE }.size == 1)
        val homePlace = t.any { it in HOME_PLACE } && verb          // "خونه" alone is just a word, "برو خونه" is a command
        if (homeStrong || homePlace) {
            val extra = t.filter { it !in NOISE && it !in HOME_STRONG && it !in HOME_PLACE && it !in HOME_PAGE_WORDS && it !in BACK_WORDS }
            val c = when {
                extra.isEmpty() -> if (verb) 0.95f else 0.88f
                verb && extra.size <= 1 -> 0.75f
                else -> return null
            }
            return Candidate(JarvisAction.GoHome, c, NluIntent.GO_HOME)
        }
        val backWord = t.any { it in BACK_WORDS } ||
            (t.contains("صفحه") && t.any { it == "قبل" || it == "قبلی" })
        if (!backWord) return null
        val extra = t.filter { it !in NOISE && it !in BACK_WORDS && it !in BACK_PAGE_WORDS }
        return Candidate(JarvisAction.GoBack, if (extra.isEmpty()) 0.92f else 0.55f, NluIntent.GO_BACK)
    }

    // ---- flashlight ---------------------------------------------------------------------------------

    private fun matchFlashlight(inp: Input): Candidate? {
        val t = inp.tokens
        val named = hasKeyword(inp, "چراغقوه", "چراغگوشی", "چراغموبایل") || t.any { it in FLASH_WORDS }
        if (!named) return null
        val on = t.any { it in ON_WORDS }
        val off = t.any { it in OFF_WORDS }
        return when {
            on && off -> null
            on -> Candidate(JarvisAction.ToggleFlashlight(TorchMode.ON), 0.95f, NluIntent.TOGGLE_FLASHLIGHT, mapOf(NluSlot.MODE to NluSlot.MODE_ON))
            off -> Candidate(JarvisAction.ToggleFlashlight(TorchMode.OFF), 0.95f, NluIntent.TOGGLE_FLASHLIGHT, mapOf(NluSlot.MODE to NluSlot.MODE_OFF))
            else -> Candidate(JarvisAction.ToggleFlashlight(TorchMode.TOGGLE), 0.75f, NluIntent.TOGGLE_FLASHLIGHT, mapOf(NluSlot.MODE to NluSlot.MODE_TOGGLE))
        }
    }

    // ---- volume -------------------------------------------------------------------------------------

    private class PercentHit(val value: Int, val explicit: Boolean)

    private fun volume(change: VolumeChange, confidence: Float, slots: Map<String, String>) =
        Candidate(JarvisAction.SetVolume(change), confidence, NluIntent.SET_VOLUME, slots)

    private fun volumePercent(value: Int, confidence: Float) = volume(
        VolumeChange.Percent(value), confidence,
        mapOf(NluSlot.ACTION to NluSlot.ACTION_SET, NluSlot.PERCENT to value.toString())
    )

    private fun matchVolume(inp: Input): Candidate? {
        val t = inp.tokens
        if (t.any { it in NEGATIONS }) return null
        val named = t.any { it in VOLUME_NAMES }
        val up = t.any { it in UP_WORDS }
        val down = t.any { it in DOWN_WORDS }

        // "بی‌صدا کن" / "سکوت" / "mute" / "صدا رو قطع کن" = volume 0.
        val hardMute = t.any { it in MUTE_WORDS } || hasKeyword(inp, "بیصدا")
        val softMute = named && t.any { it in OFF_WORDS }
        if ((hardMute || softMute) && !up && !down && findPercent(t) == null) {
            return volumePercent(0, if (hardMute) 0.9f else 0.85f)
        }
        if (!named) return null

        val pct = findPercent(t)
        if (pct != null) {
            if (up || down || pct.value !in 0..100) return null
            return volumePercent(pct.value, if (pct.explicit) 0.95f else 0.8f)
        }
        return when {
            up && down -> null
            up -> volume(VolumeChange.Up, 0.95f, mapOf(NluSlot.ACTION to NluSlot.ACTION_UP))
            down -> volume(VolumeChange.Down, 0.95f, mapOf(NluSlot.ACTION to NluSlot.ACTION_DOWN))
            t.any { it in MAX_WORDS } -> volumePercent(100, 0.9f)
            t.any { it in HALF_WORDS } -> volumePercent(50, 0.85f)
            else -> null
        }
    }

    /** "50 درصد", "50%", "پنجاه درصد" (explicit) or "روی 50" / "50 کن" (implicit, lower confidence). */
    private fun findPercent(t: List<String>): PercentHit? {
        val hasSetVerb = t.any { it in SET_VERBS }
        for (i in t.indices) {
            val tok = t[i]
            if (tok.length in 2..4 && tok.endsWith("%") && tok.dropLast(1).all { it in '0'..'9' }) {
                return PercentHit(tok.dropLast(1).toInt(), true)
            }
            val num = PersianNumbers.parse(t, i) ?: continue
            val next = t.getOrNull(num.next)
            if (next == "درصد" || next == "%") return PercentHit(num.value, true)
            val isDigits = tok.all { it in '0'..'9' }
            if (hasSetVerb || (isDigits && t.contains("کن"))) return PercentHit(num.value, false)
        }
        return null
    }

    // ---- timer / alarm ------------------------------------------------------------------------------

    /** Alarm / Timer by sentence structure (noun + verb semantics + extracted time), see [SchedulingIntentParser]. */
    private fun matchScheduling(inp: Input): Candidate? =
        scheduling.match(inp.tokens)?.let { Candidate(it.action, it.confidence) }

    // ---- settings -----------------------------------------------------------------------------------

    private fun matchSettings(inp: Input): Candidate? {
        val t = inp.tokens
        val settingsWord = t.any { it in SETTINGS_WORDS }
        val wifi = hasKeyword(inp, "وایفای", "ویفای", "wifi")
        val bluetooth = hasKeyword(inp, "بلوتوث", "bluetooth")
        val openVerb = t.any { it in OPEN_VERBS }
        val toggleVerb = t.any { it in ON_WORDS || it in OFF_WORDS }
        if (wifi && bluetooth) return null
        if (wifi || bluetooth) {
            val action = if (wifi) JarvisAction.OpenWifiSettings else JarvisAction.OpenBluetoothSettings
            val intent = if (wifi) NluIntent.OPEN_WIFI_SETTINGS else NluIntent.OPEN_BLUETOOTH_SETTINGS
            val confidence = when {
                settingsWord -> 0.95f
                openVerb -> 0.9f
                // An app cannot switch Wi-Fi/Bluetooth itself; "روشن/خاموش کن" opens the settings page for it.
                toggleVerb -> 0.85f
                else -> {
                    val rest = t.filter { tok ->
                        tok !in NOISE && !(if (wifi) isPartOf(tok, "وایفای", "ویفای", "wifi") else isPartOf(tok, "بلوتوث", "bluetooth"))
                    }
                    if (rest.isEmpty()) 0.8f else return null     // bare "وای فای" only; never inside other speech
                }
            }
            return Candidate(action, confidence, intent)
        }
        if (!settingsWord) return null
        val extra = t.filter { it !in NOISE && it !in SETTINGS_WORDS }
        val confidence = when {
            extra.isEmpty() -> if (openVerb) 0.95f else 0.88f
            openVerb && extra.size <= 1 -> 0.75f
            else -> 0.5f
        }
        return Candidate(JarvisAction.OpenSettings, confidence, NluIntent.OPEN_SETTINGS)
    }

    // ---- apps ---------------------------------------------------------------------------------------

    private fun matchApp(inp: Input): Candidate? {
        val t = inp.tokens
        if (t.any { it in NEGATIONS || it in CLOSE_WORDS }) return null
        val openVerb = t.any { it in OPEN_VERBS }
        val folded = t.map { fold(it) }
        for (a in appKeys) {
            // Very short names ("کرم" for a mis-heard "کروم") only count together with an opening verb.
            if (a.key.length < 4 && !openVerb) continue
            val span = findSpan(folded, a.key) ?: continue
            val action = JarvisAction.OpenApp(a.app.id, a.app.label, a.app.packageNames)
            val slots = mapOf(NluSlot.APP_ID to a.app.id.toString(), NluSlot.APP_NAME to a.app.label.toString())
            if (openVerb) return Candidate(action, 0.95f, NluIntent.OPEN_APP, slots)
            // No verb: only the name plus filler / "رو" / "کن" around it.
            val rest = t.filterIndexed { i, tok -> i !in span && tok !in NOISE }
            return if (rest.isEmpty()) Candidate(action, 0.85f, NluIntent.OPEN_APP, slots) else null
        }
        return null
    }

    /**
     * Token range that spells [key] (1..3 tokens glued together, optionally with a colloquial "رو/را/و" clitic,
     * or one typo for names of 5+ letters): handles "اینستاگرام", "اینستا گرام", "اینستاگرامو", "یو توب".
     */
    private fun findSpan(folded: List<String>, key: String): IntRange? {
        for (start in folded.indices) {
            val sb = StringBuilder()
            for (end in start until minOf(folded.size, start + 3)) {
                sb.append(folded[end])
                val s = sb.toString()
                if (s.length > key.length + 3) break
                if (s == key) return start..end
                if (s.length > key.length && s.startsWith(key) && s.substring(key.length) in CLITIC_FOLDED) return start..end
                if (key.length >= 5 && s.length >= 5 && kotlin.math.abs(s.length - key.length) <= 1 && editDistance(s, key) <= 1) return start..end
            }
        }
        return null
    }

    // ---- folding / distance ---------------------------------------------------------------------------

    /**
     * Phonetic key of a (normalized) word: look-alike letters share one form (ث ص → س, ذ ض ظ → ز, ط → ت, ح → ه,
     * غ → ق, آ → ا, ئ → ی), doubled letters collapse, everything that is not a letter or digit is dropped.
     * Two spellings of the same spoken word get the same key; used only to compare, never to display.
     */
    private fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        var prev = '\u0000'
        for (c0 in s) {
            val c = when (c0) {
                'ي', 'ى', 'ئ' -> 'ی'
                'ك' -> 'ک'
                'ث', 'ص' -> 'س'
                'ذ', 'ض', 'ظ' -> 'ز'
                'ط' -> 'ت'
                'ح' -> 'ه'
                'غ' -> 'ق'
                'أ', 'إ', 'ٱ', 'آ' -> 'ا'
                'ة', 'ۀ' -> 'ه'
                'ؤ' -> 'و'
                else -> c0.lowercaseChar()
            }
            if (!c.isLetterOrDigit()) continue
            if (c == prev) continue
            sb.append(c)
            prev = c
        }
        return sb.toString()
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private companion object {
        val FILLER = setOf("جارویس", "جارویز", "هی", "لطفا", "ممنون", "مرسی", "ارباب", "بله")
        val CLITICS = setOf("رو", "را", "ی", "به", "توی", "تو", "داخل", "در", "گوشی", "تویه", "درون", "برنامه", "اپ", "اپلیکیشن", "اون", "این")
        val GLUED_CLITICS = listOf("رو", "را")
        val GLUED_VERBS = listOf("کنید", "کنین", "بکن", "کن")
        val CLITIC_FOLDED = setOf("رو", "را", "و")
        val HOME_STRONG = setOf("هوم", "home")
        val HOME_PLACE = setOf("خونه", "خانه", "منزل", "دسکتاپ")
        val HOME_PAGE_WORDS = setOf("صفحه", "اصلی", "اول")
        val CLITIC_SUFFIXES = setOf("رو", "را", "و")
        val OPEN_VERBS = setOf("باز", "برو", "بیا", "اجرا", "بزن", "بیار", "بیاور", "ببر", "برید", "بروید", "بازکن", "بازش", "بازشو", "open", "run", "start", "launch")
        val NOISE = FILLER + OPEN_VERBS + CLITICS +
            setOf("کن", "کنید", "بکن", "بذار", "بزار", "اون", "این", "یه", "میشه", "می", "شه", "میتونی", "تونی", "ممکنه", "جان", "عزیزم", "بده", "بدی")
        val NEGATIONS = setOf("نکن", "نه", "نمیخوام")
        val CLOSE_WORDS = setOf("ببند", "ببندش", "حذف", "پاک", "نصب", "آپدیت")

        val DISMISS_PHRASES = setOf(
            "هیچی", "ولش کن", "بیخیال", "بی خیال", "لغو", "کنسل", "خداحافظ", "بسه", "تمام",
            "مهم نیست", "فراموشش کن"
        )
        val BACK_WORDS = setOf("برگرد", "برگردید", "بازگشت", "برگشت", "عقب", "back", "بک")
        val BACK_PAGE_WORDS = setOf("صفحه", "قبل", "قبلی", "قدم")

        val FLASH_WORDS = setOf("چراغقوه", "فلش", "فلشلایت", "flashlight", "torch", "تورچ")
        val ON_WORDS = setOf("روشن", "بزن", "وصل", "فعال", "on")
        val OFF_WORDS = setOf("خاموش", "قطع", "غیرفعال", "off")

        val VOLUME_NAMES = setOf("صدا", "صدارو", "صداو", "صدای", "صدام", "ولوم", "volume")
        val UP_WORDS = setOf("زیاد", "زیادتر", "بیشتر", "بالا", "بلند", "بلندتر", "افزایش")
        val DOWN_WORDS = setOf("کم", "کمتر", "پایین", "آروم", "آرومتر", "آهسته", "کاهش")
        val MAX_WORDS = setOf("حداکثر", "ماکزیمم", "فول", "max", "ماکس", "آخر", "کامل", "بیشترین")
        val HALF_WORDS = setOf("نصف", "نیمه", "متوسط")
        val MUTE_WORDS = setOf("سکوت", "میوت", "mute", "سایلنت", "silent")
        val SET_VERBS = setOf("بذار", "بزار", "بگذار", "تنظیم", "روی")

        val TIMER_WORDS = setOf("تایمر", "تایمیر", "timer", "زمانسنج")
        val ALARM_WORDS = setOf("آلارم", "الارم", "آلارام", "الارام", "alarm", "بیدارم")

        val SETTINGS_WORDS = setOf("تنظیمات", "settings", "setting")

        /** Every keyword the rules compare against; the canonical spellings of the folded lexicon. */
        val KNOWN_WORDS: Set<String> = LinkedHashSet<String>().also { all ->
            for (group in listOf(
                OPEN_VERBS, BACK_WORDS, BACK_PAGE_WORDS, HOME_STRONG, HOME_PLACE, HOME_PAGE_WORDS, FLASH_WORDS,
                ON_WORDS, OFF_WORDS, VOLUME_NAMES, UP_WORDS, DOWN_WORDS, MAX_WORDS, SET_VERBS, TIMER_WORDS,
                ALARM_WORDS, SETTINGS_WORDS, CLOSE_WORDS, HALF_WORDS, MUTE_WORDS, setOf("کن", "صفحه", "قبل", "قبلی")
            )) all += group
        }
    }
}

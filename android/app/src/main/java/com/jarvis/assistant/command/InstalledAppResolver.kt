package com.jarvis.assistant.command

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.nlu.PersianNormalizer
import kotlin.concurrent.thread

/**
 * Finds ANY installed launcher app by the name the user says (Persian or English). Nothing is hardcoded:
 * the list comes from PackageManager (apps with a launcher activity). Needs package visibility on Android 11+:
 * `<queries>` with MAIN/LAUNCHER (see MANIFEST-PATCH.txt).
 *
 * Matching (best tier wins):
 *  100 same spelling (phonetically folded)        90 same consonant skeleton ("تلگرام" == "Telegram")
 *   75 every spoken word matches a label word      60 one typo (names of 5+ letters)
 * Several apps in the best tier -> [Match.Ambiguous] (never guessed). Nothing -> [Match.NotFound].
 * Names AppRegistry knows (e.g. "اینستا") are added as extra aliases of the matching installed package.
 */
class InstalledAppResolver(
    context: Context,
    private val registry: AppRegistry = AppRegistry.default()
) {
    data class InstalledApp(val label: String, val packageName: String, val activityName: String) {
        fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    sealed class Match {
        data class Found(val app: InstalledApp) : Match()
        data class Ambiguous(val apps: List<InstalledApp>) : Match()
        object NotFound : Match()
    }

    private class Word(val fold: String, val skeleton: String)
    private class Entry(val app: InstalledApp, val folds: Set<String>, val skeletons: Set<String>, val words: List<Word>)

    private val pm: PackageManager = context.applicationContext.packageManager
    private val lock = Any()
    @Volatile private var index: List<Entry> = emptyList()
    @Volatile private var builtAt = 0L

    /** Builds the index on a background thread so the first command is fast. */
    fun warmUp() {
        thread(name = "jarvis-app-index", isDaemon = true) {
            try { entries() } catch (e: RuntimeException) { Log.w(TAG, "warmUp failed", e) }
        }
    }

    fun resolve(rawQuery: String): Match {
        val q = PersianNormalizer.tokens(rawQuery).filter { it !in GENERIC }.joinToString(" ")
        if (q.isEmpty()) return Match.NotFound
        val variants = LinkedHashSet<String>().apply {
            add(q)
            for (suffix in listOf("رو", "را", "و")) if (q.length > suffix.length + 2 && q.endsWith(suffix)) add(q.dropLast(suffix.length).trim())
        }
        val scored = ArrayList<Pair<Int, InstalledApp>>()
        for (e in entries()) {
            val s = variants.maxOf { score(e, it) }
            if (s > 0) scored += s to e.app
        }
        if (scored.isEmpty()) return Match.NotFound
        val top = scored.maxOf { it.first }
        val group = scored.filter { it.first == top }.map { it.second }.distinctBy { it.packageName }
        return if (group.size == 1) Match.Found(group[0]) else Match.Ambiguous(group.sortedBy { it.label }.take(MAX_AMBIGUOUS))
    }

    // ---- index ----------------------------------------------------------------------------------

    private fun entries(): List<Entry> {
        val now = System.currentTimeMillis()
        if (index.isNotEmpty() && now - builtAt < TTL_MS) return index
        synchronized(lock) {
            if (index.isNotEmpty() && System.currentTimeMillis() - builtAt < TTL_MS) return index
            val built = build()
            if (built.isNotEmpty()) { index = built; builtAt = System.currentTimeMillis() }
            return built
        }
    }

    private fun build(): List<Entry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = try {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else pm.queryIntentActivities(intent, 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "queryIntentActivities failed", e)
            emptyList()
        }
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            if (!seen.add(ai.packageName)) continue
            val label = try { ri.loadLabel(pm).toString() } catch (e: RuntimeException) { continue }
            if (label.isBlank()) continue
            val texts = ArrayList<String>().apply { add(label) }
            registry.entries.firstOrNull { ai.packageName in it.packageNames }?.let { texts += it.label; texts += it.aliases }
            val folds = HashSet<String>()
            val skeletons = HashSet<String>()
            val words = ArrayList<Word>()
            for (t in texts) {
                val n = PersianNormalizer.normalize(t)
                if (n.isEmpty()) continue
                folds += foldKey(n)
                skeleton(n).let { if (it.length >= 3) skeletons += it }
                n.split(' ').filter { it !in GENERIC }.forEach { words += Word(foldKey(it), skeleton(it)) }
            }
            folds.remove("")
            out += Entry(InstalledApp(label, ai.packageName, ai.name), folds, skeletons, words)
        }
        return out
    }

    // ---- scoring --------------------------------------------------------------------------------

    private fun score(e: Entry, q: String): Int {
        val qFold = foldKey(q)
        if (qFold.isEmpty()) return 0
        if (qFold in e.folds) return 100
        val qSkel = skeleton(q)
        if (qSkel.length >= 3 && qSkel in e.skeletons) return 90
        val qWords = q.split(' ').filter { it.isNotEmpty() }
        if (qWords.isNotEmpty() && qWords.all { w ->
                val f = foldKey(w); val s = skeleton(w)
                e.words.any { it.fold == f || (s.length >= 3 && it.skeleton == s) }
            }) return 75
        if (qFold.length >= 5 && e.folds.any { kotlin.math.abs(it.length - qFold.length) <= 1 && editDistance(it, qFold) <= 1 }) return 60
        if (qSkel.length >= 5 && e.skeletons.any { kotlin.math.abs(it.length - qSkel.length) <= 1 && editDistance(it, qSkel) <= 1 }) return 60
        return 0
    }

    /** Phonetic key: look-alike Persian letters share one form, spaces / punctuation dropped, doubles collapsed. */
    private fun foldKey(s: String): String {
        val sb = StringBuilder(s.length)
        var prev = '\u0000'
        for (c0 in s) {
            val c = when (c0) {
                'ث', 'ص' -> 'س'
                'ذ', 'ض', 'ظ' -> 'ز'
                'ط' -> 'ت'
                'ح' -> 'ه'
                'غ' -> 'ق'
                'آ' -> 'ا'
                'ئ' -> 'ی'
                'ؤ' -> 'و'
                else -> c0.lowercaseChar()
            }
            if (!c.isLetterOrDigit() || c == prev) continue
            sb.append(c); prev = c
        }
        return sb.toString()
    }

    /**
     * Consonant skeleton shared by Persian and Latin spelling: vowels (ا و ی ع / a e i o u y w) are dropped,
     * digraphs sh ch kh ph th gh zh are handled, گ=k ک=k. "تلگرام" and "Telegram" both give "tlkrm".
     */
    private fun skeleton(norm: String): String {
        val s = norm.replace(" ", "")
        val sb = StringBuilder()
        fun put(c: Char) { if (sb.isEmpty() || sb.last() != c) sb.append(c) }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else '\u0000'
            when (c) {
                'ب', 'b' -> put('b')
                'پ' -> put('p')
                'ت', 'ط' -> put('t')
                'ث', 'س', 'ص' -> put('s')
                'ج', 'ژ', 'j' -> put('j')
                'چ' -> put('C')
                'ح', 'ه', 'h' -> put('h')
                'خ' -> put('X')
                'د', 'd' -> put('d')
                'ذ', 'ز', 'ض', 'ظ' -> put('z')
                'ر', 'r' -> put('r')
                'ش' -> put('S')
                'ف', 'f' -> put('f')
                'ق', 'غ', 'q' -> put('q')
                'ک', 'گ' -> put('k')
                'ل', 'l' -> put('l')
                'م', 'm' -> put('m')
                'ن', 'n' -> put('n')
                'p' -> if (n == 'h') { put('f'); i++ } else put('p')
                't' -> { if (n == 'h') i++; put('t') }
                's' -> if (n == 'h') { put('S'); i++ } else put('s')
                'c' -> when {
                    n == 'h' -> { put('C'); i++ }
                    n == 'k' -> { put('k'); i++ }
                    n == 'e' || n == 'i' || n == 'y' -> put('s')
                    else -> put('k')
                }
                'k' -> if (n == 'h') { put('X'); i++ } else put('k')
                'g' -> if (n == 'h') { put('q'); i++ } else put('k')
                'z' -> if (n == 'h') { put('j'); i++ } else put('z')
                'x' -> { put('k'); put('s') }
                'w' -> if (n == 'h') i++
                'و', 'ی', 'ا', 'آ', 'ع', 'ئ', 'ؤ', 'ء', 'a', 'e', 'i', 'o', 'u', 'y', 'v' -> {}
                else -> if (c.isLetterOrDigit()) put(c)
            }
            i++
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
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    companion object {
        private const val TAG = "JarvisApps"
        private const val TTL_MS = 60_000L
        private const val MAX_AMBIGUOUS = 4
        private val GENERIC = setOf("برنامه", "اپ", "اپلیکیشن", "نرم", "افزار", "app", "application", "apps")
    }
}

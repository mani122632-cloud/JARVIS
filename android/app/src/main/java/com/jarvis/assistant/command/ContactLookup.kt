package com.jarvis.assistant.command

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log

/**
 * Offline contact search on ContactsContract.CommonDataKinds.Phone. Blocking: call it from a background thread
 * only (see [CallTool.executeAsync]). Never throws.
 */
class ContactLookup(context: Context) {

    private val app = context.applicationContext

    sealed class Result {
        data class Found(val displayName: String, val number: String) : Result()
        data class Ambiguous(val names: List<String>) : Result()
        object NotFound : Result()
        object NoPermission : Result()
        object Failed : Result()
    }

    private data class Entry(val key: Long, val name: String, val norm: String, val number: String, val type: Int)

    fun find(query: String): Result {
        val q = normalize(query)
        if (q.isEmpty()) return Result.NotFound
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Result.NoPermission
        }
        val entries = ArrayList<Entry>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    entries += Entry(c.getLong(0), name, normalize(name), number, c.getInt(3))
                }
            }
        } catch (e: SecurityException) {
            return Result.NoPermission
        } catch (e: RuntimeException) {
            Log.w(TAG, "Contact query failed", e)
            return Result.Failed
        }

        val tokens = q.split(' ').filter { it.isNotEmpty() }
        var best = 0
        val scored = entries.map { e ->
            val s = score(e.norm, q, tokens)
            if (s > best) best = s
            e to s
        }
        if (best == 0) return Result.NotFound
        val top = scored.filter { it.second == best }.map { it.first }
        val contacts = top.groupBy { it.key }
        if (contacts.size > 1) {
            val names = contacts.values.map { it.first().name }.distinct()
            if (names.size > 1) return Result.Ambiguous(names.take(3))
        }
        val chosen = contacts.values.first()
        val pick = chosen.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE } ?: chosen.first()
        return Result.Found(pick.name, pick.number)
    }

    private fun score(name: String, q: String, tokens: List<String>): Int = when {
        name == q -> 100
        name.startsWith("$q ") -> 80
        name.split(' ').contains(q) -> 70
        name.contains(q) -> 50
        tokens.size > 1 && tokens.all { name.contains(it) } -> 40
        else -> 0
    }

    companion object {
        private const val TAG = "ContactLookup"

        /** Persian/Arabic letter and digit unification, lowercase, no punctuation. */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) {
                when {
                    ch == 'ي' || ch == 'ى' -> sb.append('ی')
                    ch == 'ك' -> sb.append('ک')
                    ch == 'ۀ' || ch == 'ة' -> sb.append('ه')
                    ch == 'أ' || ch == 'إ' || ch == 'ٱ' -> sb.append('ا')
                    ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                    ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                    ch == '\u200c' || ch == '\u200f' || ch == '\u200e' || ch == '\u0640' -> sb.append(' ')
                    ch.isLetterOrDigit() -> sb.append(ch)
                    else -> sb.append(' ')
                }
            }
            return sb.toString().trim().replace(Regex("\\s+"), " ")
        }
    }
}

package com.jarvis.assistant.memory

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.nlu.PersianNormalizer

/**
 * [JarvisMemory] on a private SharedPreferences file (`jarvis_memory`). Small by design: at most
 * [MAX_ENTRIES] facts of at most [MAX_VALUE_LENGTH] characters each. Writes use commit() so a failed
 * write is reported instead of silently lost (the file is tiny, so the main-thread cost is negligible).
 */
class SharedPreferencesJarvisMemory(context: Context) : JarvisMemory {

    private val app = context.applicationContext

    private fun prefs(): SharedPreferences = try {
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (e: RuntimeException) {
        throw MemoryException("Preferences unavailable", e)
    }

    private fun storageKey(key: String): String {
        val k = PersianNormalizer.normalize(key)
        if (k.isEmpty()) throw MemoryException("Empty key")
        return PREFIX + k
    }

    override fun remember(key: String, value: String) {
        val k = storageKey(key)
        val v = value.trim().take(MAX_VALUE_LENGTH)
        if (v.isEmpty()) throw MemoryException("Empty value")
        val p = prefs()
        try {
            if (!p.contains(k) && p.all.keys.count { it.startsWith(PREFIX) } >= MAX_ENTRIES) {
                throw MemoryException("Memory is full")
            }
            if (!p.edit().putString(k, v).commit()) throw MemoryException("Write failed")
        } catch (e: RuntimeException) {
            throw MemoryException("Write failed", e)
        }
    }

    override fun recall(key: String): String? = try {
        prefs().getString(storageKey(key), null)
    } catch (e: RuntimeException) {            // ClassCastException on a corrupted entry, etc.
        throw MemoryException("Read failed", e)
    }

    override fun forget(key: String): Boolean {
        val k = storageKey(key)
        val p = prefs()
        return try {
            if (!p.contains(k)) return false
            if (!p.edit().remove(k).commit()) throw MemoryException("Write failed")
            true
        } catch (e: RuntimeException) {
            throw MemoryException("Write failed", e)
        }
    }

    override fun clear() {
        try {
            if (!prefs().edit().clear().commit()) throw MemoryException("Write failed")
        } catch (e: RuntimeException) {
            throw MemoryException("Write failed", e)
        }
    }

    private companion object {
        const val FILE = "jarvis_memory"
        const val PREFIX = "m."
        const val MAX_ENTRIES = 50
        const val MAX_VALUE_LENGTH = 100
    }
}

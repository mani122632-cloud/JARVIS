package com.jarvis.assistant.memory

/** Storage failed (disk full, preferences unavailable, memory limit reached). Never carries user text. */
class MemoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * JARVIS long-term memory (Stage 46.4): a tiny key -> value store of facts the user EXPLICITLY asked to
 * keep ("یادت باشه اسم من علی هست"). Local-only: nothing leaves the device, no cloud, no backup
 * (`allowBackup=false`). Nothing is ever saved automatically; only the Brain's explicit-command path calls
 * [remember].
 *
 * Keys are compared after Persian normalization, so "اسم" and "اسم؟" are the same key.
 * Every method may throw [MemoryException] (and nothing else); callers must handle it. Main thread.
 */
interface JarvisMemory {
    /** Stores or replaces [key]. */
    @Throws(MemoryException::class)
    fun remember(key: String, value: String)

    /** The stored value, or null when [key] is unknown. */
    @Throws(MemoryException::class)
    fun recall(key: String): String?

    /** True if [key] existed and was removed. */
    @Throws(MemoryException::class)
    fun forget(key: String): Boolean

    /** Removes everything. */
    @Throws(MemoryException::class)
    fun clear()
}

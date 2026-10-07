package com.jarvis.assistant.command

import android.content.Context

/**
 * The alarms JARVIS itself created in the clock app (Android lets an app set an alarm but not list the clock app's
 * alarms), so "show / switch off / delete my alarms" only ever concerns these. A non-repeating alarm is forgotten
 * once its time has passed (it has rung). Persisted in SharedPreferences; thread-safe.
 */
class AlarmRegistry(
    context: Context,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    data class Entry(val hour: Int, val minute: Int, val enabled: Boolean, val triggerAtMillis: Long)

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Alarms that have not rung yet, sorted by time. */
    @Synchronized
    fun entries(): List<Entry> {
        val all = read()
        val live = all.filter { it.triggerAtMillis > nowMillis() - GRACE_MS }
        if (live.size != all.size) write(live)
        return live.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    /** Registers (or re-enables) the alarm at hour:minute. */
    @Synchronized
    fun add(hour: Int, minute: Int, triggerAtMillis: Long) {
        val rest = read().filterNot { it.hour == hour && it.minute == minute }
        write(rest + Entry(hour, minute, true, triggerAtMillis))
    }

    @Synchronized
    fun setEnabled(hour: Int, minute: Int, enabled: Boolean) {
        write(read().map { if (it.hour == hour && it.minute == minute) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun remove(hour: Int, minute: Int) {
        write(read().filterNot { it.hour == hour && it.minute == minute })
    }

    private fun read(): List<Entry> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 4) return@mapNotNull null
            val h = p[0].toIntOrNull() ?: return@mapNotNull null
            val m = p[1].toIntOrNull() ?: return@mapNotNull null
            val t = p[3].toLongOrNull() ?: return@mapNotNull null
            if (h !in 0..23 || m !in 0..59) return@mapNotNull null
            Entry(h, m, p[2] == "1", t)
        }
    }

    private fun write(list: List<Entry>) {
        prefs.edit().putString(KEY, list.joinToString(";") { "${it.hour},${it.minute},${if (it.enabled) 1 else 0},${it.triggerAtMillis}" }).apply()
    }

    private companion object {
        const val FILE = "jarvis_alarm_registry"
        const val KEY = "alarms"
        const val GRACE_MS = 60_000L
    }
}

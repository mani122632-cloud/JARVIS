package com.jarvis.assistant.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One chat message as shown in the UI list. */
data class ChatMessage(val text: String, val fromUser: Boolean, val time: Long)

/** One saved chat (conversation thread) with its messages. */
data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<ChatMessage> = mutableListOf()
)

/** Lightweight entry for the history list (no messages loaded). */
data class ChatSummary(val id: String, val title: String, val updatedAt: Long, val count: Int)

/**
 * UI-only chat history storage (SharedPreferences + org.json, no extra dependencies).
 * Separate from JarvisMemory / the Brain: it only remembers what the chat screen displayed.
 */
class ChatStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chat that was open last; restored when the screen starts. */
    var lastId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_LAST) else putString(KEY_LAST, value) }.apply() }

    fun list(): List<ChatSummary> {
        val out = ArrayList<ChatSummary>()
        try {
            val arr = JSONArray(prefs.getString(KEY_INDEX, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ChatSummary(o.getString("id"), o.optString("title"), o.optLong("updatedAt"), o.optInt("count")))
            }
        } catch (_: Exception) { /* corrupt index: show nothing rather than crash */ }
        return out.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): ChatSession? {
        val summary = list().firstOrNull { it.id == id } ?: return null
        val session = ChatSession(summary.id, summary.title, summary.updatedAt)
        try {
            val arr = JSONArray(prefs.getString(KEY_CHAT + id, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                session.messages.add(ChatMessage(o.getString("t"), o.optBoolean("u"), o.optLong("ts")))
            }
        } catch (_: Exception) { /* keep whatever was parsed */ }
        return session
    }

    fun save(session: ChatSession) {
        while (session.messages.size > MAX_MESSAGES) session.messages.removeAt(0)
        val msgs = JSONArray()
        for (m in session.messages) {
            msgs.put(JSONObject().put("t", m.text).put("u", m.fromUser).put("ts", m.time))
        }
        val entries = list().filter { it.id != session.id }.toMutableList()
        entries.add(ChatSummary(session.id, session.title, session.updatedAt, session.messages.size))
        entries.sortByDescending { it.updatedAt }
        val dropped = if (entries.size > MAX_CHATS) entries.subList(MAX_CHATS, entries.size).toList() else emptyList()
        val kept = entries.take(MAX_CHATS)
        val index = JSONArray()
        for (e in kept) {
            index.put(JSONObject().put("id", e.id).put("title", e.title).put("updatedAt", e.updatedAt).put("count", e.count))
        }
        prefs.edit().apply {
            putString(KEY_CHAT + session.id, msgs.toString())
            putString(KEY_INDEX, index.toString())
            for (d in dropped) remove(KEY_CHAT + d.id)
        }.apply()
        lastId = session.id
    }

    fun delete(id: String) {
        val index = JSONArray()
        for (e in list().filter { it.id != id }) {
            index.put(JSONObject().put("id", e.id).put("title", e.title).put("updatedAt", e.updatedAt).put("count", e.count))
        }
        prefs.edit().remove(KEY_CHAT + id).putString(KEY_INDEX, index.toString()).apply()
        if (lastId == id) lastId = null
    }

    private companion object {
        const val PREFS = "jarvis_chat_store"
        const val KEY_INDEX = "index"
        const val KEY_CHAT = "chat_"
        const val KEY_LAST = "last_id"
        const val MAX_MESSAGES = 500
        const val MAX_CHATS = 100
    }
}

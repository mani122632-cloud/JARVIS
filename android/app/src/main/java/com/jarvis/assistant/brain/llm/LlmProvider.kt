package com.jarvis.assistant.brain.llm

import android.content.Context

/**
 * A pluggable LLM backend. The Brain only talks to this interface, so OpenAI-compatible HTTP, another cloud API
 * or a future on-device model can be swapped in without touching the Brain, the tools or the controller.
 */
interface LlmProvider {
    /** False when no endpoint/model is configured: the Brain then goes straight to the offline brain. */
    val isConfigured: Boolean

    /**
     * Blocking call. The Brain always calls it on a background thread, never on the main thread.
     * Must NOT throw: every problem is returned as [LlmResponse.Failure].
     */
    fun generate(request: LlmRequest): LlmResponse
}

/**
 * Runtime configuration of the HTTP provider. [baseUrl] is an OpenAI-compatible base (e.g. ".../v1") or a full
 * ".../chat/completions" URL. [apiKey] may be blank for local servers that need none. Never logged.
 */
data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val connectTimeoutMs: Int = 6_000,
    val readTimeoutMs: Int = 12_000,
    /** Some newer OpenAI models want "max_completion_tokens". */
    val maxTokensField: String = "max_tokens"
) {
    val isUsable: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    // Keeps the key out of accidental log lines.
    override fun toString(): String = "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"
}

/**
 * Where the configuration comes from. NOTHING is stored in the repository.
 *  1. private SharedPreferences `jarvis_llm_config` (keys: base_url, model, api_key): written by [save], e.g. by a
 *     future settings screen;
 *  2. optional BuildConfig string fields JARVIS_LLM_BASE_URL / JARVIS_LLM_MODEL / JARVIS_LLM_API_KEY, read by
 *     reflection, so the project compiles with or without them (fill them from local.properties, see
 *     INTEGRATION-LLM.md). Each field falls back independently.
 * `allowBackup=false` keeps the preferences file out of cloud backups.
 */
class LlmConfigStore(context: Context) {

    private val app = context.applicationContext

    private fun prefs() = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): LlmConfig? = try {
        val p = prefs()
        val base = p.getString(K_URL, null).orEmpty().ifBlank { buildConfigString("JARVIS_LLM_BASE_URL") }
        val model = p.getString(K_MODEL, null).orEmpty().ifBlank { buildConfigString("JARVIS_LLM_MODEL") }
        val key = p.getString(K_KEY, null).orEmpty().ifBlank { buildConfigString("JARVIS_LLM_API_KEY") }
        if (base.isBlank() || model.isBlank()) null else LlmConfig(base.trim(), model.trim(), key.trim())
    } catch (t: Throwable) {
        null
    }

    fun save(baseUrl: String, model: String, apiKey: String): Boolean = try {
        prefs().edit().putString(K_URL, baseUrl).putString(K_MODEL, model).putString(K_KEY, apiKey).commit()
    } catch (t: Throwable) {
        false
    }

    fun clear(): Boolean = try { prefs().edit().clear().commit() } catch (t: Throwable) { false }

    private fun buildConfigString(field: String): String = try {
        Class.forName("com.jarvis.assistant.BuildConfig").getField(field).get(null) as? String ?: ""
    } catch (t: Throwable) {
        ""
    }

    private companion object {
        const val FILE = "jarvis_llm_config"
        const val K_URL = "base_url"
        const val K_MODEL = "model"
        const val K_KEY = "api_key"
    }
}

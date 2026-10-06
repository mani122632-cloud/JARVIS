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
     * False while the backend is known not to accept tool definitions (e.g. llama.cpp started without --jinja).
     * The Brain then offers no tools and keeps the conversation going as plain chat.
     */
    val toolsAvailable: Boolean get() = true

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
    // A model running on the phone itself: connecting is instant, generating on a phone CPU is slow (the prompt with
    // the tool definitions alone can take tens of seconds on the first turn). Must stay below the Brain's overall
    // timeout (58 s) and the controller's processing timeout (65 s).
    val connectTimeoutMs: Int = 2_000,
    val readTimeoutMs: Int = 52_000,
    /** Some newer OpenAI models want "max_completion_tokens". */
    val maxTokensField: String = "max_tokens"
) {
    /** Usable only for a LOCAL endpoint (127.0.0.1 / localhost / ::1): JARVIS never talks to a remote server. */
    val isUsable: Boolean get() = model.isNotBlank() && isLocalEndpoint(baseUrl)

    companion object {
        /** Local llama.cpp server (OpenAI-compatible) on the phone. */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080/v1"
        const val DEFAULT_MODEL = "qwen2.5-1.5b-instruct"

        fun isLocalEndpoint(url: String): Boolean = try {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            (scheme == "http" || scheme == "https") && (host == "127.0.0.1" || host == "localhost" || host == "::1")
        } catch (t: Throwable) {
            false
        }
    }

    // Keeps the key out of accidental log lines.
    override fun toString(): String = "LlmConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"
}

/**
 * Where the configuration comes from. NOTHING is stored in the repository. With nothing configured the local
 * llama.cpp server on the phone is used (http://127.0.0.1:8080/v1, qwen2.5-1.5b-instruct, no API key).
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
            .ifBlank { LlmConfig.DEFAULT_BASE_URL }
        val model = p.getString(K_MODEL, null).orEmpty().ifBlank { buildConfigString("JARVIS_LLM_MODEL") }
            .ifBlank { LlmConfig.DEFAULT_MODEL }
        val key = p.getString(K_KEY, null).orEmpty().ifBlank { buildConfigString("JARVIS_LLM_API_KEY") }
        LlmConfig(base.trim(), model.trim(), key.trim())    // the default needs no API key
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

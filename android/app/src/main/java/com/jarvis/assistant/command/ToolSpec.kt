package com.jarvis.assistant.command

enum class ParamType { STRING, INTEGER, BOOLEAN }

/** One parameter of a tool, as the LLM sees it (name, type, meaning) and as it is validated before a tool runs. */
data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = false,
    /** Allowed values for a STRING (compared case-insensitively). Empty = free text. */
    val enumValues: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null
)

sealed class ToolArgs {
    /** Arguments that passed validation; every value is a canonical string (integers without ".0", enums as declared). */
    data class Valid(val values: Map<String, String>) : ToolArgs()
    data class Invalid(val reason: String) : ToolArgs()
}

/**
 * Compact, LLM-facing description of a tool: a stable [name], a [description], and its [params].
 * The LLM may only request tools that have a spec; everything it sends is checked with [validate] first.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList()
) {
    /** Keeps only declared parameters, checks required / type / range / enum. Never throws. */
    fun validate(raw: Map<String, String>): ToolArgs {
        val out = LinkedHashMap<String, String>()
        for (p in params) {
            val v = raw[p.name]?.trim()
            if (v.isNullOrEmpty()) {
                if (p.required) return ToolArgs.Invalid("missing required parameter '${p.name}'")
                continue
            }
            when (p.type) {
                ParamType.INTEGER -> {
                    val d = v.toDoubleOrNull()
                        ?: return ToolArgs.Invalid("parameter '${p.name}' must be an integer")
                    if (d.isNaN() || d.isInfinite() || d % 1.0 != 0.0 || d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
                        return ToolArgs.Invalid("parameter '${p.name}' must be an integer")
                    }
                    val n = d.toInt()
                    val lo = p.min
                    val hi = p.max
                    if ((lo != null && n < lo) || (hi != null && n > hi)) {
                        return ToolArgs.Invalid("parameter '${p.name}' must be between ${lo ?: "-inf"} and ${hi ?: "inf"}")
                    }
                    out[p.name] = n.toString()
                }
                ParamType.BOOLEAN -> {
                    val b = v.lowercase()
                    if (b != "true" && b != "false") return ToolArgs.Invalid("parameter '${p.name}' must be true or false")
                    out[p.name] = b
                }
                ParamType.STRING -> {
                    if (p.enumValues.isNotEmpty()) {
                        val match = p.enumValues.firstOrNull { it.equals(v, ignoreCase = true) }
                            ?: return ToolArgs.Invalid("parameter '${p.name}' must be one of ${p.enumValues.joinToString("|")}")
                        out[p.name] = match
                    } else {
                        out[p.name] = v.take(MAX_STRING)
                    }
                }
            }
        }
        return ToolArgs.Valid(out)
    }

    private companion object {
        const val MAX_STRING = 200
    }
}

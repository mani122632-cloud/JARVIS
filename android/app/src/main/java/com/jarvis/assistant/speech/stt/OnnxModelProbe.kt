package com.jarvis.assistant.speech.stt

import java.io.File
import java.io.RandomAccessFile

/**
 * Cheap look at the metadata block of an ONNX file (protobuf `metadata_props`, serialized after the graph, i.e. in
 * the tail of the file). Pure Kotlin, no native code, never throws.
 *
 * Why: sherpa-onnx decides how to run a NeMo model from the metadata keys written by its exporter. A model without
 * `model_type` makes the native recognizer factory give up with exit(), which kills the whole app process and cannot
 * be caught in Kotlin. Knowing what the file really contains lets [SherpaFarsiSttEngine] pass the model type
 * explicitly instead of hitting that path, and gives one readable log line.
 */
internal object OnnxModelProbe {

    class Info(
        /** Value of the `model_type` metadata key, or null if the key was not found. */
        val modelType: String?,
        val hasVocabSize: Boolean,
        val hasSubsamplingFactor: Boolean,
        val hasNormalizeType: Boolean
    ) {
        /** The keys sherpa-onnx's NeMo CTC loader requires are there. */
        val looksLikeNemoCtc: Boolean get() = hasVocabSize && hasSubsamplingFactor && hasNormalizeType
        override fun toString() =
            "model_type=${modelType ?: "<none>"} vocab_size=$hasVocabSize subsampling_factor=$hasSubsamplingFactor normalize_type=$hasNormalizeType"
    }

    private const val TAIL_BYTES = 2L * 1024 * 1024

    fun read(model: File): Info {
        return try {
            RandomAccessFile(model, "r").use { f ->
                val len = f.length()
                val n = minOf(len, TAIL_BYTES).toInt()
                val buf = ByteArray(n)
                f.seek(len - n)
                f.readFully(buf)
                val s = String(buf, Charsets.ISO_8859_1)
                Info(
                    modelType = valueOf(s, "model_type"),
                    hasVocabSize = s.contains("vocab_size"),
                    hasSubsamplingFactor = s.contains("subsampling_factor"),
                    hasNormalizeType = s.contains("normalize_type")
                )
            }
        } catch (t: Throwable) {
            Info(null, false, false, false)
        }
    }

    /** protobuf StringStringEntryProto: 0x0A len key, 0x12 len value (lengths < 128 for these keys). */
    private fun valueOf(s: String, key: String): String? {
        val k = s.lastIndexOf(key)
        if (k < 0) return null
        var i = k + key.length
        if (i + 2 > s.length || s[i].code != 0x12) return null
        val vlen = s[i + 1].code
        i += 2
        if (vlen <= 0 || vlen >= 0x80 || i + vlen > s.length) return null
        val v = s.substring(i, i + vlen)
        return if (v.all { it.code in 0x20..0x7E }) v else null
    }
}

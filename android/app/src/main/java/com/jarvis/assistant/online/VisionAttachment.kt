package com.jarvis.assistant.online

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Vision side channel: the image of ONE user turn travels next to the existing [ChatMessage] without changing
 * [ChatMessage] / [ChatRequest] / [OnlineProvider]. [attach] stores the image and appends an invisible tag to the
 * message text; [GeminiProvider] / [GroqVisionProvider] call [extract] to get the clean text plus the image; [release] frees it when the
 * turn ends (afterwards the tag is simply stripped and no image is re-sent from history).
 */
internal object VisionAttachment {
    private const val MARK = '⁣'
    private val TAG = Regex("$MARK" + "VIS:([0-9a-f]{32})" + "$MARK")
    private val images = ConcurrentHashMap<String, String>()

    fun attach(text: String, base64: String): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        images[id] = base64
        return "$text$MARK" + "VIS:$id" + "$MARK"
    }

    /** Clean text (tag removed) and the base64 image if it is still held. */
    fun extract(content: String): Pair<String, String?> {
        val m = TAG.find(content) ?: return content to null
        return content.replace(TAG, "") to images[m.groupValues[1]]
    }

    fun release(content: String) {
        TAG.findAll(content).forEach { images.remove(it.groupValues[1]) }
    }
}

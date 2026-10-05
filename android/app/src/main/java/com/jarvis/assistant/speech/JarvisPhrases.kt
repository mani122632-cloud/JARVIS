package com.jarvis.assistant.speech

/** Fixed sentences JARVIS says. They are still synthesized by the offline TTS engine at runtime. */
object JarvisPhrases {
    const val ACK = "بله ارباب."
    const val SURE = "حتماً."
    const val NOT_UNDERSTOOD = "متوجه نشدم."

    // Stage 46.4 conversation fallback (see brain/OfflineConversationBrain.kt)
    const val GREETING = "سلام ارباب."
    const val FINE = "ممنون، آماده‌ام."
    const val MY_NAME = "من جارویس هستم."
    const val GOODBYE = "خداحافظ ارباب."
    const val THANKS_REPLY = "خواهش می‌کنم ارباب."

    /** Said ONLY for requests that inherently need live data (weather, news, web search, prices). */
    const val ONLINE_REQUIRED = "برای این مورد باید به اینترنت وصل باشم."

    const val CAPABILITIES = "می‌توانم برنامه‌ها را باز کنم، چراغ قوه و صدا را کنترل کنم، تایمر و آلارم بگذارم و به صفحه اصلی بروم."

    /** Sentences worth synthesizing ahead of time so the first answer is instant. */
    val PREWARM = listOf(ACK, SURE, NOT_UNDERSTOOD, GREETING, FINE, MY_NAME, GOODBYE, THANKS_REPLY, ONLINE_REQUIRED)
}

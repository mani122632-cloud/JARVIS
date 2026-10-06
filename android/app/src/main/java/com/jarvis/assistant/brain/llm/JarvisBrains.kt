package com.jarvis.assistant.brain.llm

import android.content.Context
import com.jarvis.assistant.brain.JarvisBrain
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.memory.JarvisMemory

/**
 * One-line wiring for the overlay service: the LLM Brain on top of the existing offline brain.
 * Without a configured endpoint it behaves exactly like [offline].
 *
 *   val brain = JarvisBrains.create(this, executor, DefaultJarvisBrain(processor, memory), memory)
 */
object JarvisBrains {
    fun create(
        context: Context,
        executor: JarvisActionExecutor,
        offline: JarvisBrain,
        memory: JarvisMemory,
        configStore: LlmConfigStore = LlmConfigStore(context)
    ): JarvisBrain = LocalFirstBrain(
        llm = LlmJarvisBrain(
            provider = OpenAiCompatibleProvider { configStore.load() },
            catalog = LlmToolCatalog(executor, memory),
            memory = memory,
            fallback = offline
        ),
        local = offline
    )
}

/**
 * The brain handed to the conversation controller: the LLM brain plus the offline (local) brain beside it.
 * Every member of the [JarvisBrain] interface is delegated to [llm] unchanged; the controller reads [local] and [llm]
 * to route: phone commands (and the local slot questions / end of conversation / memory commands) are decided by
 * [local] and never reach the LLM; only free conversation goes to [llm] (streaming).
 */
class LocalFirstBrain(
    val llm: LlmJarvisBrain,
    val local: JarvisBrain
) : JarvisBrain by llm

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
    ): JarvisBrain = LlmJarvisBrain(
        provider = OpenAiCompatibleProvider { configStore.load() },
        catalog = LlmToolCatalog(executor, memory),
        memory = memory,
        fallback = offline
    )
}

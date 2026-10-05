package com.jarvis.assistant.command

import java.util.concurrent.CopyOnWriteArrayList

/**
 * One capability JARVIS can execute on the device (alarm, timer, later: call, SMS, contacts, search ...).
 *
 * The Brain (today the offline NLU, tomorrow possibly an LLM) only decides WHAT to do and produces a
 * [JarvisAction]; a tool knows HOW to do it with real Android APIs and reports a [JarvisActionExecutor.Outcome]
 * whose message is what JARVIS says afterwards. A tool must not throw and must run on the main thread.
 *
 * Adding a tool: implement this interface, then `executor.register(MyTool(context))`. Nothing else in the
 * executor, the controller or the Brain has to change; a generic [JarvisAction.ToolCall] reaches it by [name].
 */
interface JarvisTool {
    /** Unique id, also the `tool` of a [JarvisAction.ToolCall]. */
    val name: String

    fun canHandle(action: JarvisAction): Boolean

    fun execute(action: JarvisAction): JarvisActionExecutor.Outcome

    /**
     * How an LLM brain sees this tool (LLM-facing name, Persian-friendly description, parameters).
     * Null = the tool is not offered to the LLM. Tools that return a spec are listed automatically.
     */
    val spec: ToolSpec? get() = null

    /** Turns VALIDATED LLM arguments (see [ToolSpec.validate]) into the action this tool handles. */
    fun toAction(args: Map<String, String>): JarvisAction = JarvisAction.ToolCall(name, args)
}

/** Ordered set of tools; the first tool that [JarvisTool.canHandle] an action runs it. */
class ToolRegistry(tools: List<JarvisTool> = emptyList()) {

    private val tools = CopyOnWriteArrayList(tools)

    /** Adds [tool]; a tool with the same name is replaced. */
    fun register(tool: JarvisTool) {
        tools.removeAll { it.name == tool.name }
        tools.add(tool)
    }

    fun find(action: JarvisAction): JarvisTool? = tools.firstOrNull { it.canHandle(action) }

    /** Null when no registered tool handles [action]. */
    fun execute(action: JarvisAction): JarvisActionExecutor.Outcome? = find(action)?.execute(action)

    val names: List<String> get() = tools.map { it.name }

    /** Snapshot of all registered tools (registration order). */
    val all: List<JarvisTool> get() = tools.toList()
}

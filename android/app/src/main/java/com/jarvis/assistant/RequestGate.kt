package com.jarvis.assistant.speech

/**
 * Stale-request guard for barge-in. Main thread only.
 * Usage in the conversation controller:
 *   val token = gate.begin()                 // when a command/LLM request starts
 *   ... async result: if (!gate.isCurrent(token)) return   // drop stale result, no TTS, no tool run
 *   on barge-in: gate.cancel()               // invalidates every in-flight request
 */
class RequestGate {
    private var current = 0
    fun begin(): Int = ++current
    fun cancel() { current++ }
    fun isCurrent(token: Int): Boolean = token == current
}

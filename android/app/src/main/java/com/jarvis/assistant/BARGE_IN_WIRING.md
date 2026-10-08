# Stage 5C: wiring DONE in JarvisConversationController + JarvisOverlayService (see CHANGES). Gyro TTS unchanged (interface defaults).
# Stage 5C - wiring needed in files NOT in the ZIP
In JarvisConversationController / JarvisOverlayService (while state == SPEAKING and the user speaks):
1. `tts.interrupt()`  (Gyro controller: override isSpeaking/interrupt; defaults just call stop())
2. `gate.cancel()` (RequestGate) + cancel the active LLM request/tool run; every async callback checks `gate.isCurrent(token)`
3. `core.setState(LISTENING)`; `input.startListening()` (only inside an authorized wake session, never restart Vosk here)
4. Final text -> same path as a normal command (`token = gate.begin()`), then the normal 5B loop continues.
Wake-phrase barge-in: `activation.interruptSpeech()`.

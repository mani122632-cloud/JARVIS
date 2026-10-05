# Voice Mode fix: wake word -> "بله ارباب" -> listen -> command -> reply -> listen (loop)

Files changed: JarvisConversationController, JarvisActivationController, JarvisOverlayService, OfflinePersianStt, OfflinePersianTts (thread handler only).

- STT: startListening() on a still-"active" session now aborts and restarts it (was a silent no-op = dead Listening).
- STT: watchdog no longer counts model loading time as user silence; load failures map to NOT_AVAILABLE (spoken, controlled exit).
- STT/TTS worker threads: uncaught-exception handlers; listener callbacks wrapped (a throwing callback cannot crash the app).
- Conversation: 5 retries with growing back-off for BUSY/AUDIO/OTHER (was 2 -> session ended -> overlay hid -> Vosk re-triggered).
- Conversation: every state step is guarded; failures re-listen, then end cleanly back to the wake word. finish() is idempotent.
- Conversation: TTS start timeout 3 s -> 12 s (synthesis of a new sentence happens before onStart; 3 s cut replies off and
  jumped to listening while the voice was still being generated). Listen delay after TTS 250 -> 400 ms.
- "متوجه نشدم." is now said on the 1st and 3rd unusable utterance, session stays open and listens again.
- begin() resets a stale session instead of returning; Activation timeouts raised (3/6 s -> 10/20 s); runActivation ignores
  re-activation while a session runs; ACTION_WAKE_START cannot overwrite the flow state during a session.
Unchanged: Reactor/Overlay UI, Vosk, Gyro TTS, STT model, parser, brain, memory, models, native libs.

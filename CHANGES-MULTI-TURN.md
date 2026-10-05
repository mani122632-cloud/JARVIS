# CHANGES — Real multi-turn conversation (on the working Offline-STT base)

NOT compiled or run (no Kotlin/Android SDK here). Only source-level checks: brace/paren balance, project imports resolve, API signatures match. A real `assembleDebug` is still required.

## Changed files (android/app/src/main/java/com/jarvis/assistant/)
- `conversation/JarvisConversationController.kt` — rewritten from single-command to a session loop. Takes `SpeechInput` (still the Offline STT facade). Owns a `ConversationContext` and mirrors `SessionState`. Public API (`begin/cancel/release`, `State`, `Callback`) unchanged, so the service state mapping is untouched.
- `brain/JarvisBrain.kt` — added `think(text, context)` (default = `think(text)`).
- `brain/DefaultJarvisBrain.kt` — `think(text, context)`: same order (memory -> JarvisCommandProcessor -> small talk); leading "راستی/خب/حالا..." is stripped before the parser; a non-command, non-small-talk sentence gets a short reply instead of Unknown (an unparsed order still gets «متوجه نشدم.»).
- `brain/OfflineConversationBrain.kt` — added tiny `chat()` fallback (mood / plan / question cues + neutral acknowledgements, avoids repeating the last replies). Not a canned database.
- `overlay/JarvisOverlayService.kt` — 2 lines: `SpeechInputFactory.create(this)` instead of constructing `JarvisCommandSpeechController` directly.

Untouched: Offline STT, Vosk, Gyro TTS, Arc Reactor / overlay window, memory, command parser/processor/executor, Gradle, Manifest, models, native libs.

## Behaviour
- After «بله ارباب.» the session listens, answers, listens again — no wake word between turns.
- Each utterance: exit check -> Brain (memory -> existing command parser -> executor, else conversation). After a command the session stays open (700 ms pause, then listening).
- Exit phrases: خداحافظ، فعلاً، تمام، دیگه کاری ندارم، برو استراحت کن، کافیه (+ a few variants) -> «خداحافظ ارباب.» -> session ends -> overlay hides -> Vosk resumes. «هیچی/بیخیال/ولش کن» (existing dismiss) and «برگرد» (its only effect is closing the assistant) also end the session.
- Limits: 25 s silence per turn (quiet end), 10 min max session, history = last 6 utterances/replies in RAM, cleared at start/end.
- Silence / empty STT: silent re-listen; «متوجه نشدم.» only after 2 consecutive unusable results, session ends quietly after 4. Missing model / permission: existing spoken error, session ends.
- Microphone: only the STT listens during the session; Vosk stays suspended by the service until `onConversationFinished`.
- TTS wait time now scales with sentence length so the mic doesn't open while JARVIS is still talking.
- The old partial-result fast path was removed from the controller (the offline STT emits no partials).

## Known limits
1. Each turn is one STT capture (400 ms+ gap between turns); speech started in that gap is lost.
2. No echo cancellation; the 250 ms listen delay is the only guard against hearing JARVIS.
3. Conversation is rule-based, so replies to free sentences are generic acknowledgements.
4. Opening another app mid-session leaves the overlay on screen until the session ends.
5. `INTEGRATION.md` / `STATUS-STAGE46-4.md` still describe the old single-turn flow.

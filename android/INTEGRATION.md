# Stage 42 — Activation layer (not compiled here)

## Changed / new files (all new; place under app/src/main/java/com/jarvis/assistant/)
- activation/JarvisActivationController.kt
- speech/JarvisSpeechController.kt
- speech/AndroidTtsSpeechController.kt

No existing files replaced. JarvisCoreRenderer, Gradle, manifest, layouts untouched.
No project TTS was found in the uploaded files, so a minimal Android TTS wrapper is included;
if you already have one, implement JarvisSpeechController around it instead and skip the 3rd file.
The device needs a Persian TTS voice installed to pronounce "بله ارباب." correctly.

## MainActivity
```kotlin
private lateinit var speech: JarvisSpeechController
private lateinit var activation: JarvisActivationController

// onCreate, after setContentView (do NOT call showCinematic here):
speech = AndroidTtsSpeechController(this)          // keeps applicationContext only
activation = JarvisActivationController(speech)
activation.bind(findViewById(R.id.jarvisCore))     // your existing JarvisCoreView id
// activation.listener = object : JarvisActivationController.Listener {
//     override fun onReadyForCommand() { /* future: start command listening */ }
// }

// onDestroy:
activation.unbind()
speech.shutdown()
```
Future triggers (wake word, overlay, headset, button) just call `activation.activate(Source.X)`.
End an interaction with `activation.deactivate()`.

## Manual dev test (temporary, don't commit)
`activation.activate()` — e.g. one line at the end of onCreate while testing; remove afterwards.

Behavior: repeated activate() while activating/active returns false (no restart). If TTS never
responds, a 6 s one-shot timeout still moves to ready. Core is LISTENING -> SPEAKING (while the
phrase plays) -> LISTENING. Stock TTS gives no amplitude, so setVoiceAmplitude is not driven yet.

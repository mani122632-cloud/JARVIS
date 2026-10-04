# JARVIS Stage 46 — PARTIAL (not buildable yet)

Done: strict wake-word (WakePhrase.kt + VoskWakeWordEngine.kt), new states (WakeWordState.kt),
TTS skeleton (speech/tts/*, JarvisPhrases.kt), conditional sherpa engine (src/sherpa/*).

NOT done / currently broken:
- JarvisOverlayService.kt still references deleted AndroidTtsSpeechController, JarvisCommandProcessor
- JarvisConversationController.kt uses old command classes; no NLU / new intent+action system yet
- JarvisActionExecutor.kt only knows OpenApp
- MainActivity.kt `when(status)` lacks PHRASE_UNSUPPORTED and TtsStatus display
- app/build.gradle: sherpa conditional sourceSet / jniLibs not added
- AndroidManifest.xml: <queries> for apps, CAMERA(flashlight), SET_ALARM, MODIFY_AUDIO_SETTINGS not added
- tools/install-offline-assets.sh, INTEGRATION.md (Stage 46) not written
- Duplicate-activation guard / Vosk suspend-until-finished not yet re-wired
- Empty dirs nlu/ brain/ memory/ (JarvisBrain interface not written)
- Nothing compiled or tested

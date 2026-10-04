# JARVIS Stage 46.2 — Offline Persian TTS

Done: OfflinePersianTts lifecycle (initialize/speak/stop/release + controlled errors), sherpa Gradle wiring
(conditional sourceSet, arm64 ABI, noCompress onnx), tools/install-offline-assets.sh, INTEGRATION.md (46.2 section),
service uses OfflinePersianTts, debug [DEV] voice test button.
NOT done (46.3+): Conversation/Command (JarvisCommandProcessor missing -> project does not compile yet), NLU, Brain, Memory.
Not compiled, not run, model/native libs not included.

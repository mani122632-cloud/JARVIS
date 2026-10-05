# JARVIS — Fully offline Persian STT

## Flow (unchanged shape)
Wake word (Vosk, untouched) → «بله ارباب.» (Gyro TTS, untouched) → **OfflinePersianStt** → JarvisConversationController
→ JarvisBrain → local command / conversation → Gyro TTS → back to the wake word.

Vosk is suspended by `JarvisOverlayService.runActivation` before the command phase (existing behaviour); only the STT owns
the microphone until the conversation ends, then the existing resume logic restarts Vosk.

## Model
| | |
|---|---|
| Name | Shenava Rizeh v1.0 — Persian ASR for sherpa-onnx (NVIDIA FastConformer, CTC head, 32M params, 16 kHz) |
| Source | https://huggingface.co/Reza2kn/Shenava-Rizeh-v1.0-sherpa-onnx |
| Revision (pinned) | `e9b90ab65002f2eeaa7de56f85ca2701db58c81c` |
| License | Apache-2.0 (LICENSE is copied next to the model) |
| Files | `model.onnx` (~117 MB, fp32), `tokens.txt` |
| Install path | `android/app/src/main/assets/stt-fa/` (+ `model-id.txt`, `LICENSE`) |
| Runtime | sherpa-onnx v1.13.7 `OfflineRecognizer`, NeMo CTC, greedy search, 2 threads, CPU, arm64-v8a. The existing `libsherpa-onnx-jni.so` already contains the recognizer. |

The model card says it outputs spelled numbers (e.g. «هشت»); `PersianNumbers` in the command parser already understands those.
The ZIP does **not** contain the model, any Vosk/Gyro model, or native libraries.

### Install
```
cd android
bash tools/install-offline-assets.sh      # only if sherpa-onnx libs / Tts.kt are not installed yet
bash tools/install-persian-stt.sh         # sherpa-onnx ASR Kotlin API (same tag) + the model, SHA-256 printed
git add app/src/main/assets/stt-fa app/src/sherpa && git commit && git push   # GitHub Actions builds the APK
```
Optional: `STT_SHA256=<hash>` makes the installer verify the model; `SHERPA_VERSION` must match the native library version.
The APK grows by ~117 MB (the `.onnx` is stored uncompressed, see `noCompress "onnx"`).

## Code
New (`speech/stt/`)
- `OfflinePersianStt` — AudioRecord 16 kHz mono → energy endpointing (adaptive noise floor, 800 ms trailing silence, 12 s max) → mic closed → decode → normalized text. One worker thread, callbacks on main.
- `OfflineSttEngine` (interface), `OfflineSttEngineFactory` (loads the engine by reflection, so the app builds without sherpa), `SttModelInstaller` (copies the model from assets to `filesDir` once, re-copies only if it changed).
- `src/sherpa-stt/.../SherpaFarsiSttEngine.kt` — the only file that touches sherpa-onnx ASR classes; compiled only when `OfflineRecognizer.kt` + `OfflineStream.kt` are installed (`app/build.gradle`).

Changed
- `speech/JarvisCommandSpeechController.kt` — Android `SpeechRecognizer` removed; same public API, now a facade over `OfflinePersianStt`. New `CommandSpeechError.MODEL_MISSING`.
- `conversation/JarvisConversationController.kt` — `NO_SPEECH` (nothing said) now ends quietly so the wake word resumes; `NO_MATCH` keeps the single retry; `MODEL_MISSING` says «مدل تشخیص گفتار فارسی نصب نشده است.» and ends.
- `app/build.gradle` — conditional `src/sherpa-stt/java` source set + warning.
- `tools/install-persian-stt.sh` (new), `android/INTEGRATION.md` (stale SpeechRecognizer lines).

Untouched: Vosk wake word, Gyro TTS, Arc Reactor / overlay / UI, memory, command parser, executor, OfflineConversationBrain.

## Behaviour
- **Timeout:** no speech within 7 s after «بله ارباب.» → session ends, no spoken error, back to the wake word. A 30 s watchdog backs it up.
- **Mic busy:** 3 open attempts 250 ms apart, then `BUSY` (the conversation controller allows its own 2 retries). No endless loop.
- **Model missing / engine missing / load failure:** one clear `JarvisStt` log line (ERROR), spoken message, conversation ends. No online fallback (by design).
- **Performance:** the model is loaded once, in the background, when the service creates the controller; decoding runs on the worker thread; the main thread only waits ≤ 250 ms in `stopListening` for the AudioRecord to be closed. `release()` frees the model after pending work.
- **Privacy:** recognized text is never logged (only audio length and decode time).
- **Normalization:** output goes through `PersianNormalizer` (ي/ك, half-space, punctuation, digits), the same normalizer the parser uses.

## Not verified (honest status)
- **No real compile or device run was possible** here (no Kotlin compiler, Android SDK, Gradle or network). Imports/packages/duplicates were checked by script; the only unresolved import is the generated `R`. The sherpa-onnx Kotlin names used in `SherpaFarsiSttEngine.kt` (`OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig(nemo=…)`, `OfflineNemoEncDecCtcModelConfig`) are written from knowledge of the upstream API; the installer greps for them and stops if they are missing, but a first real build is needed.
- The model download URL and revision come from the Hugging Face page; nothing was downloaded or run. Recognition accuracy on the 15 test sentences, the endpointing thresholds (`OfflinePersianStt` companion constants) and decode speed are untested on a phone. Tune `MIN_THRESHOLD` / `NOISE_RATIO` / `END_SILENCE_SAMPLES` if speech is cut off or noise triggers.
- fp32 model ≈ 117 MB: loading takes a moment on the first command after the service starts and uses a few hundred MB of RAM while loaded.

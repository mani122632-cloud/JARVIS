# Voice Mode final fix — «تشخیص گفتار روی این گوشی در دسترس نیست»

Source of the message: JarvisConversationController.onSpeechError(NOT_AVAILABLE). OfflinePersianStt set it from ONE failed
model load and kept it for the whole service lifetime; OfflineSttEngineFactory additionally refused to ever load again after a
single native death (".native-load" marker, same build) -> Voice Mode dead until reinstall.

Changed (5 files, nothing else):
- OfflineSttEngineFactory: crash marker is now a counter with expiry (3 dead loads / 30 min), never permanent.
- OfflinePersianStt: failed load is retried by the next session (max 3, then 60 s cool-down); "missing" stays permanent;
  isPreparing; engine released if the service was destroyed while loading.
- SpeechInput / JarvisCommandSpeechController: isPreparing (default false, additive).
- JarvisConversationController: NOT_AVAILABLE is retried (5x, growing pause) before the spoken message; silence watchdog does not
  count STT model loading as silence; TTS max-timeout now stops TTS before listening (no mic + TTS overlap).
Untouched: Reactor, Vosk, Gyro TTS, STT model/assets, parser, brain, memory, multi-turn logic, jniLibs.
Not compiled/run here (no Kotlin/Android toolchain): build once with ./gradlew assembleDebug.

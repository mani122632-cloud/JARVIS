# Offline STT final fix — root cause of «تشخیص گفتار روی این گوشی در دسترس نیست»

Message = OfflinePersianStt could not create the sherpa-onnx engine (NOT_AVAILABLE). Fixed at the load path:

1. OfflinePersianStt: the "JarvisStt" worker (model load + decode) ran on a default ~1 MB Java thread stack. ONNX Runtime builds
   the FastConformer graph on that thread; overflow = native crash (uncatchable) -> crash marker -> NOT_AVAILABLE. Now 32 MB stack.
2. SherpaFarsiSttEngine: native libs loaded explicitly (onnxruntime, then sherpa-onnx-jni); the version log is no longer a failure point
   (it could throw UnsatisfiedLinkError and block the engine); model metadata is probed (OnnxModelProbe) and, when the NeMo CTC keys exist
   but `model_type` is missing, modelType="nemo_ctc" is passed explicitly (otherwise native sherpa calls exit() and kills the process);
   a rejected default config is retried once with that type.
3. New OnnxModelProbe (pure Kotlin) + one log line `JarvisSttEngine: model metadata: ...`.

Unchanged: Wake word, Vosk, Gyro TTS, Reactor/Overlay, Parser, Brain, Memory, Multi-Turn, jniLibs, model, build.gradle.
Not compiled/run here (no Kotlin/Android toolchain, no network).

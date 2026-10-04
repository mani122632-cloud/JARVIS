Files in this folder are added to the build only when BOTH exist:
  app/src/sherpa/java/com/k2fsa/sherpa/onnx/OfflineTts.kt        (sherpa-onnx Kotlin API)
  app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so           (sherpa-onnx native library)
Run tools/install-offline-assets.sh from the project root to fetch them. Without them the app still
builds and runs; JARVIS simply cannot speak (status "ENGINE_MISSING" in logcat tag JarvisTts).

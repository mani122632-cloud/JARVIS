#!/usr/bin/env bash
# JARVIS Stage 46.2 - installs the offline Persian TTS pieces into the project.
# Works in Termux (pkg install curl tar bzip2) and on any Linux/macOS shell. No root, no ADB.
# Run it ONCE from the android/ folder (it downloads ~100 MB), then build/push as usual:
#     cd <project>/android && bash tools/install-offline-assets.sh
#
# Installs:
#   app/src/main/jniLibs/<abi>/libsherpa-onnx-jni.so + libonnxruntime.so   (sherpa-onnx native runtime)
#   app/src/sherpa/java/com/k2fsa/sherpa/onnx/Tts.kt                       (sherpa-onnx Kotlin API, same tag)
#   app/src/main/assets/tts-fa/{model.onnx,tokens.txt,espeak-ng-data/}     (Persian Piper voice)
#
# Overrides (environment variables):
#   SHERPA_VERSION  sherpa-onnx release, default 1.13.7
#   VOICE           voice package from the sherpa-onnx "tts-models" release,
#                   default vits-piper-fa_IR-amir-medium  (alternatives: vits-piper-fa_IR-gyro-medium,
#                   vits-piper-fa_IR-ganji_adabi-medium)
#   ABIS            space separated, default "arm64-v8a"  (must match abiFilters in app/build.gradle)
set -euo pipefail

SHERPA_VERSION="${SHERPA_VERSION:-1.13.7}"
VOICE="${VOICE:-vits-piper-fa_IR-amir-medium}"
ABIS="${ABIS:-arm64-v8a}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/../app" && pwd)"
[ -f "$APP/build.gradle" ] || { echo "ERROR: run this from the android/ project (app/build.gradle not found)"; exit 1; }

for tool in tar bzip2; do
  command -v "$tool" >/dev/null || { echo "ERROR: '$tool' is missing (Termux: pkg install tar bzip2)"; exit 1; }
done
if command -v curl >/dev/null; then
  fetch() { curl -fL --retry 3 --connect-timeout 20 -o "$2" "$1"; }
elif command -v wget >/dev/null; then
  fetch() { wget -q --tries=3 -O "$2" "$1"; }
else
  echo "ERROR: need curl or wget (Termux: pkg install curl)"; exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
REL="https://github.com/k2-fsa/sherpa-onnx/releases/download"

echo "== 1/3 sherpa-onnx v$SHERPA_VERSION native libraries ($ABIS)"
fetch "$REL/v$SHERPA_VERSION/sherpa-onnx-v$SHERPA_VERSION-android.tar.bz2" "$TMP/android.tar.bz2"
mkdir -p "$TMP/android" && tar xjf "$TMP/android.tar.bz2" -C "$TMP/android"
for abi in $ABIS; do
  src="$(find "$TMP/android" -type d -path "*jniLibs/$abi" | head -n1)"
  [ -n "$src" ] || { echo "ERROR: ABI $abi not found in the sherpa-onnx package"; exit 1; }
  for lib in libsherpa-onnx-jni.so libonnxruntime.so; do
    [ -f "$src/$lib" ] || { echo "ERROR: $lib missing for $abi"; exit 1; }
  done
  mkdir -p "$APP/src/main/jniLibs/$abi"
  cp -f "$src/libsherpa-onnx-jni.so" "$src/libonnxruntime.so" "$APP/src/main/jniLibs/$abi/"
  echo "   $abi ok"
done

echo "== 2/3 sherpa-onnx Kotlin API (same tag as the native libraries)"
KT_DIR="$APP/src/sherpa/java/com/k2fsa/sherpa/onnx"
mkdir -p "$KT_DIR"
fetch "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$SHERPA_VERSION/sherpa-onnx/kotlin-api/Tts.kt" "$TMP/Tts.kt"
grep -q "class OfflineTts" "$TMP/Tts.kt" || { echo "ERROR: downloaded Tts.kt looks wrong"; exit 1; }
cp -f "$TMP/Tts.kt" "$KT_DIR/Tts.kt"

echo "== 3/3 Persian voice: $VOICE"
fetch "$REL/tts-models/$VOICE.tar.bz2" "$TMP/voice.tar.bz2"
mkdir -p "$TMP/voice" && tar xjf "$TMP/voice.tar.bz2" -C "$TMP/voice"
VDIR="$TMP/voice/$VOICE"
[ -d "$VDIR" ] || VDIR="$(find "$TMP/voice" -mindepth 1 -maxdepth 1 -type d | head -n1)"
ONNX="$(find "$VDIR" -maxdepth 1 -name '*.onnx' | head -n1)"
[ -n "$ONNX" ] && [ -f "$VDIR/tokens.txt" ] && [ -d "$VDIR/espeak-ng-data" ] \
  || { echo "ERROR: voice package is missing .onnx / tokens.txt / espeak-ng-data"; exit 1; }
DEST="$APP/src/main/assets/tts-fa"
rm -rf "$DEST" && mkdir -p "$DEST"
cp -f "$ONNX" "$DEST/model.onnx"
cp -f "$VDIR/tokens.txt" "$DEST/tokens.txt"
cp -rf "$VDIR/espeak-ng-data" "$DEST/espeak-ng-data"

echo
echo "Done."
du -sh "$APP/src/main/assets/tts-fa" "$APP/src/main/jniLibs" 2>/dev/null || true
echo "Next: commit app/src/main/assets/tts-fa, app/src/main/jniLibs and app/src/sherpa, then build (GitHub Actions or ./gradlew assembleDebug)."

#!/usr/bin/env bash
# JARVIS - installs the fully offline Persian speech-to-text (STT) pieces into the project.
# Works in Termux (pkg install curl tar) and on any Linux/macOS shell. No root, no ADB, no API key.
# Run it ONCE from the android/ folder (downloads ~117 MB), then commit and build as usual:
#     cd <project>/android && bash tools/install-persian-stt.sh
#
# Prerequisite: tools/install-offline-assets.sh was run (it installs libsherpa-onnx-jni.so; that same native
# library already contains the speech recognizer, so nothing native is downloaded here).
#
# Model (fixed revision, Apache-2.0):
#   Shenava Rizeh v1.0 - Persian ASR, NVIDIA FastConformer CTC exported for sherpa-onnx (32M parameters, 16 kHz)
#   https://huggingface.co/Reza2kn/Shenava-Rizeh-v1.0-sherpa-onnx
#   revision e9b90ab65002f2eeaa7de56f85ca2701db58c81c
#
# Installs:
#   app/src/sherpa/java/com/k2fsa/sherpa/onnx/*.kt      sherpa-onnx Kotlin API (ASR classes), same tag as the native lib
#   app/src/main/assets/stt-fa/{model.onnx,tokens.txt,model-id.txt,LICENSE}
#
# Overrides (environment variables):
#   SHERPA_VERSION  sherpa-onnx tag of the Kotlin API, default 1.13.7. MUST equal the version used for
#                   libsherpa-onnx-jni.so (tools/install-offline-assets.sh).
#   STT_REVISION    Hugging Face revision of the model (default: the one above)
#   STT_SHA256      optional expected SHA-256 of model.onnx; the install fails on a mismatch
#   SKIP_KOTLIN=1   install only the model (Kotlin API already in place)
set -euo pipefail

SHERPA_VERSION="${SHERPA_VERSION:-1.13.7}"
STT_REPO="Reza2kn/Shenava-Rizeh-v1.0-sherpa-onnx"
STT_REVISION="${STT_REVISION:-e9b90ab65002f2eeaa7de56f85ca2701db58c81c}"
STT_SHA256="${STT_SHA256:-}"
SKIP_KOTLIN="${SKIP_KOTLIN:-0}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/../app" && pwd)"
[ -f "$APP/build.gradle" ] || { echo "ERROR: run this from the android/ project (app/build.gradle not found)"; exit 1; }

if command -v curl >/dev/null; then
  fetch() { curl -fL --retry 3 --connect-timeout 20 -o "$2" "$1"; }
elif command -v wget >/dev/null; then
  fetch() { wget -q --tries=3 -O "$2" "$1"; }
else
  echo "ERROR: need curl or wget (Termux: pkg install curl)"; exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ ! -f "$APP/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" ]; then
  echo "WARNING: app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so not found."
  echo "         Run tools/install-offline-assets.sh first (same SHERPA_VERSION=$SHERPA_VERSION)."
fi

# ---- 1/2 sherpa-onnx Kotlin API (ASR classes) --------------------------------------------------------
if [ "$SKIP_KOTLIN" != "1" ]; then
  echo "== 1/2 sherpa-onnx v$SHERPA_VERSION Kotlin API"
  KT_DIR="$APP/src/sherpa/java/com/k2fsa/sherpa/onnx"
  mkdir -p "$KT_DIR"
  RAW="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$SHERPA_VERSION/sherpa-onnx/kotlin-api"
  API="https://api.github.com/repos/k2-fsa/sherpa-onnx/contents/sherpa-onnx/kotlin-api?ref=v$SHERPA_VERSION"

  # Preferred: the whole kotlin-api folder (that is how upstream's Android apps use it, so all files compile together).
  FILES=""
  if fetch "$API" "$TMP/list.json" 2>/dev/null; then
    FILES="$(grep -o '"name": *"[^"]*\.kt"' "$TMP/list.json" | sed 's/.*: *"\(.*\)"/\1/' | sort -u || true)"
  fi
  # Fallback (API rate limit / offline listing): the files the recognizer needs.
  [ -n "$FILES" ] || FILES="OfflineRecognizer.kt OfflineStream.kt FeatureConfig.kt Tts.kt"

  for f in $FILES; do
    if fetch "$RAW/$f" "$TMP/$f" 2>/dev/null; then
      cp -f "$TMP/$f" "$KT_DIR/$f"
    else
      case "$f" in
        OfflineRecognizer.kt|OfflineStream.kt|FeatureConfig.kt) echo "ERROR: could not download $f"; exit 1;;
        *) echo "   skipped $f";;
      esac
    fi
  done

  # Sanity: the names JARVIS compiles against.
  chk() { grep -q "$2" "$KT_DIR/$1" || { echo "ERROR: $1 does not contain '$2' - this sherpa-onnx version is not compatible with SherpaFarsiSttEngine.kt"; exit 1; }; }
  chk OfflineRecognizer.kt "class OfflineRecognizer("
  chk OfflineRecognizer.kt "class OfflineNemoEncDecCtcModelConfig"
  chk OfflineRecognizer.kt "var nemo"
  chk OfflineRecognizer.kt "fun createStream"
  chk OfflineRecognizer.kt "fun decode"
  chk OfflineRecognizer.kt "fun getResult"
  chk OfflineStream.kt "fun acceptWaveform"
  chk OfflineStream.kt "fun release"
  echo "   Kotlin API ok"
fi

# ---- 2/2 Persian STT model ----------------------------------------------------------------------------
echo "== 2/2 Persian STT model: $STT_REPO @ $STT_REVISION"
BASE="https://huggingface.co/$STT_REPO/resolve/$STT_REVISION"
fetch "$BASE/model.onnx" "$TMP/model.onnx"
fetch "$BASE/tokens.txt" "$TMP/tokens.txt"
fetch "$BASE/LICENSE" "$TMP/LICENSE" 2>/dev/null || true

SIZE="$(wc -c < "$TMP/model.onnx" | tr -d ' ')"
[ "$SIZE" -gt 50000000 ] || { echo "ERROR: model.onnx is only $SIZE bytes (download failed or Git LFS pointer)"; exit 1; }
[ -s "$TMP/tokens.txt" ] || { echo "ERROR: tokens.txt is empty"; exit 1; }

if command -v sha256sum >/dev/null; then SUM="$(sha256sum "$TMP/model.onnx" | cut -d' ' -f1)"
elif command -v shasum >/dev/null; then SUM="$(shasum -a 256 "$TMP/model.onnx" | cut -d' ' -f1)"
else SUM="(sha256 tool not available)"; fi
if [ -n "$STT_SHA256" ] && [ "$SUM" != "$STT_SHA256" ]; then
  echo "ERROR: SHA-256 mismatch for model.onnx: expected $STT_SHA256, got $SUM"; exit 1
fi

DEST="$APP/src/main/assets/stt-fa"
rm -rf "$DEST" && mkdir -p "$DEST"
cp -f "$TMP/model.onnx" "$DEST/model.onnx"
cp -f "$TMP/tokens.txt" "$DEST/tokens.txt"
[ -s "$TMP/LICENSE" ] && cp -f "$TMP/LICENSE" "$DEST/LICENSE"
printf '%s\n' "$STT_REPO@$STT_REVISION" > "$DEST/model-id.txt"

echo
echo "Done. model.onnx sha256: $SUM"
du -sh "$DEST" 2>/dev/null || true
echo "Next: commit app/src/main/assets/stt-fa and app/src/sherpa, then build (GitHub Actions or ./gradlew assembleDebug)."
echo "Note: the model is bundled uncompressed (noCompress \"onnx\"), so the APK grows by about 117 MB."

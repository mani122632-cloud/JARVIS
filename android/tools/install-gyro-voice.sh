#!/usr/bin/env bash
# Installs ONLY the Persian Gyro voice (vits-piper-fa_IR-gyro-medium) into the project, e.g. in Termux:
#     pkg install -y curl tar bzip2
#     cd <project>/android && bash tools/install-gyro-voice.sh
# Result: app/src/main/assets/tts-fa/{model.onnx,tokens.txt,espeak-ng-data/,voice-id.txt}
# The sherpa-onnx native libraries / Tts.kt are NOT touched (run tools/install-offline-assets.sh once if missing).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VOICE="vits-piper-fa_IR-gyro-medium" VOICE_ONLY=1 exec bash "$HERE/install-offline-assets.sh"

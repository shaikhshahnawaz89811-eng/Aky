#!/usr/bin/env bash
# Puts a sherpa-onnx Android AAR into app/libs (used by CI, also fine to run by hand).
# It never fails the build: with no AAR the app still builds and the Offline speech model card says "Nahi mila (AAR)".
#
# The AARs are published by the sherpa-onnx author in the Hugging Face repo
#   https://huggingface.co/csukuangfj/sherpa-onnx-libs/tree/main/android/aar
# Newer versions sit in a folder named after the version and the file name differs between versions; a GitHub
# release URL could not be confirmed. So several likely locations are tried and every download is checked before
# it is kept.
set -u

VER="${SHERPA_VER:-1.12.39}"
HF="${SHERPA_HF_BASE:-https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar}"
GH="${SHERPA_GH_BASE:-https://github.com/k2-fsa/sherpa-onnx/releases/download}"
LIBS="${SHERPA_LIBS_DIR:-app/libs}"
OUT="$LIBS/sherpa-onnx-$VER.aar"

mkdir -p "$LIBS"
if ls "$LIBS"/*.aar >/dev/null 2>&1; then
  echo "AAR already in $LIBS, skipping download"
  exit 0
fi

# A real AAR: big enough, a valid zip, and its classes.jar holds the class the app looks for by name.
valid() {
  local f="$1" tmp size
  size=$(wc -c < "$f" 2>/dev/null || echo 0)
  [ "$size" -gt 5000000 ] || { echo "  too small ($size bytes)"; return 1; }
  unzip -tq "$f" >/dev/null 2>&1 || { echo "  not a zip file"; return 1; }
  tmp=$(mktemp)
  unzip -p "$f" classes.jar > "$tmp" 2>/dev/null
  if unzip -l "$tmp" 2>/dev/null | grep -q 'com/k2fsa/sherpa/onnx/OfflineRecognizer'; then
    rm -f "$tmp"
    return 0
  fi
  rm -f "$tmp"
  echo "  no com.k2fsa.sherpa.onnx.OfflineRecognizer inside"
  return 1
}

candidates=(
  "$HF/$VER/sherpa-onnx-$VER.aar"
  "$HF/sherpa-onnx-$VER.aar"
  "$HF/$VER/sherpa-onnx-static-link-onnxruntime-$VER.aar"
  "$GH/v$VER/sherpa-onnx-$VER.aar"
)

for url in "${candidates[@]}"; do
  echo "Trying $url"
  rm -f "$OUT"
  if curl -fL --retry 2 --connect-timeout 20 -o "$OUT" "$url" 2>/dev/null && valid "$OUT"; then
    echo "Got sherpa-onnx $VER AAR from $url ($(du -h "$OUT" | cut -f1))"
    exit 0
  fi
done

rm -f "$OUT"
echo "::warning::Could not download a sherpa-onnx $VER AAR from any known place; the Offline speech model stays disabled. Put an AAR into app/libs by hand (see app/libs/README.txt)."
exit 0

#!/usr/bin/env bash
#
# Gaten
# Copyright (C) 2026 Anurag Raut
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Downloads the optional manga-ocr model (the app's second-pass Japanese OCR engine) so it can be
# pushed to a device and imported from Settings. Development convenience only: the app itself never
# downloads anything, and the model is never part of a release APK.
#
# The three files are the ones the settings screen asks for:
#   encoder_model_int8.onnx  onnx-community/manga-ocr-base-ONNX (ViT encoder, int8)
#   decoder_model_int8.onnx  onnx-community/manga-ocr-base-ONNX (BERT decoder, int8)
#   vocab.txt                kha-white/manga-ocr-base (character vocabulary)
#
# Usage:
#   tools/fetch_manga_ocr.sh [target-dir]     # default: ./manga-ocr-model
#
# Then, with the device connected:
#   adb push <target-dir>/* /sdcard/Download/
# and in Gaten: Settings → Stylized-text model → Import model files.

set -euo pipefail

TARGET_DIR="${1:-manga-ocr-model}"

ENCODER_URL="https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model_int8.onnx"
DECODER_URL="https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model_int8.onnx"
VOCAB_URL="https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt"

mkdir -p "$TARGET_DIR"

# curl -L: Hugging Face answers with a 307 to its CDN. --fail: an HTTP error must not leave a
# truncated file behind, which is why each download goes to a .part file first.
fetch() {
    local url="$1" name="$2"
    if [ -s "$TARGET_DIR/$name" ]; then
        echo "already there: $TARGET_DIR/$name"
        return
    fi
    echo "downloading $url"
    curl --fail --location --progress-bar --output "$TARGET_DIR/$name.part" "$url"
    mv "$TARGET_DIR/$name.part" "$TARGET_DIR/$name"
    echo "wrote $TARGET_DIR/$name ($(du -h "$TARGET_DIR/$name" | cut -f1))"
}

fetch "$ENCODER_URL" "encoder_model_int8.onnx"
fetch "$DECODER_URL" "decoder_model_int8.onnx"
fetch "$VOCAB_URL" "vocab.txt"

cat <<EOF

Model files are in $TARGET_DIR. Push them to the device and import them in the app:

  adb push $TARGET_DIR/encoder_model_int8.onnx $TARGET_DIR/decoder_model_int8.onnx $TARGET_DIR/vocab.txt /sdcard/Download/

Then open Gaten, scroll to "Stylized-text model (manga-ocr, optional)" and tap "Import model files",
picking all three from Download. The names are recognised, so nothing has to be renamed.
EOF

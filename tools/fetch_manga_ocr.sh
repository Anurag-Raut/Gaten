#!/usr/bin/env bash
#
# Gaten
# Copyright (C) 2026 Anurag Raut
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetches the manga-ocr weights for development inspection. The app no longer bundles them: Settings
# downloads the same three files on demand (ocr/MangaOcrModel.kt), verifying each against the pinned
# size and SHA-256 below, so this script produces byte-identical files only for inspecting the weights
# or reproducing a download by hand.
#
# In the app, install the model from Settings > Text recognition > Manga OCR > Download; the settings
# screen asks before starting the transfer and warns that it may use mobile data.
#
# The three files, at the revisions the app pins:
#   encoder_model_int8.onnx  onnx-community/manga-ocr-base-ONNX @ f9023406bb2f6b17df67bc4a327c56ecd20611f0
#   decoder_model_int8.onnx  onnx-community/manga-ocr-base-ONNX @ f9023406bb2f6b17df67bc4a327c56ecd20611f0
#   vocab.txt                kha-white/manga-ocr-base @ aa6573bd10b0d446cbf622e29c3e084914df9741
#
# Usage:
#   tools/fetch_manga_ocr.sh [target-dir]     # default: ./manga-ocr-model
#
set -euo pipefail

TARGET_DIR="${1:-manga-ocr-model}"

ONNX_REVISION="f9023406bb2f6b17df67bc4a327c56ecd20611f0"
ENCODER_URL="https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/$ONNX_REVISION/onnx/encoder_model_int8.onnx"
DECODER_URL="https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/$ONNX_REVISION/onnx/decoder_model_int8.onnx"
VOCAB_URL="https://huggingface.co/kha-white/manga-ocr-base/resolve/aa6573bd10b0d446cbf622e29c3e084914df9741/vocab.txt"

# The hashes ocr/MangaOcrModel.kt pins; a file that does not match is not usable.
ENCODER_SHA256="ddd1af56963093795705fa38da6ce7e6567d1658e7c7359db7e13fcd37dbf279"
DECODER_SHA256="2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27"
VOCAB_SHA256="344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d"

mkdir -p "$TARGET_DIR"

# curl -L: Hugging Face answers with a 307 to its CDN. --fail: an HTTP error must not leave a
# truncated file behind, which is why each download goes to a .part file first.
fetch() {
    local url="$1" name="$2" expected="$3"
    if [ -s "$TARGET_DIR/$name" ] && echo "$expected  $TARGET_DIR/$name" | sha256sum --check --status 2>/dev/null; then
        echo "already there and verified: $TARGET_DIR/$name"
        return
    fi
    echo "downloading $url"
    curl --fail --location --progress-bar --output "$TARGET_DIR/$name.part" "$url"
    echo "$expected  $TARGET_DIR/$name.part" | sha256sum --check --status \
        || { rm -f "$TARGET_DIR/$name.part"; echo "checksum mismatch for $name" >&2; exit 1; }
    mv "$TARGET_DIR/$name.part" "$TARGET_DIR/$name"
    echo "wrote $TARGET_DIR/$name ($(du -h "$TARGET_DIR/$name" | cut -f1))"
}

fetch "$ENCODER_URL" "encoder.onnx" "$ENCODER_SHA256"
fetch "$DECODER_URL" "decoder.onnx" "$DECODER_SHA256"
fetch "$VOCAB_URL" "vocab.txt" "$VOCAB_SHA256"

cat <<EOF

Verified files are in $TARGET_DIR — for development inspection only. Install the model from the app:
Settings > Text recognition > Manga OCR > Download.
EOF

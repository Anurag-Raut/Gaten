#!/usr/bin/env python3
"""Reproduces the YomiLens Japanese-OCR model smoke test.

Renders Japanese text with a CJK font and OCRs it using EXACTLY the model bundled in
app/src/main/assets/tessdata/ (via TESSDATA_PREFIX), so the result backs the claim in
../REPORT.md without depending on the host's system tessdata.

This validates the *model*, not on-device screen capture. Requires: tesseract, pillow,
a Japanese font (Noto Sans CJK). Run from the repo root:
    python3 docs/jp-prototype/model-smoke-test/reproduce.py
"""

import os
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "tessdata")
OUT = os.path.dirname(os.path.abspath(__file__))
TESSDATA = "/tmp/yomi-tessdata"

CASES = [
    ("dark_on_light", ["日本語のテスト", "猫が水を飲む", "学校へ行きます"], (0, 0, 0), (255, 255, 255)),
    ("subtitle_style", ["これは日本語の字幕です", "ありがとうございました"], (255, 255, 255), (20, 20, 20)),
]


def main() -> None:
    os.makedirs(TESSDATA, exist_ok=True)
    for model in ("eng", "jpn"):
        shutil.copy(os.path.join(ASSETS, f"{model}.traineddata"), os.path.join(TESSDATA, f"{model}.traineddata"))

    font_path = subprocess.check_output(
        ["fc-match", "-f", "%{file}", "Noto Sans CJK JP:style=Regular"]
    ).decode()
    env = dict(os.environ, TESSDATA_PREFIX=TESSDATA)

    for name, lines, fg, bg in CASES:
        image = Image.new("RGB", (1100, 90 * len(lines) + 40), bg)
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype(font_path, 54)
        for i, line in enumerate(lines):
            draw.text((30, 20 + i * 90), line, font=font, fill=fg)
        png = os.path.join(OUT, f"{name}.png")
        image.save(png)

        result = subprocess.run(
            ["tesseract", png, "stdout", "-l", "jpn+eng"], env=env, capture_output=True, text=True
        )
        with open(os.path.join(OUT, f"{name}.ocr.txt"), "w") as handle:
            handle.write(result.stdout)
        print(f"=== {name} ===\ninput: {lines}\n{result.stdout.strip()}\n")


if __name__ == "__main__":
    main()

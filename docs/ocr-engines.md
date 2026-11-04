# Gaten OCR engines

Status: current, documentation only. Describes the engines that turn a text line into text, their
pinned sources, limits and licence. ML Kit is bundled and is the default; the other three are
downloaded on demand and none of them ship in the APK. It records what was verified from the
upstream files and claims no Android or device verification.

## Summary

ML Kit finds *where* the text is (one box per line, one box per character for hit-testing) and is
also the recognizer that ships with Gaten: on a fresh install, with nothing downloaded, lookups read
with ML Kit's own Japanese text recognition. A downloadable engine turns each line box's crop into
text and is the reader's manual choice in **Settings → Text recognition**; there is no automatic
image classifier.

| Engine | Role | Bundled? |
| --- | --- | --- |
| ML Kit (Japanese text recognition, `com.google.mlkit:text-recognition-japanese`) | Default recognizer out of the box, and the fallback for every downloadable engine | Yes |
| PP-OCRv6 Medium (`PP-OCRv6_medium_rec`, 76.6 MB) | Recommended download: ordinary screen and novel text | No, downloaded |
| PP-OCRv6 Manga v0.2 (`manga_rec_v0.2`, 21.2 MB) | Optional: manga lettering, stylized fonts, outlined thumbnails | No, downloaded |
| Manga OCR (manga-ocr, ViT + BERT, int8 ONNX, 116.6 MB) | Optional: Japanese and stylized text | No, downloaded |

None of the three downloadable engines ships its weights. ML Kit's detection *and* recognition
models stay included, so a fresh install reads text out of the box; a download only replaces ML
Kit's reading with a specialized engine where the reader chooses one.

## Pinned sources

Every file is pinned to one immutable revision and checked by exact size and SHA-256 before it is
installed. The checksums below match the local files the build previously fetched.

### Manga OCR

| File | Pinned source | Bytes | SHA-256 |
| --- | --- | --- | --- |
| Encoder | [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) @ `f9023406bb2f6b17df67bc4a327c56ecd20611f0`, `onnx/encoder_model_int8.onnx` | 86,967,767 | `ddd1af56963093795705fa38da6ce7e6567d1658e7c7359db7e13fcd37dbf279` |
| Decoder | [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) @ `f9023406bb2f6b17df67bc4a327c56ecd20611f0`, `onnx/decoder_model_int8.onnx` | 29,627,936 | `2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27` |
| Vocabulary | [kha-white/manga-ocr-base](https://huggingface.co/kha-white/manga-ocr-base) @ `aa6573bd10b0d446cbf622e29c3e084914df9741`, `vocab.txt` | 24,072 | `344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d` |

Total 116,619,775 B (116.6 MB). The encoder/decoder are the int8 ONNX export of
[kha-white/manga-ocr](https://github.com/kha-white/manga-ocr).

### PP-OCRv6 (optional)

| File | Pinned source | Bytes | SHA-256 |
| --- | --- | --- | --- |
| PP-OCRv6 Medium ONNX | [PaddlePaddle/PP-OCRv6_medium_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx) @ `50c7eacafc52fa7bcf4194e8cd08e46f8558504b`, `inference.onnx` | 76,554,979 | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba` |
| PP-OCRv6 Manga v0.2 ONNX | [Kellenok/PP-OCRv6_manga](https://huggingface.co/Kellenok/PP-OCRv6_manga) @ `02af751fcea728fba69ee421a95838f3e549daa2`, `rec/manga_rec_v0.2.onnx` | 21,167,540 | `de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888` |
| Shared dictionary | [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) @ `b03f46425e8ff4442b268ce449e3eef758146cd4`, `ppocr/utils/dict/ppocrv6_dict.txt` | 74,947 | `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d` |

Total transfer: Medium 76,629,926 B, Manga v0.2 21,242,487 B (model + dictionary). The dictionary is
also present byte-identically at `Kellenok/PP-OCRv6_manga` as `ppocrv6_dict.txt` and embedded in the
Medium model's `inference.yml`; Gaten downloads the PaddleOCR copy for Medium and the Kellenok copy
for Manga.

## Graph contracts

| | Manga OCR | PP-OCRv6 (both) |
| --- | --- | --- |
| Input | encoder `pixel_values` float32 `[1, 3, 224, 224]` | `x` float32 `[N, 3, 48, W]` (N and W symbolic; the app runs N = 1) |
| Output | decoder `logits` `[1, L, V]` (V = vocab size) | `fetch_name_0` float32 `[N, T, 18710]` |
| Sequence | greedy, start token 2, EOS 3, cap 300 tokens | `T` is roughly `W / 8`, exactly `W / 8` at the widths probed (48, 320, 640, ...) |
| Classes | character-level `vocab.txt`, first five entries special | 18710 = blank (0) + 18,708 dict symbols (1..18708) + space (18709) |
| Preprocess | grayscale 224×224 square (OcrPreprocess) | height 48, planar BGR, `(px/255 - 0.5) / 0.5` |
| Decode | greedy over the decoder (no KV cache) | CTC greedy: argmax, drop class 0, collapse repeats |
| Mode | `manga_ocr` | `medium` / `manga` (`RecognitionMode`) |

For Manga OCR the decoder is re-run over the whole prefix each step and the tokenizer drops special
text; the PP pair instead emits CTC logits that need the blank/repeat collapse.

## How Gaten uses them

`RecognitionMode` is one of `ml_kit` (default), `manga_ocr`, `medium`, `manga`. The choice is the
reader's; there is no classifier. ML Kit is bundled and always installed, so it is what a fresh
install reads with, and it is the fallback for every downloadable engine: at read time an engine
that is not installed, fails, or returns blank / placeholder / no-Japanese text leaves ML Kit's
reading in place — the screen index's own reading of the line for the overlay, or a fresh ML Kit
read of the crop for a circle or a tap, whose rectangle the index may not cover. Because the engines
are recognizers, a fallback only happens when one produces nothing usable, not when it produces
confident-but-wrong text. With `ml_kit` selected there is nothing to fall back from: the ML Kit
reading is the answer.

PP-OCRv6 Medium is the recommended download; Manga OCR is an ordinary optional engine, no longer
privileged as the default or the fallback.

All three downloadable engines download the same way, from the same installer helper, and only from
an explicit **Download** button; selecting a mode alone never downloads anything.

- The download is manual and explicit: a confirmation shows the model's name and size and warns that
  Wi-Fi is recommended and mobile data charges may apply. It runs over whatever connection the device
  has, and nothing is downloaded automatically.
- Each file is written to a `.part`, checked against its exact byte size and SHA-256, and only then
  renamed into place, so a partial or tampered download can never be mistaken for an installed file.
- An engine counts as installed when its pinned files are all present at the pinned sizes and the
  recorded revision marker matches; a marker-less legacy Manga OCR install is accepted by size, so
  its files are preserved and are not re-hashed on every read.
- Installed files stay in app-private storage and are excluded from cloud backup and device transfer;
  Manga OCR keeps its existing `files/manga-ocr/` location.
- The installed revision is recorded so a source-revision change is noticed.
- Nothing is downloaded until the reader presses Download; afterwards every engine runs offline. No
  import step, and no recognizer weights in the APK beyond ML Kit's own.

At most one PP engine session is resident: switching between the two PP engines closes the previous
one. A Manga OCR session can also remain after it has been used as a fallback, until it goes idle.
`OcrModels` releases every idle engine after 90 seconds.

### Input and preprocessing

PP-OCRv6 (PaddleOCR's `RecResizeImg`): height 48, aspect preserved, width
`ceil(48 * cropWidth / cropHeight)`, squeezed past the tensor width and zero-padded below it;
planar CHW, channel order **BGR** (`DecodeImage img_mode: BGR`, no channel swap); scale to [0,1],
mean 0.5 / std 0.5, so zero on the canvas is mid-grey.

Manga OCR is different: `OcrPreprocess` grows the line box by ~10% of its height, scales small
lines up, and makes a grayscale 224×224 square for the ViT encoder — deliberately not the BGR
48-high strip the PP engines take.

### Dictionary and vocabulary

PP-OCRv6: `ppocrv6_dict.txt` is UTF-8, one symbol per line, 18,708 lines, no blank and no space
line; classes are 0 = blank, 1..18708 the lines in file order, 18709 = space (`use_space_char`) —
18,710 classes. Loaded as `listOf("blank") + file.readLines() + listOf(" ")`.

Manga OCR: `vocab.txt` is a character-level vocabulary whose first five entries are the special
tokens; WordPiece `##` prefixes are stripped on decode.

## Limits

### Width

The PP tensor width is dynamic; `T` is roughly `W / 8`, so width is the character budget. Gaten
clamps it into `[320, 1280]` (`PpOcrGeometry`): 320 is PaddleOCR's `RecResizeImg` default, so short
lines pad up to it; 1280 is the ceiling (roughly 26 CJK characters before a wider line is squeezed).
The upstream reference pipeline instead uses a floor of 16 and a cap of 640, or 2400 for a long
strip. The logits tensor `[T, 18710]` float32 is the cost driver — about 74,840 bytes per timestep,
up to roughly 12 MB at `T` = 160.

### Vertical text

Not native for either family. PP: a crop taller than wide is rotated a quarter turn
counter-clockwise before it is fed (`PpOcrGeometry` / `PpOcrPreprocess`), matching the upstream
reference pipeline (`cv2.ROTATE_90_COUNTERCLOCKWISE`). Manga OCR reads a single line box as cropped.
Detection always comes from ML Kit, and with `ml_kit` selected so does recognition.

### Multiline regions

Unsupported: these are single-text-line recognizers, one crop, one line. A crop that spans several
lines (for example a circle around a block of text) is passed to the recognizer as one run of text;
there is no automatic detection of that, and no guaranteed fallback, so it may misread or garble the
region silently. If a multiline or vertical selection reads badly, try another engine.

### Android, ONNX Runtime 1.31, CPU FP32

The three downloadable engines run on the ONNX Runtime Mobile the app uses
(`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`). The bundled ML Kit recognizer runs on ML
Kit's own runtime, which the app already includes for detection.

- ABI: the 1.31.0 AAR ships `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; Gaten packages `arm64-v8a`,
  `armeabi-v7a` and `x86_64` (`app/build.gradle.kts` split). One FP32 ONNX (the PP files; the Manga
  files are int8) runs on every ABI.
- minSdk 29 (Android 10). CPU execution provider. R8-minified builds need
  `-keep class ai.onnxruntime.** { *; }` (present in `app/proguard-rules.pro`).
- Memory: peak resident RAM was not measured. It exceeds the model file sizes — ORT holds the
  weights plus activations, buffers and the graph's tensors — with the PP logits up to roughly 12 MB
  on their own. Manga OCR's encoder alone is ~87 MB of weights.

## Attribution and licence

The three downloadable engines and both dictionaries are Apache License 2.0; ML Kit is Google's,
under the ML Kit terms of service.

- ML Kit Japanese text recognition: Google LLC, bundled and used on-device
  ([ML Kit terms](https://developers.google.com/ml-kit/terms)).
- Manga OCR: [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr) (Apache-2.0) and its
  int8 ONNX export [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX)
  (Apache-2.0), vocabulary from [kha-white/manga-ocr-base](https://huggingface.co/kha-white/manga-ocr-base)
  (Apache-2.0).
- PP-OCRv6 Medium: PaddlePaddle `PP-OCRv6_medium_rec_onnx` (Apache-2.0). PP-OCRv6 Manga v0.2:
  Kellenok `PP-OCRv6_manga` (Apache-2.0), a fine-tune of `PaddlePaddle/PP-OCRv6_small_rec` on
  `hal-utokyo/Manga109-s`. Dictionary: PaddlePaddle/PaddleOCR (Apache-2.0).

The Apache-2.0 text is bundled at `app/src/main/assets/licenses/PP-OCRv6-Apache-2.0.txt` (PP engines)
and `app/src/main/assets/licenses/manga-ocr-Apache-2.0.txt` (Manga OCR); the attributions are
recorded in `NOTICE` and the README credits. Gaten does not redistribute the downloadable weights;
the reader downloads them from the pinned locations above. ML Kit's models are bundled through
Google's own dependency, under the ML Kit terms. ONNX Runtime (Microsoft, MIT) is already
attributed.

## Verification

Done: the three Manga OCR files were already present locally, and their SHA-256 was computed and
matches the sizes/hashes above and the upstream Hugging Face metadata
(`onnx-community/manga-ocr-base-ONNX` revision `f9023406…`, `kha-white/manga-ocr-base` revision
`aa6573bd…`). The PP-OCRv6 ONNX files and dictionary copies were downloaded over HTTPS and hashed
(all match the pins); each PP graph's input/output names, shapes, IR version, opset and parameter
count were read with `onnx` (Medium IR 6 / opset 11, ~19.1M parameters; Manga IR 7 / opset 14,
~5.27M). On the bundled-ML-Kit, recommended-PP-Medium revision: `./node_modules/.bin/tsc --noEmit`
succeeded, `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`,
`:app:testDebugUnitTest` and `:app:assembleDebug` succeeded, and the merged assets hold no Manga OCR
or PP-OCRv6 ONNX weights along with both Apache-2.0 licence texts. The build does not fetch any
recognizer weights; ML Kit's models arrive with its own dependency.

Not done, and not claimed here: no on-device inference timing or accuracy comparison between
engines, and no measured memory figure. The PP `T`-width relation was observed only at multiples of
8; a dynamic-width implementation may not hold to it between them. The authorized release workflow
builds and signs the APK and AAB remotely; nothing here claims that release or Play publishing has
succeeded.

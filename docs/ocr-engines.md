# Gaten OCR engines

Status: current, documentation only. Describes the engines that turn a text line into text, how the
optional one is delivered, their pinned sources, limits and licence. ML Kit is bundled and is the
default; PP-OCRv6 Medium is the one optional engine and ships in neither the APK nor the base
module. It records what was verified from the upstream files and the build, and claims no Android or
device verification.

## Summary

ML Kit finds *where* the text is (one box per line, one box per character for hit-testing) and is
also the recognizer that ships with Gaten: on a fresh install, with nothing downloaded, lookups read
with ML Kit's own Japanese text recognition. PP-OCRv6 Medium can be chosen instead in
**Settings → Text recognition**; there is no automatic image classifier.

| Engine | Role | Delivered how |
| --- | --- | --- |
| ML Kit (Japanese text recognition, `com.google.mlkit:text-recognition-japanese`) | Default recognizer out of the box, and the fallback for the optional engine | Bundled in the app |
| PP-OCRv6 Medium (`PP-OCRv6_medium_rec`, 76.6 MB) | *Recommended* optional engine: ordinary screen and novel text | Google Play fast-follow asset pack (`ppocr`); no other install can get it |

Gaten itself downloads neither engine's weights: the pack is Google Play's own delivery, and the
reader's **Download** only asks Play for it. An install Google Play did not make — a sideloaded APK,
an F-Droid build, an image without Play — has no pack to read, so it cannot install the optional
engine at all and reads with ML Kit.

## Delivery

PP-OCRv6 Medium has exactly one delivery path: the `ppocr` fast-follow asset pack on Google Play.
`ocr/PpOcrModel.kt` resolves the files inside the pack and `ocr/PpOcrEngine.kt` opens the ONNX model
straight from that directory — nothing is copied out, and there is no second copy to fall back to or
to reconcile.

**Google Play (fast-follow asset pack).** The `:ppocr` module is a `com.android.asset-pack` module
with `packName = "ppocr"` and `deliveryType = "fast-follow"`, which carries
`pp-ocr/medium/rec.onnx` and `pp-ocr/medium/dict.txt`. Play downloads it in the background after
install — without the reader opening Settings and without Gaten asking — and verifies the transfer
itself, so `ocr/PpOcrPack.kt` reads the files straight out of
`AssetPackManager.getPackLocation("ppocr").assetsPath()`.

- A fetch resolving is not completion: Play answers as soon as it has *accepted* the request.
  Completion is the pack reaching `COMPLETED`, so a state listener is registered before anything is
  requested or queried, and the settings screen's `downloading` list reads Play's live state as well
  as the module's own in-flight request. A fast-follow download can start before the app's process
  exists and can outlive it.
- `WAITING_FOR_WIFI` and `REQUIRES_USER_CONFIRMATION` are not counted as *downloading*: nothing is
  moving, and reporting them as a transfer would leave the settings screen spinning indefinitely.
  When Play asks for confirmation, the dialog is raised from native
  (`AssetPackManager.showConfirmationDialog`) so the JS contract has no dialog of its own. Every way
  the wait can end is terminal — `COMPLETED`, `FAILED`, `CANCELED` and `WAITING_FOR_WIFI`, the last
  reported as "waiting for Wi-Fi" because Play will not move bytes for that request now — so an
  explicit download always answers instead of hanging on "Downloading…" until the timeout. A request
  Play rejects outright ends the wait immediately with Play's own reason (the fetch `Task`'s failure
  listener, since no state update follows a rejected request), and each request arms the watch with a
  non-terminal state first, so a conflated terminal state left by an earlier attempt cannot answer a
  new one. The settings read that answers the JS is itself bounded at 2 s, so a Play service that
  stops answering leaves the last reported state standing rather than an unpopulated screen.
- Both files are resolved from **one** directory — the pack's — and only when both pinned sizes are
  present; a `getPackLocation` result on its own is not "installed", so a half-extracted pack is never
  handed to ONNX Runtime, and a pack that is missing or incomplete reads as "not installed".

**No other delivery path.** A build Google Play did not install, or a Play install whose pack cannot
be served because Play Core is unavailable on the device, cannot install the engine:
`PpOcrModel.download` refuses with a clear message — there is no downloader behind this to reach for
— and Gaten keeps no copy of the model of its own, so nothing is written to app storage and there is
no second copy to exclude from backup or to reconcile. Selecting PP-OCRv6 Medium on such a build
simply reads with ML Kit, exactly as an uninstalled engine on a Play install does.

This is a *delivery* improvement, not an APK-size reduction. The PP weights were already excluded
from the app before the pack existed; the ~100 MB base download is the bundled dictionaries
(JMdict/JMnedict, Tanaka Corpus, RTK), ONNX Runtime, React Native with Hermes and ML Kit. What the
pack changes is that a Play install gets the recommended engine without a settings trip, and that the
base `android.assetPacks` list is only populated for a bundle build (`-PplayBundle=true`), so an APK
build never sees the pack at all.

Play's published sizing guidance (from Play's documentation, not verified here): the base module's
compressed download may be at most ~500 MB, each asset pack at most 1.5 GB, and fast-follow plus
on-demand packs ~30 GB cumulatively; above ~200 MB a cellular install shows a non-blocking size
dialog. The `ppocr` pack is 76.6 MB of one model plus its dictionary, far inside every limit.

### What the build verifies, and what it does not

`ppocr/build.gradle.kts`'s `stagePpOcrAssets` fetches the pinned files into the pack at bundle-build
time, checking each against its exact size and SHA-256 (the hashes below) and writing through a
`.part` file, so a truncated or replaced local copy can never be staged. A file already at its
pinned size and hash is left alone, so the network is needed once. Because the hash was checked at
build time, the app trusts the pack's files by size alone.

## Pinned sources

| File | Pinned source | Bytes | SHA-256 |
| --- | --- | --- | --- |
| PP-OCRv6 Medium ONNX | [PaddlePaddle/PP-OCRv6_medium_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx) @ `50c7eacafc52fa7bcf4194e8cd08e46f8558504b`, `inference.onnx` | 76,554,979 | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba` |
| Dictionary | [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) @ `b03f46425e8ff4442b268ce449e3eef758146cd4`, `ppocr/utils/dict/ppocrv6_dict.txt` | 74,947 | `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d` |

Total 76,629,926 B (76.6 MB, model + dictionary), the same catalogue the settings screen reports as
the download size. `ppocr/build.gradle.kts` carries these source pins and verifies them when it stages
the pack; `ocr/PpOcrModel.kt` carries only the file names and their exact sizes, which is what it
checks on the pack Play delivered. The two lists must agree — a stale size there would read a
half-delivered pack as installed.

## Graph contract

| | PP-OCRv6 Medium |
| --- | --- |
| Input | `x` float32 `[N, 3, 48, W]` (N and W symbolic; the app runs N = 1) |
| Output | `fetch_name_0` float32 `[N, T, 18710]` |
| Sequence | `T` is roughly `W / 8`, exactly `W / 8` at the widths probed (48, 320, 640, …) |
| Classes | 18710 = blank (0) + 18,708 dict symbols (1..18708) + space (18709) |
| Preprocess | height 48, planar BGR, `(px/255 - 0.5) / 0.5` |
| Decode | CTC greedy: argmax, drop class 0, collapse repeats |
| Mode id | `medium` (`RecognitionMode.PP_MEDIUM`) |

The graph was read off the ONNX file itself: ONNX IR 6, opset 11, about 19.1M parameters.
`PpOcrEngine` refuses a session whose input, output or class count does not match this contract,
rather than decoding a mismatched model/dictionary pair into garbage.

## How Gaten uses it

`RecognitionMode` is one of `ml_kit` (default) or `medium`. The choice is the reader's; there is no
classifier. ML Kit is bundled and always installed, so it is what a fresh install reads with, and it
is the fallback: at read time an engine that is not installed, fails, or returns blank / placeholder
/ no-Japanese text leaves ML Kit's reading in place — the screen index's own reading of the line for
the overlay, or a fresh ML Kit read of the crop for a circle or a tap, whose rectangle the index may
not cover. Because the engine is a recognizer, a fallback only happens when it produces nothing
usable, not when it produces confident-but-wrong text. With `ml_kit` selected there is nothing to
fall back from: the ML Kit reading is the answer.

A stored `manga_ocr` or `manga` value from an older build migrates to `medium` rather than to the
default, so a reader who had chosen a downloadable engine keeps one; a missing or unknown value
becomes `ml_kit`.

At most one ONNX session is resident. `OcrModels` releases it after 90 seconds idle, and the session
is rebuilt when the pack's files change — their path, size and modification time are the key — so a
pack update, whose extraction path differs, is picked up instead of being served from a stale
session.

## Limits

### Width

The tensor width is dynamic; `T` is roughly `W / 8`, so width is the character budget. Gaten clamps
it into `[320, 1280]` (`PpOcrGeometry`): 320 is PaddleOCR's `RecResizeImg` default, so short lines
pad up to it; 1280 is the ceiling (roughly 26 CJK characters before a wider line is squeezed). The
upstream reference pipeline instead uses a floor of 16 and a cap of 640, or 2400 for a long strip.
The logits tensor `[T, 18710]` float32 is the cost driver — about 74,840 bytes per timestep, up to
roughly 12 MB at `T` = 160.

### Vertical text

The recognizer is not natively vertical. A crop taller than wide is rotated a quarter turn
counter-clockwise before it is fed (`PpOcrGeometry` / `PpOcrPreprocess`), matching the upstream
reference pipeline (`cv2.ROTATE_90_COUNTERCLOCKWISE`). Detection always comes from ML Kit, and with
`ml_kit` selected so does recognition.

### Multiline regions

Unsupported: this is a single-text-line recognizer, one crop, one line. A crop that spans several
lines (for example a circle around a block of text) is passed to the recognizer as one run of text;
there is no automatic detection of that, and no guaranteed fallback, so it may misread or garble the
region silently.

### Android, ONNX Runtime 1.31, CPU FP32

The optional engine runs on the ONNX Runtime Mobile the app uses
(`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`); the bundled ML Kit recognizer runs on ML
Kit's own runtime, which the app already includes for detection. Play Core's asset-delivery client
(`com.google.android.play:asset-delivery`) ships in every build and is inert where the pack does not
exist.

- ABI: the 1.31.0 AAR ships `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; Gaten packages
  `arm64-v8a`, `armeabi-v7a` and `x86_64` (`app/build.gradle.kts` split). One FP32 ONNX runs on
  every ABI.
- minSdk 29 (Android 10). CPU execution provider. R8-minified builds need
  `-keep class ai.onnxruntime.** { *; }` (present in `app/proguard-rules.pro`).
- Memory: peak resident RAM was not measured. It exceeds the model file size — ORT holds the weights
  plus activations, buffers and the graph's tensors — with the logits up to roughly 12 MB on their
  own.

## Attribution and licence

The optional engine and its dictionary are Apache License 2.0; ML Kit is Google's, under the ML Kit
terms of service; Play Core's asset-delivery client is Google's, under the Play Core SDK terms.

- ML Kit Japanese text recognition: Google LLC, bundled and used on-device
  ([ML Kit terms](https://developers.google.com/ml-kit/terms)).
- PP-OCRv6 Medium: PaddlePaddle `PP-OCRv6_medium_rec_onnx` (Apache-2.0). Dictionary:
  PaddlePaddle/PaddleOCR (Apache-2.0).
- Play Asset Delivery client: Google LLC, `com.google.android.play:asset-delivery`.

The Apache-2.0 text is bundled at `app/src/main/assets/licenses/PP-OCRv6-Apache-2.0.txt`; the
attributions are recorded in `NOTICE` and the README credits. Gaten does not redistribute the
weights in its APK or base module, and it never downloads them itself: the pack is a separate
Play-delivered module, so only a Google Play install has the files. ML Kit's models are bundled
through Google's own dependency, under the ML Kit terms. ONNX Runtime (Microsoft, MIT) is already
attributed.

## Verification

Done, on this revision: both pinned files were fetched over HTTPS and hash-verified — `rec.onnx`
76,554,979 B `9c09abf0…`, `dict.txt` 74,947 B `b5f2bfe2…`, the dictionary holding 18,708 lines and so
18,710 output classes once the blank and the trailing space are counted — and the ONNX graph was read
with `onnx` 1.23.2: IR 6, opset `ai.onnx` 11, input `x` `[N, 3, 48, W]` with both dynamic dimensions
symbolic, output `fetch_name_0` `[N, …, 18,710]`, 254 initializers totalling 19,115,263 parameters.

Builds: `:app:testDebugUnitTest` (604 tests, 0 failures, 0 errors), `:app:assembleDebug`,
`:app:assembleRelease` and `:app:bundleRelease -PplayBundle=true` all succeeded. Both APK builds ran
with `--offline`, so neither needed the pack, its staged assets or the network, and their outputs
carry no recognizer weights — the largest entries of the arm64 release APK are
`assets/db/jmdict.sqlite` (109,486,080 B), `assets/db/examples.sqlite` (59,252,736 B),
`lib/arm64-v8a/libonnxruntime.so` (34,208,672 B) and `assets/db/rtk.sqlite` (5,988,352 B). The bundle
build ran `stagePpOcrAssets`, which fetched and hash-verified both files into the pack, and the
resulting AAB carries `ppocr/assets/pp-ocr/medium/rec.onnx` (76,554,979 B) and
`ppocr/assets/pp-ocr/medium/dict.txt` (74,947 B). No Manga OCR symbol (`MangaOcr*`, `MANGA_OCR`,
`manga_ocr`, `PP_MANGA`) or `fetch_manga` tool remains in the tree; the two survivals are intentional
and named in the PR: the gitignore entry and the `ignoreAssetsPattern` that keep a stale
`assets/manga-ocr` directory out of the build, and the retired `manga_ocr`/`manga` ids that
`RecognitionMode.fromId` migrates to `medium`.

Not done, and not claimed here: no on-device inference timing or accuracy comparison, no measured
memory figure, and no device run of the Play delivery path (Play grades asset-pack delivery by
its own testing, which this repository does not perform). The `T`-width relation was observed only
at multiples of 8; a dynamic-width implementation may not hold to it between them. The authorized
release workflow builds and signs the APK and AAB remotely; nothing here claims that release or Play
publishing has succeeded.

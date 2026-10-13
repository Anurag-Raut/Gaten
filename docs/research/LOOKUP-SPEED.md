# Lookup speed research

Research date: 2026-10-13. Initial app source inspected at `155d7b6`. The initial research findings below describe that baseline; subsequent implementation and verification are recorded at the end.

| User context | Finding |
|---|---|
| Detection | Boxes usually appear in about one second or less. |
| Lookup | Tapping or circling takes about 1–2 seconds, with noticeable variation. |
| Required behavior | Start box processing at the earliest point after the trigger, while the overlay outline animates; animation completion must not gate work or interaction. |
| Accuracy constraint | Preserve the exact selection crop and the currently accurate reader. |
| Main research priority | Reduce foreground lookup work and contention before replacing detection or OCR models. |

## TODO: overlap box processing with overlay animation

- [x] Inspect whether OCR waits for the outline animation to complete.
- [x] Inspect the earliest point at which screenshot pixels are available.
- [ ] Measure trigger → screenshot ready → scan submitted → boxes ready, alongside animation start/end, if device profiling is requested.
- [x] Start one screenshot-scoped scan as soon as the software screenshot is accepted, before waiting for the frozen frame and composing the lookup UI. Reuse that scan in the UI and cancel it with its capture; keep native-task bitmap ownership safe. Implemented in `OverlayWindowController` and `LookupOverlay`.

**Static result: partially already implemented.** `LookupOverlay.kt:166` starts the screen scan independently of `EdgeOutline.kt:59`, whose reveal animation lasts 900 ms. Detection does not wait for that animation. However, `OverlayWindowController.kt:217` waits for the screenshot frame to commit plus another vsync before creating the lookup composition. OCR starts from that composition, so it does not start at the earliest screenshot-ready point. Recognition cannot start from the trigger alone because the screenshot pixels do not exist yet. Keep the existing waits that avoid capturing the notification shade or floating button; preserve the freeze-before-media-pause ordering.

## Highest-value options

Priority describes research value for the reported 1–2 second gesture delay. Benefits below are hypotheses unless explicitly identified as structural facts; no new phone timings were collected.

| Priority | Candidate | Why it matters | Accuracy / implementation constraint |
|---|---|---|---|
| Highest | Give gestures priority over speculative OCR and Meiki tiles | Gesture OCR shares the manga engine with background line reads; Meiki competes for CPU | Keep the same crop/model; defer background work rather than dropping useful detections |
| Highest | Cache and join identical exact-crop reads | Every tap now runs fresh OCR, including repeated taps | Existing padded line cache is not an exact-crop cache |
| High | Stop copying all decoder logits into Kotlin each step | Only the last token position is used, but every position is copied | Pinned outputs can retain the original graph; graph ArgMax requires equivalence checks |
| High | Benchmark explicit ORT thread budgets | Multiple sessions have their own pools, with no explicit app limits | Tune encoder, decoder and detector separately; measure frame smoothness and tail latency |
| High | Investigate decoder KV-cache export | Current decoder repeats the whole prefix for each character | Requires a new export, mask/position handling and output comparisons; not a settings toggle |
| High for circles | Display accurate crop answer before supplemental line OCR | Circles await up to three additional line reads after their primary crop | Preserve final supplemental entries and stable selection/context; first answer is partial |
| Medium | Benchmark encoder arena / memory-pattern reuse | Both optimizations are explicitly disabled | Existing memory-saving decision is intentional; measure active memory and latency |
| Medium | Share dictionary memoization across the complete lookup | Supplement and crop currently have separate memoization maps | Preserve repair, deinflection, ranking, misses and name results |
| Medium for cold start | Prepare models and dictionary earlier | Model loading and DB readiness can enter the gesture critical path | Avoid racing startup with detection or retaining models indefinitely |
| Medium for detection | Start scanning before lookup UI composition | Removes the frozen-frame/composition dependency from scan submission | One scan per accepted capture; native-task bitmap ownership must remain safe |
| Later | New detectors, OCR models or hardware providers | Potentially larger improvements | Android compatibility and accuracy on the user's content are unproven |

## What the app currently waits for

### Detection and outline

The current sequence is:

```text
trigger accepted
  → hide our floating button / allow shade to collapse where needed
  → request screenshot
  → create software screenshot on capture executor
  → accept screenshot and attach frozen ImageView
  → wait for frame commit + one vsync (or the existing timeout)
  → compose LookupOverlay
      ├─ submit full-screen ML Kit scan on Dispatchers.Default
      ├─ open dictionary on Dispatchers.IO
      └─ animate overlay edge reveal for 900 ms
  → publish complete ML Kit index
      ├─ make indexed lines tappable
      ├─ fade text-box outlines in for 250 ms
      ├─ prefetch every line with manga-ocr
      └─ run Meiki tiles, then append and read extra lines
```

The edge reveal is independent of scan completion. The box-outline fade is independent of hit testing. Consequently, there is no 900 ms animation-completion gate to remove. Starting detection at screenshot acceptance could still overlap more work with first-frame drawing and composition. Its benefit must be measured: the existing delay protects the frozen frame and player behavior, and moving CPU work earlier could also make that frame slower.

Meiki-only boxes are different: they begin as empty lines without character boxes. `ScreenHits.hitTestLine()` ignores those placeholders and `LassoLayer` does not draw their outlines until a reading supplies characters. Those boxes therefore appear after detector **and reader** work, not simply detector completion. Making them immediately tappable is a separate behavior change: false detections and non-Japanese lines currently get filtered by reading first. Early selectable candidates would need a deliberate policy and content evaluation.

### Tap

`LookupOverlay.kt:385` awaits dictionary readiness, then calls `runLookup`. `LookupPipeline.kt:71` crops the selected box and reads it through manga-ocr. After that, the selected index line is scanned for supplemental dictionary words; the crop is repaired and scanned too, and the merged result is assigned. The sheet's 220 ms slide starts only after the complete result exists.

The change in `155d7b6` is a concrete explanation for additional tap work: the old tap joined `ScreenLineReader.read()` and could use its cached result. The new tap always performs exact-crop OCR. This is the behavior that improved accuracy, so restoring the old tap path would sacrifice the requested crop semantics.

### Circle

The crop is read first. Then the index callback calls `readGestureLines()`, which awaits up to three touched lines sequentially. Each line wait is bounded by 2,500 ms; these limits do **not** form one overall gesture deadline. After line reads, dictionary repair, scanning and merging finish before the sheet opens.

The exact-crop manga call itself has no gesture timeout around it. Its decode loop stops on EOS, cancellation, or the 300-token cap. The line reader's 2.5 second timeout does not automatically bound this direct crop call. These facts permit longer tails, but do not establish that timeouts are happening on the user's device.

## Why identical-looking gestures can have different delays

| Possible contributor | Code evidence | How to distinguish it later |
|---|---|---|
| A background line already occupies manga-ocr | `MangaOcrEngine.recognize()` holds one mutex for complete inference | Separate engine queue wait from encoder/decoder time |
| Meiki is running at the same time | Meiki and line prefetch both start after ML Kit readiness | Record active detector tile intervals against gesture timings |
| Longer decoded text | Every character requires a decoder call, over a growing prefix | Record decode call count and time per call |
| Models are cold | Sessions load lazily and are released after 90 seconds of actual model inactivity or memory pressure | Record session-load intervals and whether sessions were reused |
| Additional circle line reads | Up to three sequential waits occur after the crop read | Record crop completion and supplemental line completion separately |
| Dictionary work varies | OCR repair probes alternatives; word scanning probes spans and deinflections | Record cache misses, SQL time, repair time and word-scan time |
| Allocation/GC pressure | Heap logits buffers and preprocessing arrays are repeatedly allocated | Align GC events and allocations with slow gestures |
| UI presentation time | Sheet enters over 220 ms; drag state updates copy points and redraw outlines | Separate first result frame, animation finish and inference finish |
| Device load / temperature | Multiple native pools and the app beneath the overlay share CPU resources | Compare short and sustained usage with thermal/frame traces |

There is no evidence yet that any one of these dominates the latest phone. Source inspection proves extra work exists, not its percentage of the reported wait.

## Preserving accuracy while removing work

### 1. Foreground work should take priority

Once detection finishes, the app immediately prefetches all lines and runs Meiki. A gesture enters the same manga engine used by prefetch. A coroutine being on `Dispatchers.Default` does not make its native CPU work inexpensive or lower-priority.

The simplest candidate is to stop starting new background reads while a gesture is pending, let the current native call finish safely, and run the foreground crop next. Resume useful prefetch and detector work afterward. Consider doing this from pointer-down rather than waiting for tap release or lasso completion, without starting OCR on an unfinished crop.

Important detail: cancelling `prefetchJob` only cancels the ordering/awaiting coroutine. The actual line Deferred belongs to `ScreenLineReader`'s independent scope and keeps running. A foreground-aware scheduling check or deliberate cancellation of the speculative Deferred is required. A cancelled Deferred must be removed or made retryable; otherwise a future line request can join a permanently cancelled result. The engine already checks cancellation between decoder calls, but never closes a session while a native call uses it.

Do not describe this as waiting behind an entire prefetch queue: current prefetch submits one line then awaits it. The immediate cost is the in-flight line, possible scheduling races, and CPU competition. Meiki has a separate session and lock; it competes for CPU rather than occupying the manga mutex. Pause between tiles, preserving eventual full coverage, rather than closing a live session or abandoning every missing box.

### 2. Exact-crop caching and joining

Use a screenshot-scoped key containing the effective integer crop, crop/preprocessing mode, and model revision. Store the **raw** model answer and apply the caller's acceptance/merge policy afterward. Successful identical requests can reuse or join the same inference; failed/cancelled reads should remain retryable. Drop the cache with the screenshot and invalidate when the model changes.

The current background path calls `readLineWithMangaOcr` on the full screenshot, so preprocessing can pad beyond the line. The tap/circle path first cuts the selection and therefore clamps padding at its edge. Those are different model inputs even when their line bounds match. The reader also applies a length acceptance policy that the exact crop deliberately bypasses. Its accepted text is therefore not a substitute for the crop's raw reading.

A small exact-crop cache improves repeated gestures immediately. Improving the *first* tap through prefetch requires prefetching the same exact crop, or maintaining an explicit distinction between exact and padded reads. Keeping both doubles some speculative work; replacing padded reads can change supplemental results. Start by removing duplicate identical requests, then evaluate whether exact prefetch can serve both uses safely. Cache final dictionary results only if supplemental-index revision and dictionary/model state are included; otherwise a later result can miss improved supplement entries.

### 3. Reduce decoder output allocation and transfer

`MangaOcrEngine.kt:169` calls `logits.floatBuffer`, then reads just the last row. ONNX Runtime Java's `getFloatBuffer()` creates a copy. This is confirmed in the app's exact runtime version, 1.31.0, rather than inferred from another binding. [ONNX Runtime 1.31.0 tensor source](https://github.com/microsoft/onnxruntime/blob/v1.31.0/java/src/main/java/ai/onnxruntime/OnnxTensor.java).

The published manga decoder has a vocabulary of 6,144. A float row is 24 KiB. With 30 decoder calls over prefix lengths 1–30, copying every row creates about 10.9 MiB of heap buffers; with 60 calls, about 42.9 MiB. This is an allocation calculation, **not** a measured latency or peak-memory figure. [Model configuration](https://huggingface.co/onnx-community/manga-ocr-base-ONNX/blob/main/config.json).

Two alternatives deserve comparison:

- Keep the original model graph and use caller-owned direct output buffers with Java's pinned-output `run` overload. Shapes must match each actual prefix length; retaining every length's full tensor would waste memory. Read the owned buffer directly instead of calling the copying getter. This removes heap copies but not the model's full-prefix computation. [Java session API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.html).
- Add a last-position gather followed by ArgMax **after** the existing logits computation and request only the resulting token. This reduces host output to one ID and removes the Kotlin vocabulary scan. Keep the first-index tie rule (`select_last_index=0`), and explicitly compare finite logits, unusual outputs, EOS and decoding. It still computes the original prefix internally. [ONNX ArgMax specification](https://onnx.ai/onnx/operators/onnx__ArgMax.html).

`getBufferRef()` is not a universal zero-copy accessor for native-created outputs; it refers to tensor buffers supplied/owned through the Java buffer path. Do not propose it as a one-line replacement without establishing ownership. Requesting the existing `logits` output by name also does not select its final row.

Moving the gather earlier, before the vocabulary projection, can additionally remove head computation for previous positions. This requires graph inspection. In a dynamically quantized graph, changing the tensor used to compute quantization ranges can change the newest logits, so this more aggressive rewrite needs stronger equivalence/accuracy evaluation.

### 4. Cached decoding with the same trained weights

Current inference runs the encoder once, then reruns the decoder over every previous token. A decoder export exposing past self-attention keys/values can reuse prior activations. Encoder cross-attention projections may also be reusable if the export exposes them. The base model's configuration enables `use_cache`, but the installed ONNX files and Kotlin input contract do not expose caches; adding a Kotlin flag cannot fix that.

Hugging Face documents cached decoder exports and their purpose. Its generic examples do not prove that this manga encoder-decoder exports successfully with the necessary contracts. The community model file listing checked during this research contains ordinary encoder/decoder variants and no advertised decoder-with-past file. [Cached ONNX exports](https://huggingface.co/docs/optimum-onnx/onnx/usage_guides/export_a_model).

This is a promising larger optimization: retain the crop, trained weights, tokenizer, greedy decoding and EOS behavior, while avoiding repeated decoder work. It requires explicit masks/positions, cache lifecycle and initial-step handling. Dynamic quantization and changed kernels can still change near-tied predictions; the same weights alone do not guarantee identical output. Compare raw tokens and OCR quality across short, long, stylized, vertical and multi-line crops before replacement.

### 5. Runtime sessions: tune speed without creating a new framework

All three native model sessions currently use default thread settings. Meiki runs while manga prefetch/gesture work and rendering can be active. ONNX Runtime documents per-session thread pools, intra-op parallelism, default spinning and contention. More threads are not always faster. Candidate thread budgets of 1, 2 and 4 are experimental points, not proposed user-facing settings or a claim that any one is best. Tune the encoder, per-token decoder and detector independently; tiny repeated decoder calls may behave differently from the encoder. [ORT threading](https://onnxruntime.ai/docs/performance/tune-performance/threading.html).

`MangaOcrEngine.kt:208` explicitly disables CPU arena allocation and memory-pattern optimization to reduce retained memory. Benchmark enabling reuse on the fixed-shape encoder first; growing decoder shapes require separate investigation. Changing both blindly can increase memory pressure enough to erase a speed benefit. Idle and memory-pressure release must continue to work. [Java session options](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html).

Other bounded candidates: reuse native-order direct input buffers, token storage and preprocessing arrays inside the already serialized engine; load Meiki from an installed file instead of re-reading its 41 MB compressed asset into a byte array on each cold session load; prepare an optimized ORT-format graph offline to reduce cold setup work. Do not assume graph optimization is currently off: ORT enables graph optimizations by default. [ORT graph optimizations](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html), [ORT model format](https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html).

### 6. Faster first answer for circles

The primary exact crop finishes before up to three supplemental lines are awaited. Publish its accurate text and words when ready, then add the same supplemental entries as background work completes. Preserve final ordering, deduplication, word cap and stable UI positions; do not silently remove supplementation or substitute a lower-accuracy ML Kit answer for the primary crop.

This improves time-to-first-useful-result rather than making all OCR finish sooner. It cannot make an uncached crop instant. It also changes when the complete set of dictionary entries is available, which must be treated as a visible product choice. An alternative with less UI change is to eliminate matching duplicate line reads and tune their priority while retaining the existing complete-result presentation.

### 7. Dictionary and image work

`lookupFromSelection()` and `mergeLookup()` each build their own memoization wrapper around the same repository accessor. One outer operation-local cache would let crop repair/scanning and supplemental repair/scanning share both hits and misses, preserving query ordering. Existing database indexes are already present; this is not a missing-index diagnosis. Deinflection and repaired-line results could also be reused when text/context is identical, but only if measurements show they matter.

Currently `awaitRepo()` precedes `runLookup()`. On the first or a cold overlay, crop OCR can start independently while the dictionary opens, joining the repository only when words are needed. The database already opens in parallel with screen detection; the potential extra overlap is specifically a gesture arriving before it is ready.

Preprocessing creates crop, optional upscale, grayscale, 224×224 resize and float inputs. Buffer reuse can reduce churn while keeping the same pixels. Combining grayscale/resizes or skipping an intermediate resize can change interpolation and rounding, and therefore model input. Those changes need pixel/output comparisons; they are not automatically accuracy-neutral. Bitmap recycling already has fixes in place, and ML Kit input lifetimes extend through native Task completion, even when the waiting coroutine is cancelled.

## Detection options, with lower priority for the latest symptoms

| Option | Opportunity | Constraint |
|---|---|---|
| Earlier scan submission | Overlap OCR with overlay frame preparation/animation | Preserve first frozen frame, capture cancellation and screenshot ownership |
| Publish line geometry before ink/character refinement | Earlier visual feedback on dense screens | Tightened boxes affect exact crops and taps; preliminary geometry needs its own explicit behavior |
| Progressive Meiki tile publication | Missing lines can appear before every tile finishes | Cross-tile NMS can revise boxes; stable indices/crops need careful handling |
| Schedule Meiki after foreground gesture | Reduce competition with primary crop inference | Keep eventual coverage of lettering ML Kit missed |
| Change detector thread settings / buffers | Same detector and crop policy | Measure total screen time, not just one isolated tile |
| Try Meiki v0.1 960×544 | New MobileNetV4 backbone; author reports lower latency | Designed more for games, 64-box limit; different preprocessing from current square letterbox |
| Try Meiki v0.1 320×192 or old tiny | Smaller/faster detector candidate | Small text, manga recall and box count can regress |
| Lower-resolution ML Kit scan | Less full-screen work | Tiny kana/furigana can disappear; original screenshot should remain the crop source |

Meiki's model card now lists v0.1 variants with a game focus and 64 detections. That is not a drop-in replacement for the current `small.v0` 640×640 tiled detector. Published timings are not measurements on the user's phone. [Meiki detector model card](https://huggingface.co/rtr46/meiki.text.detect.v0).

ML Kit's bundled Japanese recognizer is already used and reused by the app. Google says Japanese recognition is slower than its Latin recognizer and recommends sufficient character pixels, ideally at least 16×16. Blind screenshot downscaling conflicts with retaining small-text accuracy. [ML Kit Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

## Alternative readers and hardware acceleration

These are candidates for later evaluation, not recommended replacements for the currently accurate reader.

| Candidate | Verified useful property | Reason to keep it exploratory |
|---|---|---|
| Baberu OCR v1.1 | Ships ONNX vision, decoder-prefill and cached decoder-step files; model author reports improved Japanese accuracy | Different tokenizer/preprocessing/decoding; fp16/int4 vision compatibility needs checking; available GPU comparison does not show faster single-crop reads |
| Meiki recognition | Predicts characters and their boxes without the same autoregressive loop | Trained for rendered game text; 48-character line limit and beta vertical support in its pipeline |
| MangaOCR NAR Preview | Predicts ordered text positions in one forward pass | ~196M parameters; inspected repo has no ONNX model; weights have a noncommercial license; phone performance unmeasured |
| MangaOCR-Nar-Small | Published benchmark lists ~54M model and strong speed/accuracy | Weights marked “to be released”; not an installable option today |
| Hayai v2.5 Nova | Uses preallocated KV cache and fewer vision tokens | ~157M parameters; inspected repo has no ONNX model; custom architecture needs mobile export work |
| PP-OCR mobile Japanese | Lightweight line recognizer family | Plain-text suitability does not establish accuracy on manga bubbles, stylized lettering or vertical layouts |

Baberu's ONNX package and cached inference are documented by its publisher. Its own reported accuracy is useful evidence, not proof it will retain the app's exact results. [Baberu model card](https://huggingface.co/genshiai-daichi/baberu-ocr).

The current JMangaBench_Read v1.3 reports GPU single-image times of 123.6 ms for Baberu v1.1 and 84.9 ms for mangaOCR, versus 31.9 ms for NAR Preview and 9.1 ms for unreleased NAR Small. These comparisons use RTX 2080 Ti/fp16, not Android int8. Benchmark authors also developed the NAR models and disclose development use of some benchmark data. Sound effects are excluded from its main set. Treat it as a shortlist aid, not a mobile ranking or independent guarantee. [Benchmark, model availability and disclosures](https://github.com/muscgab/JMangaBench_Read).

The game-text constraints come from the recognizer publisher and official pipeline. The NAR/Hayai properties come from their model cards and published file listings inspected on the research date. [Meiki recognition](https://huggingface.co/rtr46/meiki.txt.recognition.v0), [Meiki pipeline](https://github.com/rtr46/meikiocr), [NAR Preview](https://huggingface.co/muscgab/manga-ocr-nar-preview), [Hayai Nova](https://huggingface.co/JustANormalTinkerer/hayai-ocr-v2.5-nova). Paddle documents a small Japanese recognizer, but its documentation benchmarks do not establish performance on this app's crops. [Paddle recognition models](https://paddlepaddle.github.io/PaddleOCR/main/version3.x/module_usage/text_recognition.html).

Hardware candidates also need graph-level checks:

- **XNNPACK:** available in ORT Android, but its operator coverage is constrained, including 2D MatMul. It does not imply acceleration of every dynamically quantized transformer operation. Verify actual node assignment before spending time tuning provider threads. [XNNPACK provider](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html).
- **QNN / Qualcomm NPU:** potentially suitable for a supported Qualcomm phone and fixed-shape encoder/detector. Requires compatible graph/quantization and native packaging; QNN EP documents lack of dynamic-shape support. The growing-prefix decoder is harder than the fixed encoder. Do not infer the chipset only from the handset name. [QNN provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html).
- **NNAPI:** existing ORT option, but Android deprecated it in Android 15. It is a lower-priority new investment; supported-node fragmentation and CPU fallback must be measured. [Android NNAPI status](https://developer.android.com/ndk/guides/neuralnetworks), [ORT NNAPI coverage](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html).
- **LiteRT / ExecuTorch / custom native kernels:** larger runtime migrations with conversion, packaging and numerical-evaluation costs. Consider only if the focused ORT work cannot meet measured latency needs.

The app already uses int8 manga-ocr. “Quantize it” is therefore not a new optimization. Switching to q4/fp16 is not justified by file size alone; the repository records prior fp16 export-load problems and unevaluated q4 accuracy. Native CPU math is not made dramatically faster merely by compiling the Compose UI in release mode.

## Smoothness and useful latency measurements

Preserve the immediate selected-box highlight, which already appears before tap OCR. Consider showing the result-sheet container immediately, filling it with the accurate result when available, or shortening its 220 ms entry animation. This is presentation work: it can improve perceived responsiveness without reducing inference duration. The outline reveal and OCR must remain independent.

While dragging, `points = points + it` copies the accumulated list, `boundingBoxOf` scans it, and `buildTrailPath` rebuilds it. Large gestures can therefore allocate and do increasing work per pointer event. If frame traces show this cost, maintain gesture points/path/bounds in a small dedicated drawing state and append incrementally while preserving the same final selection rectangle. Avoid aggressive sampling that changes what the user selected. Scope OCR-result state updates so background line reads do not repeatedly rebuild unrelated gesture/sheet UI. [Compose performance practices](https://developer.android.com/develop/ui/compose/performance/bestpractices).

The recently shared artifact was a debug APK. A non-debuggable, optimized build and an app-specific Baseline Profile can improve Kotlin/Compose startup and rendering. These do not remove native decoder prefix work or engine waiting. Profile the accessibility-trigger → overlay → gesture path specifically, rather than only the home activity. [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance), [Compose Baseline Profiles](https://developer.android.com/develop/ui/compose/performance/baseline-profiles).

Suggested product targets for discussion are a selected-box response within one display frame and ordinary warm single-line first useful results in a few hundred milliseconds. These are aspirations, not forecasts; cold model loading and long selections need separate reporting.

If device profiling is requested, measure the existing behavior before changing it:

| Record | Why |
|---|---|
| Trigger, screenshot ready, frozen frame, scan submission, boxes ready, outline start/end | Check the early-start TODO and distinguish capture/OCR/animation |
| Gesture down/up, engine enqueue/acquire, current background task | Distinguish queue delay from inference |
| Model/session load, crop preprocessing, encoder time | Expose cold-start and fixed per-read costs |
| Decoder calls, per-step time, logits transfer/argmax time | Expose text-length and allocation costs |
| Primary crop completion, each supplemental line completion | Quantify the circle-specific extra wait |
| Dictionary ready, SQL hit/miss count/time, repair/scan time | Locate dictionary cost without weakening rules |
| Result assigned, first useful sheet frame, sheet animation complete | Measure responsiveness rather than just computation |
| GC, CPU scheduling, PSS/native memory, frame deadlines, thermal state | Explain slow outliers and memory/latency tradeoffs |

Use short same-crop comparisons for immediate versus delayed gestures on the same screenshot, repeated taps, short versus long lines, active versus completed prefetch, and idle-released versus warm models. Report p50 and p95, not just the fastest example. A source-identical crop is essential: changing selection size changes both OCR work and results.

For accuracy comparisons, retain exact-crop text, word ordering, readings/glosses and context; include dense manga, vertical/furigana, outlined subtitles, game UI, proper names, slang, Latin-looking misdetections, long selections and failed/empty results. Scheduling/cache work should preserve successful final results; new graphs, quantization or providers need token/output and task-quality comparisons. Do not create a broad benchmark harness until there is authorization for device verification.

ORT has an inference profiler and Android supports custom trace events; async/coroutine spans need suitable identifiers rather than mismatched cross-thread begin/end slices. [ORT profiling](https://onnxruntime.ai/docs/performance/tune-performance/profiling-tools.html), [Android custom trace events](https://developer.android.com/topic/performance/tracing/custom-events).

## Evidence limits and follow-up TODOs

The earlier `docs/optimization-audit.md` records Moto g35 measurements: ML Kit readiness 1.3–2.9 seconds, Meiki roughly 7–9 seconds and warm manga line reads around 0.5–1.6 seconds. Those are older measurements on a different phone/scene. The underlying `.gradle/device-audit` reports referenced there are absent from this checkout, so the summarized numbers were not independently rechecked. Current user-reported timings take priority for this research.

- [ ] Determine which of engine wait, encoder, decoder, supplementation or dictionary work dominates on the current phone, if profiling is requested.
- [ ] Compare foreground-priority scheduling with the same content and final results.
- [ ] Evaluate an exact-crop cache and duplicate-request joining; avoid padded-cache substitution.
- [ ] Compare pinned output buffers against a last-row/ArgMax graph output.
- [ ] Check cached manga decoder export feasibility and numerical equivalence.
- [ ] Compare explicit thread budgets and encoder allocator reuse for latency, p95, frames and memory.
- [ ] Assess progressive circle results while preserving final supplemental entries.
- [ ] Evaluate newer detectors/readers only after the existing-path costs are understood.

The initial research pass performed no app build, browser verification, OCR inference, device interaction, benchmark, commit or push. Only this document was initially added on `research/lookup-speed`.

## Approved implementation — 2026-10-13

Work continues uncommitted on `perf/lookup-speed`, from the same `155d7b6` baseline. The user approved the four initial changes and requested subagents, with the coordinating agent integrating and reviewing them. Device verification belongs exclusively to a separate subagent; the user subsequently requested Luna for that role.

| Change | Implemented behavior | Main files |
|---|---|---|
| Foreground OCR priority | Background manga reads yield before acquiring the engine, re-check priority after locking, and resume afterward. Meiki yields between tiles and resolves its session under the native lock. Touched supplemental lines promote background reads without cancelling concurrent foreground callers. | `OcrPriority.kt`, `MangaOcrEngine.kt`, `JapaneseOcr.kt`, `ScreenLineReader.kt`, `MeikiDetector.kt` |
| Exact-crop cache | The overlay owns a bounded cache of raw readings keyed by clamped crop and model file revision. Identical requests join; null, failed and cancelled reads remain retryable. New captures use a new cache. | `LookupPipeline.kt`, `LookupOverlay.kt` |
| Early circle results | The primary crop's repaired text/words appear before supplemental line OCR. Complete results append/deduplicate supplementary words and preserve fallback text. Select-again/dismiss cancels the pending lookup; Main publications check cancellation. | `LookupPipeline.kt`, `LookupOverlay.kt` |
| Earlier box detection | The accepted screenshot starts one detector Deferred before window/frame/Compose setup. The overlay awaits that same pass; hide/replacement/add-window failure cancels it. Freeze/media ordering stays intact. | `OverlayWindowController.kt`, `LookupOverlay.kt` |

No model, image preprocessing, crop padding, decoder output, thread budget, or dictionary ranking change was introduced. Existing native calls finish safely: this iteration does not preempt a running manga line or Meiki tile midway. Timing logs now expose queue, model load, preprocess, encoder, decoder, primary dictionary, supplementation and primary/complete gesture intervals.

Local validation: `:app:testDebugUnitTest` and `:app:assembleDebug` passed; 313 tests, zero failures/errors/skips. Focused additions cover cache sharing, invalidation, cancellation/failure retries, eviction, fallback text, background-read promotion, continued prefetch, shared foreground reads and equivalence of progressive/final result merging. Two independent reviews examined standards and the approved behavior; root fixed the initial cancellation, session-lifetime and fallback issues.

Device verification incident: the first verification agent force-stopped Gaten, then explicitly issued `adb reboot` when accessibility appeared disconnected. The reboot was outside the approved verification. Device actions were stopped, the cause was disclosed and an apology given. That agent reported no APK installation, app-data/settings/model changes or Anki writes. After the user reconnected and set up the phone, verification resumed with explicit prohibitions on reboot, force-stop, process killing, settings changes and data/model deletion. Luna took over at the user's request. These actions must not be repeated as a recovery workaround.

Luna's Nord comparison uses the Japanese Wikipedia page titled `日本`. Baseline scan readiness was approximately 432 ms (18 ML Kit lines), and baseline/optimized taps and circles returned the same visible `日本` text and dictionary result. The speed APK (`a5efd29ed1ba5b3c1e35e0a09778767a1a45882100bdd2088f16f16aaf140929`) installed with `adb install -r`; Gaten and Key Mapper remained bound/enabled. Optimized measurements on this two-character target:

| Stage | Observed sample |
|---|---|
| First uncached tap | 201 ms complete; crop 168 ms, primary dictionary 3 ms, supplement 1 ms |
| Repeated exact-box tap on the same screenshot | 50 ms complete; crop cache read 1 ms, primary dictionary 1 ms, supplement 0 ms |
| Circle primary result ready | 221 ms; crop 172 ms |
| Circle complete | 346 ms; supplement 2 ms |

These are stage/completion logs, not frame-render or animation-finish measurements. Baseline lacks comparable tap/circle completion logs, so no percentage improvement is established. The optimized detection sample was slower than baseline despite earlier submission; one sample cannot identify the reason or show a detector speed gain. Rendering a primary sheet before supplemental work completes was not captured directly. Stale-sheet device checks stopped when the user resumed interacting; that path has code review and local cancellation/equivalence coverage, but no completed device check. Luna's report/screenshots are in `/tmp/yomilens-speed-verification/`.

## Additional modal arrow fix

The user reported arrows covering the identified word-chip text in the result modal. `ResultSheet.kt` lays out arrows beside a weighted, clipped middle chip viewport, preventing chips from drawing beneath the controls. Following the user's spacing correction, an arrow's 40 dp container and adjacent padding exist only while that arrow is visible; a hidden arrow leaves no reserved gap after its fade finishes. Paging uses the actual middle viewport. No new UI tests were added for this small layout edit; the updated APK builds. The arrow layout has code review/build coverage, but no device visual check.

The subsequently requested portrait full-screen handle expansion was removed at the user's request; the original modal size and downward-dismiss gesture are restored. `OverlayWindowController` now gives the visible lookup window `FLAG_KEEP_SCREEN_ON`, keeping an already-on, unlocked display bright while the lookup is open and allowing normal timeout once it is removed. It does not alter system brightness/lock settings or change admission for fully off/locked screens. [Android window flag contract](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON). Combined updated APK SHA-256: `6a090903ed570220c34afd6337b2ac1b140efeb8a2aca2902869d0bc9b8ca9b6`.

The retained optimized scan logs contain `Early screen scan ready in 1092 ms` and a separate `Screen scan ready in 697 ms` (the UI's await interval). Their other stage counters do not reconcile to one end-to-end total. The device report was corrected to preserve the observed intervals without interpreting 1092 ms as trigger-to-ready. Relevant existing excerpts are saved in `/tmp/yomilens-speed-verification/timing-excerpts.log`.

Files changed: `ocr/JapaneseOcr.kt`, `ocr/MangaOcrEngine.kt`, `ocr/MeikiDetector.kt`, `ocr/ScreenLineReader.kt`, new `ocr/OcrPriority.kt`, `ui/lookup/LookupOverlay.kt`, `ui/lookup/LookupPipeline.kt`, `ui/lookup/ResultSheet.kt`, `ui/overlay/OverlayWindowController.kt`, `ocr/ScreenLineReaderTest.kt`, `ui/lookup/LookupPipelineTest.kt`, and this research/implementation document. Kotlin paths are under the app's existing main/test package directories.

## Standards

Final standards review: zero remaining findings. The reviewers accepted the cache's explicit snapshot/invalidation contract and native-work lifetime protections. The arrow fix passed the subsequent focused review.

## Spec

Final behavior review: zero remaining findings. All four approved changes are implemented; the arrow layout also matches the user's added requirement. Exact crop, model, acceptance/fallback and complete merged-word behavior are preserved by the implementation and tested where feasible.

Review summary: Standards 0 / Spec 0; no unresolved worst issue on either axis. No commits or pushes were performed.

## Animation refinement — 2026-10-13

The user reported janky modal presentation/dismissal and horizontal word scrolling. At the user's request, the GLM 5.3 Flash UI Work agent diagnosed the code before editing it. No frame-rate measurement or additional device testing was requested.

| Finding | Correction |
|---|---|
| The accessibility overlay's window flags did not enable hardware acceleration. | Request `FLAG_HARDWARE_ACCELERATED` when adding the window. |
| The animated border appended another contour to its remembered destination paths on every redraw. | Reset both segment paths before extracting the current frame's contours. |
| Showing/hiding arrows changed the chip viewport's width and padding during scrolling. | Following the user's latest preference, overlay arrows with an opaque sheet-colored background over a fixed-width chip viewport. |
| Clearing the result at selection reset removed the sheet before its exit animation. | Retain the displayed result and dock while it exits. Key the sheet by lookup session so progressive updates preserve selection and scrolling. |
| The drag spring's handle progress was read during sheet composition. | Read it inside the handle's layout instead. |
| The sheet moved a full-screen wrapper and window removal used a fixed delay. | Slide only the measured panel (260 ms in, 200 ms out), then fade the overlay. Remove the window after both transitions become idle. |

Android's [hardware acceleration guidance](https://developer.android.com/topic/performance/hardware-accel) documents window-level enabling and deferred layer transforms. [`PathMeasure.getSegment`](https://developer.android.com/reference/android/graphics/PathMeasure#getSegment(float,%20float,%20android.graphics.Path,%20boolean)) appends to its destination rather than replacing it. These are code-level findings; their contribution to the Nord's actual frame rate remains unmeasured.

Dismissal blocks new selections while it completes, including a close during the opening animation. A completed downward dismiss drag stays at its released offset while the slide continues, rather than springing back during the exit. The original modal size, OCR behavior and keep-screen-on flag are preserved. The coordinator reviewed the changes and corrected the transition-idle checks and the handle's width-clamp import.

Validation for this refinement is a debug build and a whitespace check; no additional unit tests, device gestures or FPS measurements were run. The user requested committing the accumulated changes and sending the arm64 debug APK to the paired Nord 6 through KDE Connect.

### Follow-up: entry height and tap ordering

| Request | Implemented behavior |
|---|---|
| Smooth height changes when selecting entries of different lengths. | The portrait panel animates measured content size over 250 ms while retaining its bottom anchor, height cap and insets. The first measurement is immediate; the existing content crossfade and landscape sizing remain. |
| Put words nearest the tap first. | Tap results rank the identified candidates by distance to their character boxes. Each word's own OCR context is aligned to the tapped line using the existing alignment code. The first chip and default entry are the nearest candidate; ties preserve the previous order. Circle ordering, recognized text, dictionary entries and candidate limits remain. |

Ranking runs off the main thread and adds no OCR or dictionary reads. Four existing tap-test expectations were updated for the requested ordering; no new tests were added. Validation is compilation of the debug APK and existing unit-test sources, plus a whitespace check; tests and device gestures were not run for this follow-up.

## Pill placement and local tap OCR

Implemented on `fix/tap-phrase-ocr` after the user approved the researched plan.

| Change | Result |
|---|---|
| Pill safe area | Reserve hidden-status-bar and camera-cutout insets, then add a 16 dp gap; include horizontal cutouts in landscape. |
| Local tap crop | Read a twelve-cell phrase around the nearest glyph in the chosen line, retaining whole shared glyphs and surrogate pairs. Restore original detector extent and add guarded margins before cutting the bitmap. |
| Full-line results | Replace only the frozen phrase span with accepted local OCR; keep prefix/suffix text and geometry, then scan and rank the corrected full line. |
| Candidate order | Rank before surface deduplication and the 24-word limit, preserving the nearest repeated occurrence; corrected-region words win distance ties. |
| Failure behavior | Missing/rejected OCR and unusable crop geometry keep the indexed line. Full-line highlighting, cancellation guards, circle lookup and exact-crop/model caching remain. |

The crop builder handles horizontal and vertical lines, line ends, unequal glyph widths, neighboring lines, and screenshot boundaries. Shared crop OCR is reused by tap and circle paths; native model/preprocessing settings are unchanged. Context and word offsets are derived from the corrected sentence rather than remapped onto an unchanged, possibly incorrect sentence.

Validation: debug build and 108 focused unit tests passed, with zero failures/errors/skips. The 25 new tests cover crop geometry, glyph/Unicode boundaries, local corrections and changed lengths, surrounding words, repeated occurrences, late candidates beyond the old limit, distance ties, and rejected-read fallback. Existing lookup/cache/alignment/hit-test/preprocessing tests also passed. `git diff --check` passed.

No phone gestures, installations, visual checks, or OCR accuracy comparisons were run. A representative failing screenshot was not provided, so the twelve-cell default and recognition improvement remain unverified on the user's failing scene. The user requested a commit and KDE Connect APK delivery to the Nord 6.

Files changed: `ocr/ScreenText.kt`, `ocr/ScreenTextScanner.kt`, `ui/lookup/LookupOverlay.kt`, `ui/lookup/LookupPipeline.kt`, `ui/lookup/ScreenLookup.kt`, and the new `TapPhraseCropTest.kt` / `TapLookupResultTest.kt`, plus these notes.

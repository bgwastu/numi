# Gemma for local SMS filtering

Research checked on 2026-10-11. EmbeddingGemma is installed and verified on Atlas. Desktop accuracy, Android unit tests, phone inference, failure handling, and Spam-folder integration checks have passed.

## Current selection

**Use EmbeddingGemma 2 Text 270M locally with a classification head trained on public SMS data.** The model is an ungated, Apache-licensed 165 MB Gemma export, and LiteRT-LM 0.18.0 supplies its Android embedding API. This keeps SMS processing on the phone. Gemma computes a semantic vector for each unmatched message; the trained head turns that vector into a spam probability. There is no bag-of-words classifier fallback. [Google's EmbeddingGemma guide](https://ai.google.dev/gemma/docs/embeddinggemma), [0.18.0 release source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/README.md).

| Property | Pinned value |
| --- | --- |
| Model owner/exporter | `litert-community/embeddinggemma-2-text-270m-litert-lm` |
| Revision | `9be6e8b90982095dc05c2bd162e4b954ee4dbac7` |
| File | `embeddinggemma-2-text-270m.litertlm` |
| Size | `164626432` bytes |
| SHA-256 | `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb` |
| Android runtime | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| Actual desktop embedding runtime | `com.google.ai.edge.litertlm:litertlm-jvm:0.18.0` |
| Initial limits | CPU, two threads, 128 input tokens, 768 output dimensions |
| License | Apache 2.0 for this model and LiteRT-LM |

Use the [pinned generic CPU/GPU artifact](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm) and verify its bytes. The inventory also contains chip-specific variants for newer processors; those are not evidence of support for Atlas's Kona processor. [Artifact inventory and hash](https://huggingface.co/api/models/litert-community/embeddinggemma-2-text-270m-litert-lm?blobs=true).

### Measured classifier quality

The pinned Indonesian and UCI English corpora contain 6,717 original rows, reduced to 6,231 normalized unique messages. Near-identical templates are grouped before the frozen split: 4,445 training, 891 validation, and 895 test messages. Only the training split fits the linear head. Validation selects the lowest threshold meeting 99% aggregate spam precision and at least 50% recall; the selected threshold is 0.74. The test set is not used to select the threshold. No private inbox contents are used for training. Dataset citations, archive checksums, split details, model hashes, and confusion matrices are in [the generated provenance](../android/app/src/main/assets/spam/provenance.json).

| Evaluation | Messages | Spam precision | Spam recall | False positives | Missed spam |
| --- | ---: | ---: | ---: | ---: | ---: |
| Validation, combined | 891 | 99.32% | 93.63% | 1 | 10 |
| Held-out test, combined | 895 | 98.72% | 94.48% | 2 | 9 |
| Held-out test, Indonesian | 164 | 97.47% | 92.77% | 2 | 6 |
| Held-out test, English | 731 | 100% | 96.25% | 0 | 3 |

These are raw Gemma results, before contacts, allowed senders, OTP protections, and explicit advertising/scam rules. The datasets are older public collections, not a guarantee for contemporary spam or this user's inbox. Spam remains recoverable; user restoration overrides automatic reclassification.

The encoder also matched all 18 synthetic probe labels that the stock chat model had failed. [Public probe results](evidence/gemma-embedding-public-probes.json). The JVM export of all 6,231 public messages finished in 1,022,676 ms with 335,839,232 bytes maximum RSS using the previously generated weight cache. An earlier cold desktop embedding probe used 408,010,752 bytes maximum RSS. These figures do not establish Atlas's memory use.

Numi's shared `GemmaTextInput.java` splits the body into chunks of at most 96 UTF-8 bytes, preferring word boundaries and preserving surrogate pairs. It caps input at 3,000 Unicode code points, adds the classification query prefix to each chunk, and L2-normalizes the sum of 768-dimensional chunk vectors. Both the JVM exporter and Android use this exact implementation with the 128-token signature. Invalid vectors and model failures remain UNKNOWN/PENDING.

The reproducible pipeline is `tools/train_gemma_spam.py --prepare`, `tools/GemmaEmbeddingExport.java`, then `tools/train_gemma_spam.py --embeddings`. The exporter verifies the pinned Gemma checksum. Python dependencies are pinned in `tools/spam-requirements.txt`. The 6,172-byte decision head has SHA-256 `bc163438b6f325163f97acf345993050ebada95e95fa380fa17774a2831e4c6f`; Android verifies it before inference. Thirty-one unit tests pass, including numerical parity against independent sklearn predictions on real held-out Gemma vectors.

### Atlas verification and installation

All **32 phone tests passed**: 24 storage/queue checks, four Gemma inference/failure checks, and four SMS provider/inbox checks. Actual Gemma inference classified a synthetic Indonesian promotion as SPAM and a personal message as SAFE in a separate process. A missing model stays enabled and returns UNKNOWN; killing the worker leaves the inbox process alive. The original 26 incoming SMS were evaluated on the phone without training, provider writes, or alerts. The three local provider fixtures were removed, and all fields of all 27 original SMS rows remained unchanged.

The first cold model check took **5,493 ms**. Cold and warm checks together took 6,391 ms with **463,180 KiB peak RSS**; a subsequent cache-backed suite took 3,910 ms with 461,976 KiB peak RSS. Production classification reached **387,584 KiB peak RSS**. An initially conservative 600 MiB startup guard prevented loading when instrumentation left 571 MiB available. Reducing the startup requirement to 512 MiB resolved that specific failing test while retaining the 512 MiB worker ceiling, 256 MiB system-memory floor, and Android low-memory check. Temporary probe logs were removed. ADB survived and the phone did not reboot.

The signed update retains the original package and certificate. Both default SMS and dialer roles and the Telegram configuration remain intact. All messages completed screening, historical reclassification generated **zero Telegram alerts**, and Telegram reconnected. The Spam screen reports that Gemma is ready. Gemma classification uses no remote inference service; the configured Telegram connector continues its normal forwarding of eligible messages. A rollback APK and private database/settings snapshots are retained outside the repository. [Public verification metadata](evidence/gemma-atlas-verification.json).

The local signed release build and lint pass with zero lint errors; the existing app reports 67 warnings and one hint. The GitHub workflow tests, lints, signs, and publishes an APK for each main-branch commit that changes `android/**` or `tools/**`. Commits outside those folders retain the current version.

### Input limits, preprocessing and vector output

The generic export advertises **128, 256, 512, 1024, 2048 and 8192-token text signatures**. Output sizes are **128, 256, 512 and 768 dimensions**. These input capacities include the task prompt and automatically inserted special tokens, so they are not character limits. [Export specifications](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm#model-specifications), [special-token insertion and tokenization](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/core/embedding_engine_impl.cc).

The original Google checkpoint recommends `task: classification | query: {content}`; its SentenceTransformers configuration agrees. The LiteRT guide instead shows `task: classification | text:`. This is a first-party documentation discrepancy. Prefer the checkpoint's `query:` prompt, record it in the trained head's metadata, and use exactly the same preprocessing in desktop training and Android. Both wrappers pass plain strings without adding a task prompt. Trim boundary whitespace; preserve the body otherwise unless a separately evaluated, versioned preprocessing rule requires a change. [Original model prompt](https://huggingface.co/google/embeddinggemma-2#1-task-instruction-prefixes), [checkpoint prompt configuration](https://huggingface.co/google/embeddinggemma-2/blob/main/config_sentence_transformers.json), [LiteRT prompt table](https://developers.google.com/edge/litert-lm/embedding_models#task_instruction_prefixes), [Python wrapper](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/python/litert_lm/embedding_engine.py).

Native defaults are **L2 normalization enabled, special tokens enabled, and overflow ERROR**. `maxInputLength = 128` restricts loaded signatures to capacities at most 128; unset bounds can load every signature unless model metadata supplies limits. Kotlin has no public overflow-strategy field. Python exposes ERROR, TRUNCATE and CHUNK_AND_AVERAGE, so select ERROR for Android parity. A too-long SMS must remain pending/unknown or use an explicitly tested application strategy; do not label an overflow failure SAFE. [Native options](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/engine/embedding_engine.h), [signature selection](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/executor/model_signature_utils.cc), [engine defaults and selection](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/engine/embedding_engine_settings.cc), [Kotlin options](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/EmbeddingConfig.kt).

Keep **768 dimensions initially**. Native code computes the vector, truncates it when requested, then L2-normalizes the result. Choosing 128 output dimensions reduces retained output storage from 3,072 to 512 bytes; this does not reduce the model weights or selected input signatures. Check for finite components and a nonzero vector before applying the head. The model card warns against FP16 inference; use the generic CPU route and verify the actual exported activation type rather than forcing a GPU precision choice. [Output order](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/core/embedding_engine_impl.cc), [precision and truncation guidance](https://huggingface.co/google/embeddinggemma-2#4-numerical-precision).

### Android API, pinned to 0.18.0

The Maven AAR declares Apache 2.0. Kotlin offers `EmbeddingEngine(config)`, synchronous `initialize()`, `computeEmbedding(contents, options)`, `computeEmbeddingBatch(contentsBatch, options)`, async variants, and `close()`. The result contains a `FloatArray`. Calls are serialized with an internal lock; initialization and inference belong in the separate worker. This class exposes no inference-cancellation method; a coroutine timeout alone does not establish that native work stopped. [Maven artifact](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.pom), [Kotlin engine source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt).

```kotlin
val engine = EmbeddingEngine(
    EmbeddingEngineConfig(
        modelPath = verifiedModel.path,
        backend = Backend.CPU(threadCount = 2),
        cacheDir = existingPrivateCache.path,
        maxInputLength = 128,
    )
)
engine.initialize() // Worker thread/process; the cache directory already exists.
try {
    val embedding = engine.computeEmbedding(
        listOf(InputData.Text("task: classification | query: " + body.trim())),
        EmbeddingOptions(normalize = true, insertSpecialTokens = true, outputSize = 768),
    ).embedding
    // Validate dimension/finite values, then apply the pinned learned head.
} finally {
    engine.close()
}
```

`EmbeddingEngineConfig` has nullable `modelPath` or Android `modelFd` (exactly one), CPU backend/thread selection, nullable audio/vision backends, `cacheDir`, `maxInputLength` and `activationDataType`. It does not expose the Python minimum-length bound. The initial configuration above uses the export's activation default. [Configuration signatures](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/EmbeddingConfig.kt).

### Desktop corpus embedding API

Python **`litert-lm-api==0.18.0`** requires Python 3.10+ and publishes both `macosx_12_0_arm64` and `manylinux_2_27_aarch64` wheels. Its constructor loads the engine immediately; the context manager closes native resources. Batch calls reuse one model but native inference currently loops sequentially, so use small batches to bound Python input/result memory. One overlong item can fail its batch. This is an API for public corpus preparation, not a need to send private SMS to a server. [Wheel metadata](https://pypi.org/pypi/litert-lm-api/0.18.0/json), [Python engine API](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/python/litert_lm/embedding_engine.py), [native batch implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/core/embedding_engine_impl.cc).

```python
from litert_lm import Backend, EmbeddingEngine, EmbeddingOptions, InputOverflowStrategy

with EmbeddingEngine(
    verified_model_path,
    backend=Backend.CPU(thread_count=2),
    cache_dir=existing_private_cache,
    max_input_length=128,
) as engine:
    responses = engine.compute_embedding_batch(
        ["task: classification | query: " + body.strip() for body in public_batch],
        options=EmbeddingOptions(
            normalize=True,
            insert_special_tokens=True,
            output_size=768,
            input_overflow_strategy=InputOverflowStrategy.ERROR,
        ),
    )
    vectors = [response.embedding for response in responses]
```

Use the same trimming rules, overflow handling, model hash, prefix, dimensions and normalization in both environments; `.strip()` and `.trim()` should be checked against shared Unicode fixtures if used directly. Store those choices with the trained head. Split by near-duplicate group before fitting/evaluating the head and its probability threshold. Public multilingual embedding benchmarks do not measure Indonesian SMS spam precision or false positives on OTPs and personal conversations.

### Memory comparison and release attribution

The official export card reports **334 MB Android CPU memory and 27.1 ms text latency** on S26 Ultra for the **128-token signature**, with memory measured on a second cache-backed load. No official 256-token memory figure or Atlas cold-load bound was found. `maxInputLength = 256` allows both 128 and 256 signatures and longer text; the resulting allocation and latency must be measured. Input length and output dimension are separate controls. [Export benchmark and methodology](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm#embeddinggemma-2-performance-on-litert-lm), [native selection logic](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/core/embedding_engine_impl.cc).

Package the Apache 2.0 license with the distributed model/runtime, retain applicable copyright and attribution notices, and carry forward any supplied NOTICE material. Add an entry for EmbeddingGemma 2 by Google DeepMind and LiteRT-LM by its authors in Numi's existing licenses screen or bundled attribution file, recording pinned versions and model provenance. The generic model repository lists no separate NOTICE file; that does not remove notice obligations for runtime dependencies. The Gemma 3 terms below concern rejected/alternative Gemma 3 artifacts, not this Apache-licensed selection. [EmbeddingGemma license declaration](https://ai.google.dev/gemma/docs/embeddinggemma), [LiteRT-LM license](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/LICENSE), [Apache redistribution requirements](https://ai.google.dev/gemma/apache_2).

## Rejected stock Gemma 3 270M IT

**Do not deploy stock Gemma 3 270M IT for this classifier.** It was the smallest verified LiteRT-LM candidate, but the tested artifact returned SAFE for every example in both an 18-case zero-shot run and an 18-case few-shot run, missing all eight spam examples in each run. Reversing the regex label order and removing constrained decoding did not change three additional probes. These observations establish failure in the tested setup; they do not establish whether the cause is the model, export, runtime, or prompt. [Local probe metadata](evidence/gemma-270m-desktop.json).

The original 270M artifact is 304,005,120 bytes (290 MiB). Google positions this size for specialized tasks, including text classification after tuning; stock instruction following alone does not establish Indonesian SMS accuracy. [Google's 270M announcement](https://developers.googleblog.com/en/introducing-gemma-3-270m/), [official LiteRT model card](https://huggingface.co/litert-community/gemma-3-270m-it), [official artifact metadata](https://huggingface.co/api/models/litert-community/gemma-3-270m-it?blobs=true).

| Property | Pinned value |
| --- | --- |
| Model owner/exporter | `litert-community/gemma-3-270m-it` |
| Upstream revision | `9d2093270fb5aa49a986b49b5779d763dde7b630` |
| File | `gemma3-270m-it-q8.litertlm` |
| Size | `304005120` bytes |
| SHA-256 | `757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63` |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.17.1` |
| Backend | `Backend.CPU(threadCount = 2)` initially |
| License | Gemma Terms of Use for the model; Apache 2.0 for LiteRT-LM |

The pinned upstream download is [this generic Q8 file](https://huggingface.co/litert-community/gemma-3-270m-it/resolve/9d2093270fb5aa49a986b49b5779d763dde7b630/gemma3-270m-it-q8.litertlm). Unauthenticated HEAD returned **401 / GatedRepo** during this investigation. The repository requires agreeing to the conditions and authenticated access. Do not put a user's Hugging Face credential into an APK. [Upstream access conditions](https://huggingface.co/litert-community/gemma-3-270m-it).

A public transport mirror advertises the **same file size and SHA-256**: [72fstudio mirror metadata](https://huggingface.co/api/models/72fstudio/gemma-3-270m-it?blobs=true). Its [pinned download](https://huggingface.co/72fstudio/gemma-3-270m-it/resolve/daa9f3663f4752fd1c5239f4defdaf748c1351ba/gemma3-270m-it-q8.litertlm) returned HEAD 200 and Content-Length 304005120. Revision: `daa9f3663f4752fd1c5239f4defdaf748c1351ba`. The implementation agent downloaded it and confirmed the upstream SHA-256. Its README declares `license: gemma`; separate LICENSE and Notice files were absent. Retain Google's terms and notices in Numi's own distribution. [Mirror README](https://huggingface.co/72fstudio/gemma-3-270m-it/blob/daa9f3663f4752fd1c5239f4defdaf748c1351ba/README.md), [local integrity record](evidence/gemma-270m-desktop.json).

## Generative API inspected for the rejected candidate

The rejected experiment used the existing LiteRT-LM 0.17.1 integration seam. This release is available as an Android AAR from Google Maven; it is **pinned**, not the latest release. Maven metadata currently lists 0.18.0 as latest. The versioned generative API explicitly exposes CPU thread count, a maximum combined input/output token budget, an output token limit, cancellation, and closeable engine/conversation resources. Initialize outside the UI thread. [0.17.1 Maven artifact](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.17.1/litertlm-android-0.17.1.pom), [Maven versions](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml), [versioned getting started](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/docs/api/kotlin/getting_started.md), [configuration source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt), [conversation source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt).

The Kotlin API supports constrained decoding using Regex or JSON Schema. For a binary classifier, a short exact label avoids explanatory prose:

```kotlin
val engine = Engine(
    EngineConfig(
        modelPath = verifiedModel.path,
        backend = Backend.CPU(threadCount = 2),
        maxNumTokens = 1024,
        cacheDir = privateCache.path,
    )
)
engine.initialize()
engine.createConversation(
    ConversationConfig(
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        maxOutputToken = 8,
        enableResponseFormat = true,
    )
).use { conversation ->
    val result = conversation.sendMessage(
        prompt,
        responseFormat = ResponseFormat.regex("SAFE|SPAM"),
    )
    // Parse the exact response text; exceptions or invalid output remain UNKNOWN.
}
```

Validate that the Android AAR enforces the constraint on the selected artifact. A grammar controls the output shape; it does not establish classification accuracy. Fresh conversations should prevent one sender's SMS from becoming another message's context. [ResponseFormat source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt), [sendMessage constraint plumbing](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt).

Use CPU for this artifact. The model card describes 270M GPU acceleration as unfinished. A Google LiteRT-LM maintainer confirmed the reported GPU FP16 empty-output problem on 0.17.1; CPU generation was unaffected in those reproductions. The issue remained under investigation on 2026-10-06. This is evidence for selecting CPU, not a latency guarantee on this phone. [Model card](https://huggingface.co/litert-community/gemma-3-270m-it), [runtime issue 3280](https://github.com/google-ai-edge/LiteRT-LM/issues/3280).

## Resource and quality limits

**Artifact size is not peak RAM.** Google's related FunctionGemma 270M benchmark reports roughly 549–551 MB peak RSS for a 288 MB dynamic-INT8 model with 1,024 context, 512 prefill tokens, 32 decode tokens, and four CPU threads on a Samsung S25 Ultra. It reports about 0.3 seconds to first token after model loading. Those figures concern a sibling model, different hardware and workflow; they neither bound Numi's peak RSS nor include a guaranteed cold start. [Google's benchmark](https://ai.google.dev/gemma/docs/functiongemma/model_card#benchmark-results).

The stock 270M macOS/JVM probe initialized in 2,448 ms and took approximately 0.8–0.9 seconds per message. Its reported process maximum RSS was **1,445,494,784 bytes** with an initial cache-directory error. A repeat with the directory present initialized in 1,427 ms; its command maximum RSS was **1,630,289,920 bytes**, although that command included a javac child and is not a clean model-only measurement. These are desktop measurements, not proof of Android RAM consumption. No 270M inference was attempted on the phone. [Local probe metadata](evidence/gemma-270m-desktop.json).

Atlas measurements for the selected encoder are above. A separate worker process, one inference at a time, a short context, a watchdog, and releasing the worker after a batch are Numi design choices to limit impact. Startup requires **512 MiB available memory** and no Android low-memory flag; the watchdog stops the worker above **512 MiB RSS** or below **256 MiB system available memory**. Native work has a 30-second deadline and the worker is released after four seconds idle. These are measured application guardrails, not vendor-certified limits. The earlier multi-gigabyte model is not a fallback.

XNNPACK packs extra copies of weights for optimized access. Its file-backed cache can deduplicate shared tensors, signatures and interpreter instances, and warm starts can avoid paging in the original weights. Ensure the cache directory exists before loading and compare first cache construction with a warm load. This optimization cannot fix the observed classifier output. LiteRT-LM 0.17.1 connects a cache path to XNNPACK; its C++ CPU configuration also exposes prefill chunking for dynamic exports, which the Kotlin 0.17.1 configuration does not expose. [XNNPACK cache documentation](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/delegates/xnnpack/README.md), [LiteRT-LM cache connection](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/runtime/executor/litert_compiled_model_executor_utils.cc), [CPU chunk configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/runtime/executor/llm_executor_settings.h).

Evaluate Indonesian promotions/scams, English spam, personal conversations, OTPs, service/transaction notices, numeric or URL-only bodies, and instructions embedded in an SMS. Model cards do not provide an Indonesian SMS spam benchmark. Google recommends specialization for 270M; multilingual family claims alone do not show adequate SMS quality at this size. If prompt-only quality is insufficient, fine-tune Gemma on licensed public SMS datasets, hold out evaluation data by near-duplicate group, and export the tuned checkpoint. Do not train on private inbox contents without explicit authorization. [270M announcement](https://developers.googleblog.com/en/introducing-gemma-3-270m/), [Google tuning guidance](https://ai.google.dev/gemma/docs/tune), [LiteRT custom checkpoint conversion](https://developers.google.com/edge/litert/conversion/pytorch/genai).

The detector should preserve UNKNOWN distinctly from SAFE when the model is missing, killed, times out, or returns invalid output. Keep an incoming message visible locally while awaiting screening; a failed classifier must not erase it or silently claim a successful verdict. Spam recovery and suppression of spam Telegram alerts belong to the SMS storage/effects pipeline, independently of the model choice.

## Larger alternatives inspected

These are generic mobile artifacts listed by their owners on the research date; sizes are download sizes, not RAM requirements.

| Model/export | Generic artifact bytes | Assessment |
| --- | ---: | --- |
| [Gemma 3 1B IT INT4](https://huggingface.co/api/models/litert-community/Gemma3-1B-IT?blobs=true) | 584,417,280 | Above a 500 MB download target; larger RAM risk. Hash `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`. |
| [Gemma 3n E2B INT4](https://huggingface.co/api/models/google/gemma-3n-E2B-it-litert-lm?blobs=true) | 3,655,827,456 | Not a smaller replacement. |
| [Gemma 3n E4B INT4](https://huggingface.co/api/models/google/gemma-3n-E4B-it-litert-lm?blobs=true) | 4,919,541,760 | Not suitable for this memory budget. |
| [Gemma 4 E2B generic](https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm?blobs=true) | 2,588,147,712 | Earlier phone failures make another direct load inappropriate. GPU-only export is still about 2.0 GB. |
| [Gemma 4 E4B generic](https://huggingface.co/api/models/litert-community/gemma-4-E4B-it-litert-lm?blobs=true) | 3,659,530,240 | Larger still. |

Gemma 3n's effective-parameter optimizations do not make it a sub-500 MB artifact. Google describes E2B as just under 2 billion effective parameters even after PLE caching and parameter skipping. Chip-specific exports for newer Qualcomm/MediaTek targets should not be chosen merely because Atlas is a Qualcomm phone; the verified generic CPU artifact avoids that compatibility assumption. [Gemma 3n architecture](https://ai.google.dev/gemma/docs/gemma-3n), [270M export inventory](https://huggingface.co/api/models/litert-community/gemma-3-270m-it?blobs=true).

## Distribution

Gemma 3 models retain the Gemma Terms of Use regardless of download source. Redistribution requires the terms, the stipulated Notice file, applicable use restrictions, and notices for modified model files. Package these alongside any bundled or separately distributed weights; do not assume the runtime's Apache license covers the model. [Gemma Terms, section 3](https://ai.google.dev/gemma/terms#section-3-distribution-and-restrictions).

Keep only model bytes and public provenance in a model download. The selected phone-local inference design never uploads SMS bodies.

## Task-tuned small Gemma route

Google's export tooling accepts a local safetensors checkpoint and supports Gemma 3. The versioned LiteRT Torch v0.9.4 source exposes prefill lengths, cache length and custom quantization recipes, and its Gemma 3 conversion example supports a 270M model with dynamic INT4. Choose a small prefill signature and context for an SMS task, then measure actual memory. INT4 may shrink weights, but cannot by itself teach the stock model a missing classification behavior. [Custom checkpoint export](https://developers.google.com/edge/litert/conversion/pytorch/genai), [v0.9.4 export API](https://github.com/google-ai-edge/litert-torch/blob/v0.9.4/litert_torch/generative/export_hf/export.py), [270M converter](https://github.com/google-ai-edge/litert-torch/blob/v0.9.4/litert_torch/generative/examples/gemma3/convert_gemma3_to_tflite.py), [INT4 example](https://github.com/google-ai-edge/litert-torch/blob/v0.9.4/litert_torch/generative/examples/gemma3/README.md).

An ungated checkpoint mirror advertises the same `model.safetensors` bytes as Google's 270M IT checkpoint: **536,223,056 bytes**, SHA-256 `700b710a9a99c295ed546647aa81cacf9f81f4c573ea2be613a0e2517a44afab`. Pin `unsloth/gemma-3-270m-it` revision `23cf460f6bb16954176b3ddcc8d4f250501458a9` and verify actual bytes before tuning. This is an access path for training/export, not a trained spam model. No fine-tuning has been performed. [Google checkpoint metadata](https://huggingface.co/api/models/google/gemma-3-270m-it?blobs=true), [mirror metadata](https://huggingface.co/api/models/unsloth/gemma-3-270m-it?blobs=true).

## Server fallback investigated, not selected

LiteRT-LM supports Linux ARM64 CPU through Python. The pinned `litert-lm-api==0.17.1` package publishes a `manylinux_2_27_aarch64` wheel and requires Python 3.10 or later. No GPU or Ollama is needed for this runtime route. An older Pi 4 ARMv8.0 CPU issue reports illegal instructions with a prebuilt wheel; check the target CPU and run a bounded public-data smoke test rather than inferring compatibility from `aarch64` alone. [Python platform guide](https://developers.google.com/edge/litert-lm/python), [versioned wheel metadata](https://pypi.org/pypi/litert-lm-api/0.17.1/json), [ARM compatibility issue](https://github.com/google-ai-edge/LiteRT-LM/issues/1847).

The generic [Gemma 4 E2B artifact](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm) is **2,588,147,712 bytes**, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`; its repository is ungated and Gemma 4 uses **Apache 2.0**. Google's overview reports Linux ARM CPU performance of 260 prefill tokens/second, 35 decode tokens/second, 4 seconds to first token and 1,628 MB peak CPU memory on its benchmark host. A Raspberry Pi 5 result is 8 decode tokens/second. These numbers make CPU hosting plausible but are not Artemis measurements or bounds. [Artifact metadata](https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm?blobs=true), [Gemma 4 license/model card](https://ai.google.dev/gemma/docs/core/model_card_4), [official benchmark](https://developers.google.com/edge/litert-lm/overview#featured-model-gemma-4-e2b).

A service can reuse one CPU engine with no audio/vision backends, create a fresh conversation per SMS, explicitly disable thinking, and return only a regex-constrained verdict plus the pinned model version. Limit context/output and serialize inference. The existing Python API exposes these primitives; it does **not** provide Numi's authenticated HTTP service itself. [Engine API](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/python/litert_lm/engine.py), [conversation/configuration API](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/python/litert_lm/interfaces.py), [thinking behavior](https://ai.google.dev/gemma/docs/core/model_card_4#thinking-mode-configuration).

Numi's remote design should keep SMS storage and the recoverable Spam folder on the phone. Send only the message being classified to the user's chosen host through the private mesh, authenticate requests, constrain the endpoint, and avoid SMS bodies in request/error logs. Keep UNKNOWN/PENDING visible when the host is unreachable and retry with backoff; do not silently label a server failure SAFE. Rechecking history must remain quiet. Hosting shifts the weight/cache memory to the server, while the SMS app and Telegram connector continue on the phone. These are proposed application requirements, not vendor guarantees; no private SMS has been sent to a server by the research agent.

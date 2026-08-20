# gemma-pipeline-poc

Standalone Android POC for **Fases 4 + 5 + 6** of the Travel2Chicago
real-time ES→EN translator. Integrates Silero VAD + chunker (Fase 3),
Gemma 4 E4B AST (Fase 0), and Kokoro-82M TTS (Fase 5) into one app —
full speech-to-speech pipeline in one process — plus optional token +
per-sentence streaming (Fase 6) that cuts audible latency from ~14 s to
a target ≤ 3 s.

**Status:**
- ✅ Fase 4 + Fase 5 COMPLETADAS on Xiaomi 15T Pro (junio 2026).
- ✅ Fase 6 (streaming) VALIDADA on device (julio 2026) — 3-round
  protocol passed with 0 errors. **Both streaming flags default ON**;
  measured latencies: baseline ~13.5 s → Stage C only ~3.7 s → all
  stages ON ~3.4 s (first-audio, end-to-end). The UI toggles under
  "3½. FASE 6 STREAMING" remain wired for runtime disabling if a
  regression appears. Latency counters (`First token` + `First
  audio`) live in the same panel.
- ✅ **Post-Fase-6 changes validated on device** (August 2026):
  - **Audio-after-text order** — no regression; `English:` marker
    detected in 100 % of chunks.
  - **Official AST prompt with router extraction** — extraction
    fired on every chunk, `englishMarkerMissing` stayed at 0.
    Follow-up: prompt tightened to skip Spanish transcription
    (was wasting ~1–2 s of decode per chunk).
  - **Android system TTS Fast mode** — first-audio ~3.4 s vs
    Kokoro 3.2–6 s; snappier voice. Fast mode is the travel
    default; Kokoro stays as the quality option.
  - **Meta-text filter refined** — now only matches inside the
    first 60 chars of the reply to eliminate false positives on
    legitimate translations that mention "translation" / "audio"
    mid-sentence.
- ❌ **Investigated + dropped** the same round: MTP / speculative
  decoding (short outputs don't benefit) and the official
  `gemma-4-E4B-it.litertlm` export (worse AST than our Fase 0
  model). Both revert commits merged; toggles preserved.
- ❌ **Fase 7 upgrade attempt (0.12 → 0.15) REVERTED** (August 2026).
  Device testing showed GPU audio never activated with our current
  model, first-token latency regressed from ~1170 ms to ~2000 ms,
  and the app crashed twice under memory pressure (0.15 AAR ~4 MB
  heavier). Back on `0.12.0`. Root cause turned out to be upstream:
  `litert-torch export_hf` doesn't emit the audio-GPU subgraphs
  (litert-torch issue #1039); even Google's own official model
  ships `backend_constraint: cpu` in the audio section. **CPU audio
  at ~800 ms prefill is the current state of the art on-device for
  Gemma 4** — no SDK bump, model swap, or flag flip will move it
  until the upstream lands. `AstConfig.audioBackendGpu` flag +
  attempt-chain scaffolding preserved (default `false`) so
  re-attempting is a one-flag + one-version-bump change when Google
  ships the fix. See [`PROGRESS.md`](PROGRESS.md) for the full
  write-up.
- ✅ **Full-duplex mode validated on device** (August 2026). New flag
  `AstConfig.fullDuplexMode` (default OFF, toggle in UI). When on,
  the mic stays open while TTS speaks — chunker keeps collecting,
  translations stream out continuously (OpenAI-style barge-in).
  UI badge shows "🎙 VAD live (full-duplex, TTS)" during playback
  to confirm the mute is bypassed. Needs a directional mic or
  headphones to avoid speaker feedback.
- ✅ **POC closure — validation final** (commit `c623e7a`, August 2026).
  14/14 translations, 0 errors, 0 drops over ~10 minutes of
  continuous operation. All accumulated features working together:
  full-duplex, male voice `am_michael` default, Android TTS fast
  mode, streaming AST+TTS, retuned chunker, translation-only
  prompt with `English:` marker extraction, meta-text filter with
  60-char prefix window. **Latencies: first token ~1200 ms, first
  audio ~3.4 s — 4× improvement end-to-end vs pre-Fase-6 baseline
  (~14 s → ~3.4 s)**. Known issues (Mexican slang, "It says"-style
  narrative wrapping, Kokoro on long sentences) documented in
  PROGRESS.md and non-blocking for field use. **POC is stable and
  ready for field testing with production hardware**
  (unidirectional mic + wired USB DAC speaker eliminates both the
  full-duplex feedback risk and A2DP's ~300 ms playback tail).
- 🧪 **AEC investigation — three-way experiment** (August 20, 2026).
  Consolidating three parallel AEC experiments this week:
  1. **HAL AEC** (`InputPreset::VoiceCommunication` +
     `AcousticEchoCanceler`, commit `d9a6f18`): validated **works**
     with the Xiaomi's built-in mic + speaker (no full-duplex
     feedback loop). **Does NOT work with external hardware**
     (Saramonic USB + JBL Go 4 BT) — the HAL has no calibration
     for external acoustic paths, and `AcousticEchoCanceler.create()`
     over the Oboe `SessionId` returns `null`. HAL AEC stays default
     ON (useful for internal path, inocuous for external).
  2. **WebRTC AECM software** (commit `254ebd4`): POC vendored
     from `theeasiestway/android-webrtc-aecm` (.aar in `app/libs/`),
     `AecProcessor` with 24→16 kHz resample + 10 ms frame alignment
     + hardcoded 200 ms BT A2DP delay, hooks in `TtsAudioPlayer`
     (far-end) + `VadChunkingPipeline` (near-end). Flag off by
     default. **Device test pending** — expected 30–50 %
     cancellation over BT (AECM tolerates only ~10 ms of delay
     jitter; A2DP jitters ±50 ms).
  3. **Hardware mic — Cubilux MLC-10 passive cardioid**: rejected —
     off-axis rejection ~6–10 dB, insufficient to eliminate
     feedback with the speaker close to the mic. Evaluating
     **Cubilux ENC (~$48)** with active DSP cancellation as a
     hardware-level solution that would bypass the SW-AEC delay
     estimation problem entirely.
  Also this cycle: `AstConfig.fullDuplexMode` default flipped to
  ON (`d9a6f18`) after the HAL AEC + internal-hardware validation.
  **Pending**: (a) device test AECM over BT, (b) resolve external
  mic + USB hub conflict, (c) evaluate Cubilux ENC. See
  [`PROGRESS.md`](PROGRESS.md) "AEC investigación — resultados y
  estado actual" for the full write-up.
- ✅ **Post-closure streaming bug fix + stable-state validation**
  (commit `1d4f2e5`, August 2026). Field testing surfaced a phrase
  repetition bug — the app repeated the initial phrase and
  interleaved old + new content even with the speaker isolated from
  the mic. Root cause: LiteRT-LM 0.12.0's
  `Conversation.sendMessageAsync(...): Flow<Message>` emits
  **cumulative** messages (each `Message.toString()` = full text so
  far), not deltas. The prior implementation appended every callback,
  producing `"Hello" + "Hello world" + "Hello world."` in the buffer
  → `"HelloHello worldHello world."` on the bus. Verified against
  the decompiled AAR (`Conversation$JniMessageCallbackImpl`). Fix:
  prefix-diff via a new allocation-free `startsWithBuffer` helper;
  defensive fallback for a hypothetical future delta protocol.
  Bundled in the same commit: `am_michael → am_adam` default voice
  (snappier phoneme decay on the JBL, lower per-sentence Kokoro
  latency) and three narrative-wrapping meta-text patterns
  (`"it says"`, `"he says"`, `"she says"`). **Post-fix validation
  on device: 12+ translations, 0 repetitions, 0 errors. Logs confirm
  the cumulative protocol** (`drained 36 callback(s) → 28 chars`).
  Stable-state metrics: first token ~1100–1200 ms consistent, first
  audio ~3.4 s, full-duplex working without repeats, 10+ min
  continuous operation without crash.

See [`PROGRESS.md`](PROGRESS.md) for the full validation results, the
six fixes applied during Fase 5 device testing, the Fase 6 investigation
findings (LiteRT-LM has NO public audio-input streaming API — the win
comes from output-token streaming + per-sentence TTS), and the four
post-Fase-6 investigations with their feature flags.

## What it does

1. Oboe captures Spanish audio from the Saramonic USB mic (Fase 2).
2. Silero VAD v5 (with the mandatory 64-sample context prefix, Fase 3) detects
   speech and a 1.5–6 s chunker emits utterances (retuned in Fase 6
   Stage C; was 3–6 s in Fase 5).
3. Each chunk is wrapped in a WAV (RIFF/WAVE PCM 16-bit) and sent to Gemma 4
   E4B via LiteRT-LM (`Backend.GPU()` + `audioBackend=Backend.CPU()`). With
   Fase 6 Stage A ON, Gemma's tokens stream out and the router emits one
   `TranslationReady` per sentence; with it OFF, one event per whole reply.
4. The English translation appears in the UI within ~2 s (streaming) or
   ~3 s (one-shot).
5. Each translation (or each sentence, with Fase 6 Stage B ON) is fed to
   Kokoro TTS (ONNX Runtime CPU, int8 model) to synthesize PCM at 24 kHz,
   played back through a dedicated `AudioTrack`. With streaming ON,
   sentence 1 starts playing while sentences 2..N are still synthesising.

## Setup before first run

1. The Gemma model must live at `/sdcard/Download/gemma_model/`:
   ```
   gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm
   .litertlm.audio_adapter.xnnpack_cache
   .litertlm.audio_encoder.xnnpack_cache
   .litertlm.static_audio_encoder.xnnpack_cache
   .litertlm_16442536968298684338.bin
   .litertlm_1776297412_3609411584_mldrift_program_cache.bin
   .litertlm_5818495038867434237.bin
   ```
   Reuse the directory from `gemma-ast-poc` (Fase 0). The companion files are
   required for the GPU backend. We briefly tried the official
   `gemma-4-E4B-it.litertlm` export (which embeds the MTP drafter) but its
   AST quality was noticeably worse on device — see [`PROGRESS.md`](PROGRESS.md).

2. The Kokoro TTS model must live at `/sdcard/Download/kokoro_model/`:
   ```
   kokoro-v1.0.int8.onnx    (~88 MB, int8 quantised)
   voices-v1.0.bin          (~27 MB, NPZ archive of ~26 voice embeddings)
   ```
   Both files are from `huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX`.
   The int8 build is the right one for mobile — the fp32 (~310 MB) does not
   fit comfortably in the RAM budget alongside Gemma.

   `voices-v1.0.bin` is **not** a raw float blob — it's an NPZ archive (a
   ZIP of `.npy` files, one per voice). `VoicesNpz` parses every entry on
   load; each voice is a `[511, 1, 256]` float32 tensor and the engine
   slices `voice[tokenCount]` per sentence (matching `voice = voice[len(tokens)]`
   in the Python reference). Voice keys (`af_heart`, `af_bella`,
   `am_michael`, `bf_emma`, `bm_george`, …) come from the entry filenames.

3. `silero_vad.onnx`, `kokoro_config.json`, and `cmudict_ipa.dict` are
   bundled in `app/src/main/assets/`. The config holds the canonical 178-token
   Kokoro vocab (sourced verbatim from `thewh1teagle/kokoro-onnx`); the
   dictionary is the ~125 000-entry CMU→IPA file from
   `puff-dayo/Kokoro-82M-Android` (GPL-3, bundled as data only). OOV words
   fall through to a schwa fallback with a WARN log — extend the dict if
   common terms in your domain produce robotic speech.

4. Grant `MANAGE_EXTERNAL_STORAGE` when prompted (needed to read the Gemma
   and Kokoro models from `/sdcard/Download/`).

## Architecture (additions over vad-chunking-poc)

```
… (capture + VAD + chunker as in Fase 3) …
         │
         ▼
  AudioEvent.ChunkReady on bus
         │
         ▼
  AstChunkRouter
   - Channel(capacity=4, DROP_OLDEST)  ← bounds Gemma backlog
   - Consumer (Dispatchers.IO, single)
         │
         ▼
  WavBuilder.build(samples) → wavBytes
         │
         ▼
  GemmaAstEngine.translate(wav, prompt)
   - EngineConfig(GPU + audioBackend=CPU, maxNumTokens=1024)
   - Fresh Conversation per call (close prior, then createConversation)
   - Conversation.sendMessage(Contents.of(AudioBytes, Text))
         │
         ▼
  bus.emit(AudioEvent.TranslationReady)
         │
         ├──▶ UI sección "TRANSLATIONS"
         │
         ▼
  TtsRouter
   - Channel(capacity=4, DROP_OLDEST)  ← bounds Kokoro backlog
   - Consumer (Dispatchers.IO, single)
         │
         ▼
  KokoroOnnxEngine.synthesize(text, voice="af_heart")
   - Phonemizer (CMU dict, ~125k entries, EN-US, schwa OOV fallback)
   - Tokenizer (per-char IPA → ID; engine wraps with PAD)
   - VoiceStyles.styleFor(voice, tokenCount) per sentence (NPZ)
   - OrtSession.run(input_ids|tokens, style, speed) → "audio" float32 [1, N]
   - sentence splitting if text > 510 tokens
   - Two ONNX export conventions auto-detected (input_ids vs tokens)
         │
         ▼
  bus.emit(AudioEvent.TtsAudioReady) (24 kHz int16 PCM)
         │
         ▼
  TtsAudioPlayer (dedicated AudioTrack @ 24 kHz, Mutex-serialised)
         │
         ▼
  JBL Go 4 BT speaker (English voice)
```

## Operating the pipeline — expected half-duplex flow

The loop is intentionally half-duplex, because the mic and speaker share the
room and BT A2DP has ~200 ms of latency. Talk to it like a walkie-talkie:

1. **Speak** one Spanish utterance (3–6 s of continuous speech).
2. **Pause** for at least `silenceEndMs` (default 700 ms) so the chunker
   emits.
3. **Wait** for the English text to appear on screen (~2–3 s) and then
   for Kokoro to speak it (~2–5 s).
4. **Speak** the next utterance.

While Kokoro is speaking, the UI shows **🔇 VAD muted (TTS)** in the
TRANSLATIONS card and your mic input is silently discarded (see the mute
mechanism below). If you speak while it's muted, that audio is lost.

The four-tap rule (speak → pause → wait → speak) is the current v1
contract; Fase 6 aims to overlap steps 2–4 with streaming AST so it
starts feeling conversational.

## Meta-text filter

Gemma 4 E4B occasionally replies with **assistant-style preambles**
instead of a clean translation. Observed on device:

- `"The translation of the Spanish audio is: 'I'm going to the store.'"`
- `"Here is the translation: hello"`
- `"The audio was not provided."` (on near-silent chunks)
- `"I cannot translate what wasn't spoken."`

If any of these reach `TtsRouter`, Kokoro will happily read the entire
sentence out loud. `AstChunkRouter` post-filters them out.

**How it works**: after `engine.translate(...)` returns, the router
lower-cases and trims the reply and checks it against
`AstConfig.metaTextPatterns` — a `List<String>` of substrings. Any hit
means "this is meta-text about the audio, not a translation of the
audio" and the reply is dropped: `totalDiscardedMeta` increments, the
UI shows `Meta-text dropped: N` under the TRANSLATIONS card, and
**no `TranslationReady` is emitted** — TtsRouter never sees it.

**Default patterns (12 total)**:
- No-input replies: `"not provided"`, `"no audio"`, `"please provide"`,
  `"no spanish"`, `"cannot translate"`, `"no speech"`
- Assistant preambles: `"translation of"`, `"the translation"`,
  `"spanish audio"`, `"translate the"`, `"here is the"`, `"the audio"`

**Adding a new pattern**: edit
[`AstConfig.metaTextPatterns`](app/src/main/java/com/travel2chicago/gemmapipeline/ast/AstConfig.kt).
Rules of thumb:
1. Pick the shortest distinctive fragment. `"the translation"` catches
   both `"Here is the translation:"` and `"The translation is:"`.
2. Use lowercase — the filter lowers the reply before comparing.
3. Beware collateral damage. `"the audio"` catches the meta-text
   `"The audio is: hello"` **but also** any legitimate translation
   that literally contains the phrase "the audio" (rare in
   conversational Spanish, but worth noting).
4. Add a test in `AstChunkRouterTest` that exercises the new pattern
   against a real leaked phrase so regressions are visible.

There is also a **pre-inference RMS gate** (`AstConfig.rmsThreshold`,
default 500 on int16 PCM). Chunks under the threshold get dropped
**before** the ~2.8 s Gemma call — most meta-text is generated in
response to near-silent chunks, so the RMS gate stops the problem at
the source and the meta-text filter only exists for the ones that slip
past. Counter: `Skipped low-RMS: N` in the same UI row.

## VAD mute during TTS playback

Without gating, the mic re-captures the Kokoro TTS output coming out
of the speaker, the VAD fires SPEECH on the recaptured English, and
Gemma tries to translate its own English output into… whatever. To
break the loop:

- The ViewModel owns a shared `AtomicBoolean ttsPlaying`.
- `TtsAudioPlayer.play()` flips it to `true` **before** the first
  `AudioTrack.write` and clears it in `finally` (so cancellation
  mid-playback still un-mutes).
- `VadChunkingPipeline.setTtsPlayingRef(ref)` is called before
  `start()`. Every incoming `AudioData` frame checks
  `ttsPlaying.get()`; if `true`, the frame is dropped **before**
  reassembler / VAD / chunker touch it.
- Logging is **edge-triggered** — one line at the false→true edge
  (mute start) and one at the true→false edge (mute end, with the
  count of dropped mic events) instead of a log per frame. Keeps
  logcat readable during a long conversation.

There's also a **reset at the mute rising edge** so the pipeline
starts clean when the mute lifts:

1. If the chunker was actively `collecting` and had `≥ minChunkSamples`
   buffered, flush it as a normal `ChunkReady` — Gemma still gets a
   coherent utterance of what was said just before TTS started.
2. If the buffer was shorter than that, discard it silently (too
   short to translate meaningfully).
3. Unconditionally: `chunker.resetAll()` (buffer + pre-roll +
   silence counter), `processor.reset()` (VAD state machine + Silero
   LSTM state + context prefix), `reassembler.reset()` (drops any
   residual pre-mute samples that would otherwise splice onto the
   first post-mute Silero frame).

The result: after every TTS playback, the mic starts from a truly
clean baseline instead of resuming a half-finished chunk with a
multi-second audio gap in the middle.

**Physical caveat**: even with the software mute, the JBL Go 4 has
~200 ms of A2DP tail after `AudioTrack.write` returns. If the mic is
right next to the speaker, the transient at end-of-playback may still
trigger a spurious SPEECH detection immediately after un-mute. In
production, use a unidirectional mic pointing away from the speaker
and a wired USB DAC (no BT), which eliminates both the tail and the
codec re-negotiation latency.

## Fase 6 streaming — how to measure the win

Two feature flags collapse the ~14 s first-audio wall down to ~3.4 s by
pipelining Gemma's output tokens straight into Kokoro's per-sentence PCM.
Both flags **default ON** after the Fase 6 device validation — the UI
toggles are still wired so you can disable either stage at runtime if a
regression appears.

**Stage A — AST streaming** (`AstConfig.streamingEnabled`, UI switch):
`LiteRtGemmaAstEngine` uses `Conversation.sendMessageAsync(...): Flow<Message>`
instead of the synchronous `sendMessage`. `AstChunkRouter` scans the
accumulated buffer for `.`, `!`, `?` and emits ONE `TranslationReady`
per sentence with `sentenceIndex + isFinal` so downstream can start
work before Gemma finishes decoding. Meta-text filter runs on the
accumulated buffer — preambles like "The translation of the Spanish
audio is:" split across two token deltas still get caught.

**Stage B — TTS streaming** (`TtsConfig.streamingEnabled`, UI switch):
`KokoroTtsEngine.synthesizeStreaming` yields one PCM chunk per sentence;
`TtsRouter` hands each to the `TtsPlayerSink` directly. Sink bookends
(`beginUtterance` / `endUtterance` with an `AtomicInteger` depth
counter) own the shared `ttsPlaying` mute flag across the whole
utterance so per-sentence `play()` calls don't flicker the flag between
sentences — otherwise the pipeline's `handleMuteRisingEdge` would
trigger a spurious chunker reset mid-utterance.

**Stage C — chunker retune** (always on): `ChunkerConfig` defaults
`minChunkMs 3000 → 1500` and `silenceEndMs 700 → 500`. Halving the
min-chunk shaves ~1.5 s off end-of-speech → first-audio without hurting
translation quality on the 10-phrase canonical device test. The sliders
in "8. CHUNKER TUNING" are still wired if a specific utterance type
needs different framing.

**MTP / speculative decoding** (`AstConfig.mtpEnabled`, UI switch,
**default OFF**): sets `ExperimentalFlags.enableSpeculativeDecoding = true`
before `Engine.initialize()`. Uses the model's built-in Multi-Token
Prediction drafter to speculate on the next N tokens and verify them
in a single decode step — advertised as ~2.2× **decode** speedup.
Turned off after device testing: our AST replies are ~5–10 tokens
long, so decode is not the bottleneck and MTP's win is negligible.
Also, our current model export (`gemma4_4b_v09_...`) does not embed the
drafter subgraph — the flag is a no-op on it. The toggle stays for
future experiments (longer replies, model swaps); the change takes
effect on the next Gemma reload.

**LiteRT-LM 0.14 does NOT support audio-input streaming.** Reverse-
engineering the local AAR + reading upstream docs confirmed there's no
`sendAudioFrame(...)` or `AudioStreamingEnabled` setter. Gemma still
needs the whole chunk before it starts decoding. The chunker retune is
the only lever on the input-side latency; the two streaming flags help
on the output side.

**Measurement panel** in the UI ("3½. FASE 6 STREAMING" card):
- `First token: X ms` — wall-clock from `ChunkReady.timestampNs` to
  Gemma's first output. In streaming mode = time to first delta; in
  one-shot = time to the full reply.
- `First audio: Y ms` — wall-clock from the same anchor to the first
  PCM handed to the sink / bus. This is the number the user hears.

Placeholder `—` when the value is 0 (no chunk processed since the
last pipeline start / router restart) to avoid the "0 ms = blazing
fast" misread.

**Device test protocol used for validation** (3 rounds; the same 10
canonical Spanish phrases each time):
1. Both flags OFF — baseline. Stage C is already on, so this is
   Fase 5 numbers minus ~1.5 s of chunker retune.
2. Flip AST streaming ON, TTS streaming OFF. `First token` drops
   sharply (Gemma emits tokens as they decode); `First audio` drops
   by less because Kokoro still runs full-utterance.
3. Flip both ON. `First audio` drops again as Kokoro speaks
   sentence 1 while sentences 2..N are still synthesising —
   noticeable with multi-sentence translations, marginal with
   single-sentence ones.

Measured on Xiaomi 15T Pro during validation (July 2026):

| Configuration | First token | First audio | Notes |
|---|---|---|---|
| Baseline (chunker 3000/700, all off) | ~5800 ms | ~13500 ms | pre-Fase-6 |
| Stage C only (chunker 1500/500) | ~2000 ms | ~3700 ms | flags OFF |
| + Stage A ON (AST streaming) | ~1200 ms | ~3800 ms | Kokoro one-shot |
| + Stage A + B ON (all streaming) | ~1170 ms | **~3400 ms** | current default |

Biggest win came from Stage C (chunker retune); Stage A drove another
42% off first-token; Stage B is modest with one-sentence phrases and
scales up with multi-sentence translations.

## Post-Fase-6 optimisations (pending device validation)

Four investigations landed after the Fase 6 device tests, mining
Google's official multimodal Gemma docs + the local LiteRT-LM 0.12.0
AAR. Two shipped, two were dropped after testing; UI toggles for
everything under "3½. FASE 6 STREAMING".

**Merged + pending device validation**:

- **Audio-after-text order** (`AstConfig.audioAfterText`, default ON).
  Google's docs: "For optimal performance with multimodal inputs, place
  audio content AFTER the text in your prompt. Getting this order wrong
  will reduce accuracy." Fase 0 shipped audio-first for compat reasons
  that no longer apply — now `Contents.of(Content.Text(prompt),
  Content.AudioBytes(wav))` by default. Legacy order is one toggle away.

- **Official Google AST prompt** (`AstConfig.useOfficialAstPrompt`,
  default ON). The prompt asks Gemma to output the Spanish
  transcription, then a newline, then `English: `, then the English
  translation. The router extracts everything after `English:` before
  emitting `TranslationReady` so Kokoro never speaks the Spanish
  transcription. Streaming path holds a gate — no sentence emission
  until the marker arrives; if it never arrives (Gemma ignored the
  format), an end-of-flow fallback treats the whole reply as the
  translation. `englishMarkerMissing` counter surfaces in the UI for
  diagnosis. Legacy Fase 4 English-only prompt kept as
  `AstConfig.legacyPrompt` — one toggle away.

- **Fast TTS mode** (`TtsConfig.useFastMode`, default OFF).
  `AndroidTtsEngine` wraps `android.speech.tts.TextToSpeech` with
  `Locale.US` — Android's system TTS renders straight to the system
  output (~100–300 ms/utterance vs Kokoro's ~1.5–2.5 s). Bookends
  (`beginUtterance` / `endUtterance`) still fire on the sink so the
  VAD-mute flag stays raised during playback — mic doesn't re-capture
  the speaker output. UI status text reports Android TTS init state
  and expected latency range for the active mode. Default OFF because
  the user chooses Fast mode when speed > voice quality.

**Investigated + dropped**:

- **MTP / speculative decoding** (`AstConfig.mtpEnabled`, default
  OFF). `ExperimentalFlags.enableSpeculativeDecoding = true` was
  available and wired in, but our translation outputs are ~5–10
  tokens — MTP accelerates decode, not prefill, so the win on short
  outputs is negligible. Our current Fase 0 model export also
  doesn't embed the drafter, so the flag was a silent no-op anyway.
  Toggle preserved for future model swaps / long-form workloads.

- **`gemma-4-E4B-it.litertlm` model export** (bumped then reverted).
  The official post-2026-05-05 export was tested and produced
  noticeably worse AST — Spanish echoes, garbled English, higher
  latency. Reverted to the Fase 0 `gemma4_4b_v09_...` model. See
  [PROGRESS.md](PROGRESS.md) for the full write-up.

## Fase 7 — LiteRT-LM 0.15 upgrade attempt (August 2026, REVERTED)

We bumped LiteRT-LM `0.12.0 → 0.15.0` to pick up NPU/GPU audio
acceleration, `Engine.setNativeMinLogSeverity`, and `Capabilities`.
Device testing killed the upgrade:

- **GPU audio never activated** with the current Fase 0 model — the
  audio subgraph isn't GPU-compatible. Load fell back to CPU audio
  on every attempt; zero gain.
- **First-token latency regressed** from ~1170 ms (Fase 6 baseline)
  to ~2000 ms sustained. Not bisected — no time to investigate
  while other things were breaking.
- **Two OOM crashes** during sustained runs. The 0.15 AAR is ~4 MB
  heavier than 0.12; on a device already running Gemma-GPU + Silero
  + Kokoro ONNX + Oboe buffers, the extra headroom mattered.

Reverted to `0.12.0`. `AstConfig.audioBackendGpu` (default `false`)
and the `(main, audio)` fallback chain in
`LiteRtGemmaAstEngine.load()` are preserved so a future re-attempt
is just a version bump + flag flip. The `Engine.setNativeMinLogSeverity`
and `Capabilities` calls (0.14+ APIs) were removed from source —
they don't compile against 0.12.

Full write-up in [`PROGRESS.md`](PROGRESS.md).

## Known noise

During `engine.initialize()` LiteRT-LM v0.12.0 emits hundreds of native
logs of the form:

```
[litert_dispatch.cc:113] No dispatch library found in /sdcard/Download/gemma_model
```

This is **expected and harmless**. The dispatch library is an optional
accelerator hook; when absent, LiteRT-LM falls back to the declared backend
(GPU or CPU) without functional degradation. `EngineConfig` in 0.12 does
not expose a setting to point at, disable, or silence this path. The log
comes from native code (`__android_log_print`), so a Kotlin `Log` filter
cannot suppress it. 0.14+ exposes `Engine.setNativeMinLogSeverity(...)`
that would silence it, but the Fase 7 upgrade to 0.15 regressed other
things and was reverted — so on 0.12 the noise stays.

## Go/No-Go criteria (Fase 4 + Fase 5) — 12/12 PASS

All twelve criteria validated on device. Highlights:

- **AST #6**: First end-to-end translation in <3 s from end-of-speech. ✅
- **AST #7**: 10 consecutive utterances translated, avg 2.7–3.3 s. ✅
- **AST #10**: Sustained run without OOM, leaks, or overflow. ✅
- **TTS #6**: Synthesized PCM is intelligible English via the JBL Go 4. ✅
- **TTS #7**: 5 consecutive translations are spoken in order, no overlap. ✅
- **TTS #11**: `stopGracefully` drains any pending translation through TTS
  before the pipeline reports stopped. ✅

Sample real outputs observed in device:
- "Hello Luisa, how did you wake up today? How was your weekend?"
- Kokoro: 54 voices loaded from the real NPZ, `af_heart` default,
  1.3 s load, 1.5–5 s synthesis per sentence.

Full validation report, the six fixes applied during device testing
(int8 model filename, real assets, two ONNX I/O conventions, NPZ voices,
per-char tokeniser, per-call Conversation), and the four known issues
deferred to the latency-optimisation pass: see [`PROGRESS.md`](PROGRESS.md).

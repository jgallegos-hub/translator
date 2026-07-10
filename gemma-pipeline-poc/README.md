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
  protocol passed with 0 errors. **Both streaming flags default ON**
  as of this commit; measured latencies: baseline ~13.5 s → Stage C
  only ~3.7 s → all stages ON ~3.4 s (first-audio, end-to-end). The
  UI toggles under "3½. FASE 6 STREAMING" remain wired for runtime
  disabling if a regression appears. Latency counters (`First token`
  + `First audio`) live in the same panel.

See [`PROGRESS.md`](PROGRESS.md) for the full validation results, the
six fixes applied during Fase 5 device testing, the Fase 6 investigation
findings (LiteRT-LM has NO public audio-input streaming API — the win
comes from output-token streaming + per-sentence TTS), and the three
staged optimisations with their feature flags.

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
   gemma-4-E4B-it.litertlm
   .litertlm.audio_adapter.xnnpack_cache
   .litertlm.audio_encoder.xnnpack_cache
   .litertlm.static_audio_encoder.xnnpack_cache
   .litertlm_16442536968298684338.bin
   .litertlm_1776297412_3609411584_mldrift_program_cache.bin
   .litertlm_5818495038867434237.bin
   ```
   Reuse the directory from `gemma-ast-poc` (Fase 0). The companion files are
   required for the GPU backend. The `.litertlm` filename bumped in this
   commit — the newer export contains the MTP drafter subgraph so
   `AstConfig.mtpEnabled = true` actually kicks in; the previous
   `gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm` from Fase 0/5
   does not have the drafter and MTP silently no-ops on it.

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
default ON): sets `ExperimentalFlags.enableSpeculativeDecoding = true`
before `Engine.initialize()`. Uses the model's built-in Multi-Token
Prediction drafter to speculate on the next N tokens and verify them
in a single decode step — advertised as ~2.2× decode speedup. Requires
a `.litertlm` export built after 2026-05-05 (contains the drafter
subgraph); older models silently ignore the flag with no crash and no
gain. The toggle acts as a kill-switch: flipping it only takes effect
on the next Gemma reload.

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

## Known noise

During `engine.initialize()` LiteRT-LM v0.12.0 emits hundreds of native
logs of the form:

```
[litert_dispatch.cc:113] No dispatch library found in /sdcard/Download/gemma_model
```

This is **expected and harmless**. The dispatch library is an optional
accelerator hook; when absent, LiteRT-LM falls back to the declared backend
(GPU or CPU) without functional degradation. The SDK's `EngineConfig` does
not expose a setting to point at, disable, or silence this path. The log
comes from native code (`__android_log_print`), so a Kotlin `Log` filter
cannot suppress it. See the comment block in `GemmaAstEngine.kt` for the
exact spot to wire a dispatch-lib config if a future SDK release adds one.

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

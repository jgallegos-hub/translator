# gemma-pipeline-poc

Standalone Android POC for **Fases 4 + 5** of the Travel2Chicago real-time
ES→EN translator. Integrates Silero VAD + chunker (Fase 3), Gemma 4 E4B AST
(Fase 0), and Kokoro-82M TTS (Fase 5) into one app — full speech-to-speech
pipeline in one process.

## What it does

1. Oboe captures Spanish audio from the Saramonic USB mic (Fase 2).
2. Silero VAD v5 (with the mandatory 64-sample context prefix, Fase 3) detects
   speech and a 3–6 s chunker emits utterances.
3. Each chunk is wrapped in a WAV (RIFF/WAVE PCM 16-bit) and sent to Gemma 4
   E4B via LiteRT-LM (`Backend.GPU()` + `audioBackend=Backend.CPU()`).
4. The English translation appears in the UI within ~2 s.
5. Each translation is fed to Kokoro TTS (ONNX Runtime CPU, int8 model) to
   synthesize PCM at 24 kHz, played back through a dedicated `AudioTrack`.

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
   required for the GPU backend.

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
   - Phonemizer (dict-based, EN-US)
   - Tokenizer (phoneme→ID + BOS/EOS)
   - OrtSession.run(input_ids, style, speed) → "audio" float32 [1, N]
   - sentence splitting if text > 510 tokens
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

## Go/No-Go criteria (Fase 4 + Fase 5)

12 criteria documented in the project plan file. Critical for shipping:

- **AST #6**: First end-to-end translation in <3 s from end-of-speech.
- **AST #7**: 10 consecutive utterances translated, avg latency <3 000 ms.
- **AST #10**: Sustained 10-min run without OOM, leaks, or overflow.
- **TTS #6**: Synthesized PCM is intelligible English via the JBL Go 4.
- **TTS #7**: 5 consecutive translations are spoken in order, no overlap.
- **TTS #11**: `stopGracefully` drains any pending translation through TTS
  before the pipeline reports stopped.

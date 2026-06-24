# gemma-pipeline-poc

Standalone Android POC for **Fase 4** of the Travel2Chicago real-time ES→EN
translator: integrates the VAD + chunker layer (Fase 3) with Gemma 4 E4B AST
(Fase 0). The first end-to-end POC — speech goes in, English text comes out.

## What it does

1. Oboe captures Spanish audio from the Saramonic USB mic (Fase 2).
2. Silero VAD v5 (with the mandatory 64-sample context prefix, Fase 3) detects
   speech and a 3–6 s chunker emits utterances.
3. Each chunk is wrapped in a WAV (RIFF/WAVE PCM 16-bit) and sent to Gemma 4
   E4B via LiteRT-LM (`Backend.GPU()` + `audioBackend=Backend.CPU()`).
4. The English translation appears in the UI within ~2 s.

No TTS yet — that's Fase 5 (Kokoro).

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

2. `silero_vad.onnx` is bundled in `app/src/main/assets/` — no action needed.

3. Grant `MANAGE_EXTERNAL_STORAGE` when prompted (needed to read the Gemma
   model from `/sdcard/Download/`).

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
         ▼
  UI sección "TRANSLATIONS"
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

## Go/No-Go for Fase 5

12 criteria — see the project plan file for the full list. Critical:

- **#6**: First end-to-end translation in <3 s from end-of-speech.
- **#7**: 10 consecutive utterances all translated, avg latency <3000 ms.
- **#10**: Sustained 10-min run without OOM, leaks, or overflow.

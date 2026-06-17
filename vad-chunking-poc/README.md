# vad-chunking-poc

Standalone Android POC for **Fase 3** of the Travel2Chicago real-time ES→EN
translator: Voice Activity Detection (Silero VAD via ONNX Runtime) + smart
chunking (3–6 s utterances with pre-roll), feeding off the audio plumbing
validated in `audio-capture-poc/` (Fase 2).

This POC is the bridge between Fase 2 (raw audio capture) and Fase 4 (Gemma
4 E4B AST). It does NOT call Gemma — it just emits chunks that you can
audibly verify via the "Replay last chunk" button.

---

## What gets validated here

| Layer | Implementation | Mirrors |
|---|---|---|
| Audio capture | Oboe + USB mic (copied verbatim from audio-capture-poc) | `audiopoc/audio/` |
| Audio playback | Oboe (USB DAC) / AudioTrack (BT A2DP) — `PlaybackSink` dispatch | `audiopoc/audio/PlaybackSink.kt` |
| VAD | Silero v5 ONNX via `onnxruntime-android:1.19.2`, state-machine anti-flicker | `src/audio_manager/vad_processor.py` |
| Chunker | ArrayDeque pre-roll, max-size + silence-end triggers | `src/audio_manager/audio_chunker.py` |
| Frame reassembler | NEW (no Python equivalent) — variable Oboe chunks → fixed 512 samples | n/a |

## Setup before first run

1. Download the Silero VAD model:
   ```
   https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx
   ```
2. Place it at `app/src/main/assets/silero_vad.onnx` (~2.2 MB).
3. Open in Android Studio → run on the Xiaomi 15T Pro with Saramonic USB
   connected.

Unit tests mock the model and do NOT require the .onnx file.

## Go/No-Go criteria

Pipeline runs end-to-end on the Xiaomi with the Saramonic USB:

1. `assembleDebug` compiles without warnings.
2. 32+ unit tests pass (`testDebugUnitTest`).
3. Model loads in <500 ms; APK <15 MB.
4. Reassembler emits frames of exactly 512 samples; no samples lost.
5. VAD detects voice in <100 ms from the vocal attack; prob >0.7 during speech.
6. VAD returns to SILENCE ~300 ms after the word ends.
7. Single cough does NOT flip state to SPEECH (anti-flicker holds).
8. Chunker emits a ~4 s chunk after "talk 4 s, then silent 1 s".
9. Chunker forces a split at 6 s during a continuous 8 s talk.
10. Replay of last chunk is audibly intelligible (pre-roll preserves the onset).
11. `SileroVadModel.probability()` <5 ms per frame.
12. 5 minutes of intermittent speech → 0 crashes, 0 ring-buffer overflows.

## What's NOT in this POC

- Gemma AST integration (Fase 4)
- TTS (Fase 5)
- Dual-mic
- AEC / noise suppression pre-VAD
- Auto-calibration of thresholds — manual via in-app sliders

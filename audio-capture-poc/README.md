# audio-capture-poc

Fase 2 POC: validates the **NDK + Oboe + lock-free ring buffer + JNI** path
on the Xiaomi 15T Pro before merging this code into the production translator
app. Standalone, no Gemma, no VAD, no chunker.

## What it does

- Opens an Oboe capture stream against the selected input device (Saramonic
  USB mic via the Inland 10G hub) at 16 kHz mono int16, low-latency mode.
- The Oboe callback writes each frame to a lock-free SPSC ring buffer in C++
  (`ring_buffer.cpp`, port of `src/audio_manager/buffers.py`).
- A Kotlin drain coroutine (`AudioCaptureManager`) reads the ring every 25 ms
  via JNI and emits `AudioEvent.AudioData` onto an `AudioEventBus` (`SharedFlow`).
- The Compose UI subscribes to that bus and shows a live VU meter, sample
  counter, overflow counter, routing info and Oboe-reported latency.
- A separate Oboe playback stream can replay captured audio (loop-back switch)
  or play a 440 Hz sine tone for output sanity checks.

## Files

| Layer | File | Purpose |
|---|---|---|
| C++  | `cpp/ring_buffer.{h,cpp}` | SPSC lock-free buffer, port of `buffers.py` |
| C++  | `cpp/audio_engine.{h,cpp}` | Oboe capture + playback streams, callbacks |
| C++  | `cpp/jni_bridge.cpp` | JNI symbols consumed by Kotlin |
| C++  | `cpp/CMakeLists.txt` | Build, links `oboe::oboe` via prefab |
| KT   | `audio/AudioConfig.kt` | `AudioFormat`, `RingBufferConfig`, `AudioEngineConfig` |
| KT   | `audio/AudioTypes.kt` | sealed `AudioEvent` |
| KT   | `audio/AudioException.kt` | sealed errors |
| KT   | `audio/AudioEventBus.kt` | `SharedFlow` event bus |
| KT   | `audio/AudioDeviceManager.kt` | enumerate + hot-plug callback |
| KT   | `audio/NativeAudioEngine.kt` | Kotlin wrapper over JNI |
| KT   | `audio/AudioCaptureManager.kt` | drain coroutine, emits to bus |
| KT   | `audio/AudioPlaybackManager.kt` | feed playback ring, sine generator |
| KT   | `AudioPocViewModel.kt` | UI state, event aggregation |
| KT   | `ui/AudioPocScreen.kt` | Compose UI, VU meter, device selector |
| Test | `AudioConfigTest.kt`, `AudioEventBusTest.kt` | JVM unit tests |

## How to use

1. Plug the Saramonic into the Xiaomi (directly or through the Inland 10G hub).
2. Install:
   ```
   ./gradlew installDebug
   ```
3. Grant `RECORD_AUDIO` and `BLUETOOTH_CONNECT` in the system prompt.
4. Verify the Saramonic appears in **INPUT DEVICES** marked ★. Tap to select.
5. Tap **Start capture** — the VU meter should react when you speak.
6. Tap **Play 440 Hz sine** to verify output works. Then flip **Loopback**
   to hear yourself routed mic → engine → playback (USB DAC ideally).

## Go/No-Go checks for Fase 3

| # | Check | Pass |
|---|---|---|
| 1 | `./gradlew assembleDebug` succeeds (C++ + Kotlin) | ✅ |
| 2 | Unit tests pass (`./gradlew test`) | ✅ |
| 3 | App installs and lists Saramonic as a USB input | manual |
| 4 | Capture: VU meter responds, samples counter climbs, overflow=0 | manual |
| 5 | Oboe latency < 30 ms reported in the UI | manual |
| 6 | 5 min sustained capture without overflow / crash | manual |
| 7 | Sine wave audible on USB DAC / BT speaker | manual |
| 8 | Loop-back: speaking into the mic comes out the speaker with < 200 ms delay | manual |
| 9 | Hot-plug: unplug USB → device disappears within ~1 s, plug back → re-appears | manual |

## Out of scope

- VAD, chunking, Gemma AST, TTS — those are Fase 3, 4, 5.
- C++ unit tests via GTest (not worth the Gradle setup for this POC; the
  ring buffer is exposed through `NativeRingBuffer` so an instrumented test
  on device can exercise it).

# audio-hw-check

Mini Android app that validates the hardware path required by the translator
(Pre-Fase 2): **Saramonic USB mic → Xiaomi 15T Pro → JBL Go 4 BT speaker**,
using only the standard Android API (`AudioRecord` + `AudioTrack`).

If this works, the same path is guaranteed to work via Oboe (which sits on
top of the same HAL drivers), so we can safely build the NDK POC next. If it
doesn't work, we know to fix MIUI / pairing issues *before* writing any C++.

## What it does

1. Enumerates input and output devices via `AudioManager.getDevices()`.
2. Auto-selects the Saramonic USB mic (input) and JBL Go 4 A2DP (output)
   when present, marking them with ★.
3. **Record**: captures 5s of 16 kHz mono PCM 16-bit from the selected input
   using `AudioRecord.setPreferredDevice(...)` and verifies routing via
   `record.routedDevice`.
4. **Play**: streams the recorded buffer to the selected output using
   `AudioTrack.MODE_STATIC` and verifies routing via `track.routedDevice`.
5. Reports RMS of the recording (so you can tell silence from real signal)
   and whether the OS honored the preferred device.

## How to use

1. Pair the JBL Go 4 in Android Bluetooth settings, then connect it.
2. Plug the Saramonic into the Xiaomi via USB-C OTG.
3. Build and install:
   ```
   ./gradlew installDebug
   ```
4. Grant `RECORD_AUDIO` and `BLUETOOTH_CONNECT` in the system prompt.
5. Verify the Saramonic shows in **INPUT DEVICES** with ★, and the JBL in
   **OUTPUT DEVICES** with ★.
6. Tap **RECORD 5s** and speak into the mic.
7. Tap **PLAY recording** and listen.

## Pass criteria (9 checks)

| # | Check |
|---|---|
| 1 | Saramonic detected (USB_HEADSET or USB_DEVICE) |
| 2 | JBL Go 4 detected (BLUETOOTH_A2DP) |
| 3 | Both auto-selected with ★ |
| 4 | Record completes — ~80,000 samples in ~5s |
| 5 | RMS avg > 100 when speaking |
| 6 | `routedDevice` for record matches Saramonic |
| 7 | Playback audible via JBL Go 4 |
| 8 | `routedDevice` for playback matches JBL |
| 9 | Hot-plug: unplug USB → list updates in <2s |

**GO** (≥7/9 incl. 4-8) → proceed to Fase 2 (Oboe POC).
**NO-GO** (any of 4, 6, 7, 8 fails) → debug MIUI USB host / BT routing first.

## Not included

- No NDK, Oboe, ring buffer, VAD, Gemma, TTS.
- No file output (recording lives in RAM only).
- No instrumented tests — manual verification on physical device.

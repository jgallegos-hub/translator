# Translator — Progress Tracker

## Fase 0: POC Gemma AST ✅ COMPLETADA (25 mayo 2026)

### Resultados:
- GPU backend: FUNCIONA (16s load, 3-7 tok/s)
- Audio AST: FUNCIONA (audio ES → texto EN en ~2s via Content.AudioBytes WAV)
- Traducciones texto: perfectas (4/4 frases correctas)
- Memoria: ~765MB (dentro del budget de 12GB)
- audioBackend: CPU (configurado en EngineConfig)
- Configuración clave copiada de AI Edge Gallery source code

### Decisiones confirmadas:
- Pipeline: Mic → Gemma 4 E4B AST directo (sin Whisper) → Kokoro TTS → Speaker
- LiteRT-LM v0.12.0 con GPU backend + audioBackend CPU
- Modelo: gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm (~3.4GB)
- Companion files requeridos para GPU (xnnpack caches, weight shards)

### Configuración validada:
```kotlin
EngineConfig(
    modelPath = modelFile.absolutePath,
    backend = Backend.GPU(),
    audioBackend = Backend.CPU(),
    maxNumTokens = 1024,
    cacheDir = externalFilesDir,
)
```

### Archivos companion requeridos para GPU:
- `.litertlm.audio_adapter.xnnpack_cache`
- `.litertlm.audio_encoder.xnnpack_cache`
- `.litertlm.static_audio_encoder.xnnpack_cache`
- `.litertlm_16442536968298684338.bin`
- `.litertlm_1776297412_3609411584_mldrift_program_cache.bin`
- `.litertlm_5818495038867434237.bin`

### AndroidManifest GPU declarations:
```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
<uses-native-library android:name="libvndksupport.so" android:required="false" />
```

---

## Pre-Fase 2: Hardware Sanity Check ✅ COMPLETADA (31 mayo 2026)

App standalone `audio-hw-check/` valida el hardware path antes de invertir en NDK/Oboe.

### Resultados:
- Samaronic USB mic detectado como `USB_HEADSET` en Xiaomi 15T Pro
- Funciona directo al Xiaomi **Y** a través del hub Inland 10G USB-C
- Captura: 80,000 samples, 5.00s, RMS 7785.9 (señal real)
- Routing input correcto: `USB-Audio [USB_HEADSET]`
- JBL Go 4 Bluetooth A2DP: playback OK, routing correcto
- `AudioRecord` + `AudioTrack` funcionan en Android 16 / HyperOS
- **9/9 criterios Go/No-Go: PASS**

### Decisión de hardware confirmada:
- Producción usará **speaker cableado por USB hub** en vez de BT, porque el JBL Go 4 se desconecta por inactividad. El hub Inland 10G ya soporta el Samaronic mic, así que añadir un USB DAC + speaker en el mismo hub es la ruta más confiable.
- Fase 2 Oboe debe soportar capture USB + playback USB en paralelo (ambos a través del hub).

### Lecciones aplicables a Fase 2:
- Para `AudioTrack` en `MODE_STATIC`: el estado inicial es `STATE_NO_STATIC_DATA` (2), no `STATE_INITIALIZED` (1). Solo aborta si `STATE_UNINITIALIZED` (0). Verifica `INITIALIZED` después de `write()`.
- Forzar `AudioManager.mode = MODE_NORMAL` antes de playback para que `USAGE_MEDIA` no se rutee al earpiece.
- `setPreferredDevice` antes de `write()` y `play()` — pinear routing temprano.
- Llamar a `getRoutedDevice()` después de `start/play` y verificar que matchea el preferred (logging defensivo).

---

## Fase 2: Audio Capture con Oboe (NDK) ✅ COMPLETADA (17 junio 2026)

POC standalone `audio-capture-poc/` validado en Xiaomi 15T Pro (Dimensity 9400+, Android 16 / HyperOS).

### Resultados:
- **Oboe capture del Saramonic USB**: OK — device id=140, 16kHz mono, format I16, `Unprocessed` input preset, `LowLatency` + `Exclusive`
- **AudioTrack playback al JBL Go 4 BT (A2DP)**: OK — routing correcto via `AudioTrackSink`
- **Loopback (mic → speaker)**: funciona end-to-end (reverb esperado por feedback loop acústico)
- **VU meter**: responde en tiempo real al hablar, drain interval 25 ms
- **Tono 440Hz**: se oye limpio en JBL sin glitches
- **Device detection**: enumeración + hot-plug funcionan (USB y BT)
- **ADB por WiFi** configurado para iteración rápida

### Arquitectura validada (dual-backend playback):

| Sink | Cuándo se usa | Razón |
|---|---|---|
| `OboePlaybackSink` | USB DAC, BUILTIN_SPEAKER, WIRED | LowLatency + Exclusive, baja latencia real |
| `AudioTrackSink` | BLUETOOTH_A2DP, BLE_HEADSET, SCO | **Oboe Exclusive/LowLatency no rutea a A2DP en HyperOS** — silencioso reroute al earpiece. AudioTrack via AudioPolicyManager sí rutea correctamente. |

`AudioPlaybackManager.pickSink(device)` selecciona en `start()` basado en `type.isBluetoothOutput()`. `feedLoopback()` y `playSineWave()` usan la misma interfaz `PlaybackSink.write()` — el caller no sabe cuál back-end está activo.

### Lecciones técnicas:
- `Oboe::calculateLatencyMillis()` retorna `ResultWithValue<double>`, NO un puntero — usar `.value()` después de checar `operator bool()`.
- `AudioTrack.write` en `MODE_STREAM` necesita `WRITE_NON_BLOCKING` para que el drain coroutine no se bloquee si el buffer BT se llena (drop el tail en su lugar).
- Buffer ≥ 1s en `AudioTrack` para tolerar jitter BT.
- `setPreferredDevice` ANTES de `play()` — confirmado en ambos POCs.
- Forzar `AudioManager.mode = MODE_NORMAL` antes de abrir AudioTrack BT — restaurar al stop.
- `<uses-native-library>` va dentro de `<application>`, NO de `<manifest>`.
- Material3 `Switch` con `enabled=false` ignora silenciosamente `onCheckedChange` — no gatear el switch de loopback en estados del stream; el guard real va en el handler de eventos.

### Decisión confirmada para producción:
- **Captura**: Oboe + USB (Saramonic via hub Inland 10G) — la ruta más confiable, menor latencia.
- **Playback**: AudioTrack si BT, Oboe si USB DAC/builtin. La abstracción `PlaybackSink` se mantiene en la app de producción.

### 9/9 criterios Go/No-Go: PASS

---

## Fase 3: VAD + Chunking (Pendiente)
- SileroVADProcessor con ONNX Runtime Android (port de `vad_processor.py`)
- AudioChunker (3-6s, 700ms silence_end, 200ms pre-roll — port de `audio_chunker.py`)
- Integración con `AudioEventBus` del POC de Fase 2
- POC standalone `vad-chunking-poc/` (mismo patrón que Fases 0 y 2)

### Siguiente: Fase 3 — VAD + Chunking en Android

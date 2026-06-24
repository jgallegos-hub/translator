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

## Fase 3: VAD + Chunking ✅ COMPLETADA (18 junio 2026)

POC standalone `vad-chunking-poc/` validado en Xiaomi 15T Pro. Silero VAD v5 (ONNX Runtime Android) + chunker 3–6 s integrados sobre la capa Oboe de Fase 2. Commit final: **`e98b996`**.

### Resultados:
- **VAD detecta speech consistentemente** (p > 0.9 hablando, ~0.001 en silencio)
- **Chunks de 3–6 s** emitidos correctamente con pre-roll de 200 ms (sin clipping del syllable inicial)
- **Múltiples transiciones SPEECH ↔ SILENCE** funcionando sin flicker
- **Replay del último chunk audible** — coherente con lo que se habló
- **Self-test del modelo al cargar** confirma sanidad: silence → p≈0, noise → p>0.05
- **39 unit tests passing** (11 VAD + 12 chunker + 8 reassembler + 8 configs)

### Bugs críticos corregidos (en orden de descubrimiento):

**Bug 1 — Sample rate mismatch** (commit `733c09e`):
Oboe en el Xiaomi abre el USB Saramonic a 48 kHz (sample rate nativo del hardware) aunque pidamos 16 kHz. Le pasábamos los 512 samples a Silero diciéndole `sr=16000` → el modelo veía 10.6 ms de audio en vez de 32 ms y respondía con basura. Fix: el pipeline consulta `engine.actualSampleRateCapture()` al arrancar y aplica mean-decimation por factor entero (48 → 16 = factor 3) antes del FrameReassembler.

**Bug 2 — Lectura de outputs ONNX por índice** (commit `b697944`):
`results[0]` no es necesariamente el output declarado primero — ORT no garantiza el orden. Si esta build de Silero retorna `stateN` en [0] y `output` en [1], leíamos el primer float del LSTM state como probabilidad (≈ 0 tras init). Fix: `results.get("output")` y `results.get("stateN")` por nombre.

**Bug 3 — Silero v5 requiere context prefix de 64 samples** (commit `e98b996`):
El wrapper oficial (`silero_vad/utils_vad.py OnnxWrapper.__call__`) muestra que el input real al modelo es **576 samples = `[context_64 | frame_512]`** donde `context_64` son los últimos 64 samples del frame anterior. Pasarle solo 512 samples causaba que el LSTM interpretara los primeros 64 como "context residual" y todo lo demás desalineado. El state crecía linealmente (+1.0 por inferencia, exacto en los logs) hasta saturar la sigmoid a las ~350 inferencias y colapsar prob a 0 para siempre. Fix: buffer rodante `context: FloatArray(64)`, prepended a cada frame antes de la inferencia, actualizado con `frame[-64:]` después.

### Lecciones técnicas:
- ORT Android 1.19 `OnnxTensor.createTensor(env, Object)` con `Array<FloatArray>` no funciona → usar `FloatBuffer.wrap(...)` con `long[]` explícito de shape.
- Silero v5 no es drop-in con su predecesor v4 (que usaba estados `h`/`c` separados de shape [2,1,64]). El v5 tiene single state [2,1,128] + context prefix obligatorio.
- Anti-flicker en VAD state machine (counters consecutiveSpeech/consecutiveSilence) elimina tos / transientes sin perder palabras cortas.
- Output `stateN` declarado como [-1,-1,-1] dynamic en el static spec; el runtime shape real es [2,1,128] — loguear shape real en primera inferencia ayuda a confirmar.
- `unitTests.isReturnDefaultValues = true` en `build.gradle.kts` evita que `android.util.Log` rompa los unit tests JVM.

### Arquitectura validada:
- `AudioEventBus` (Kotlin `SharedFlow`) como columna vertebral — capture publica `AudioData`, pipeline publica `VadTransition` + `ChunkReady` al mismo bus. Múltiples colectores independientes (UI ViewModel + pipeline) sin interferencia.
- `FrameReassembler` (nuevo, sin contraparte Python) re-batches los chunks variables de Oboe (1–2048 samples por evento) en frames fijos de 512 para Silero. Sum-equality preservada bajo carga aleatoria.
- Sliders en UI para todos los tunables (threshold/minSpeech/minSilence/min/max/silenceEnd/preRoll) — la única forma realista de calibrar es con voz real en device.

### 12/12 criterios Go/No-Go: PASS

---

## Fase 4: Gemma AST en Pipeline (Pendiente)
- POC standalone `gemma-pipeline-poc/` (mismo patrón)
- Reusa capa audio/vad/chunker verbatim de Fase 3
- Nuevo paquete `ast/`: AstConfig, WavBuilder (PCM int16 → WAV bytes), GemmaAstEngine (wrapper LiteRT-LM), AstChunkRouter (Channel bounded DROP_OLDEST, consumer single-threaded)
- Integra Gemma 4 E4B AST (configuración validada en Fase 0) — chunks 3-6 s → traducción ES→EN en texto
- UI: sección "TRANSLATIONS" en tiempo real con avg latency + queue size

### Siguiente: Fase 4 — Gemma AST en Pipeline

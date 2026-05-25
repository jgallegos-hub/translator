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

## Fase 1: Fundación (Pendiente)
- Configurar proyecto Android con NDK, Compose, todas las dependencias
- AudioConfig, AudioTypes, AudioEventBus con SharedFlow
- Sealed classes para eventos

## Fase 2: Audio Engine NDK (Pendiente)
- Ring buffer C++ lock-free (port de buffers.py)
- Oboe capture/playback streams
- JNI bridge a Kotlin
- AudioDeviceManager (USB mic, BT speaker detection)

### Siguiente: Fase 2 — Audio Capture Android

# Research: eco y latencia — alternativas locales (2026-10-08)

Leyenda: **[V]** verificado en fuente primaria · **[I]** inferencia/estimación a medir. Benchmarks x86 de terceros no son trasladables a ARM.

Spot-check adicional (2026-10-08): `Enaium/webrtc-aec3-kmp` existe (MIT, creado 2026-08-07, último push 2026-09-16, 0 estrellas); `moonshine-ai/moonshine` activo (push 2026-10-02, ~11k estrellas); `moonshine-ai/moonshine-streaming-small-es` disponible en Hugging Face.

## 1. Resumen ejecutivo

**Eco (top 5)**
1. **WebRTC AEC3 vía `webrtc-aec3-kmp`** + **pre-alineación de la referencia**. MIT, Maven Central, JNI arm64, API de frames de 10 ms con `setAudioBufferDelay` y métricas ERL/ERLE/delay [V]. AECM falló por ser la variante móvil de baja tolerancia; AEC3 tiene estimador de delay continuo diseñado para delays que se mueven [V]. Ventana por defecto ~500 ms [I]. Ganancia esperada 20–35 dB si la alineación gruesa queda dentro de ±100 ms [I].
2. **"Echo gate" por correlación con la referencia conocida**: correlacionar envolvente/espectro del mic contra el PCM del TTS en lags 0–500 ms y descartar frames que matchean. Cómputo casi nulo, tolera jitter porque busca el lag por ventana [I, diseño propio].
3. **Gate por embedding de hablante** (sherpa-onnx: 3D-Speaker / WeSpeaker / NeMo; APK Android y modelos publicados [V]). Enrolar la voz de Kokoro como negativo. ~10–40 ms/chunk [I].
4. **Half-duplex con barge-in**: silenciar ASR durante TTS + cola = latencia BT medida; detectar interrupción con (2) o energía residual de AEC3. Red de seguridad garantizada.
5. **Hardware**: altavoz USB-C cableado (elimina delay BT) o speakerphone USB con AEC en DSP (Jabra Speak2, Anker PowerConf) [I: verificar UAC con el Xiaomi].

**Latencia (top 5)**
1. **ASR streaming en español: Moonshine Streaming Small-es (123M, WER 4.9%) / Tiny-es (34M, WER 6.2%)**, MIT, librería con soporte Android, int8 `.ort` [V]. Transcribe mientras el usuario habla → elimina la espera del chunker + prefill de audio.
2. **Cascada ASR streaming → MT texto corto (Gemma 4 E2B/E4B texto, Opus-MT o ML Kit Translate) → TTS por cláusulas**: primer audio estimado ~0.8–1.3 s tras fin de habla vs ~3.4 s hoy [I].
3. **TTS más rápido**: Supertonic 3 (2-step) RTF 0.121 vs Kokoro ONNX 0.641 en Xeon 4 núcleos (~5×) [V, heyneo, x86]. Además trocear la primera frase a 5–8 palabras [I].
4. **Tras el release con PR #3749**: re-medir Gemma AST con audio streaming + encoder en GPU (PR mergeado 2026-09-26, NO en v0.18.0) [V].
5. **NPU MediaTek**: sin beneficio a corto plazo. LiteRT NeuroPilot soporta MT6991 [V], pero LiteRT-LM NPU solo tiene Gemma3-1B para MT6991 [V]; Kokoro en LiteRT no corre en GPU [V].

## 2. ECO

### Por qué AECM falló y AEC3 no tendría por qué
- AECM asume delay pequeño y estable; A2DP da 100–400 ms con ±50 ms de jitter. Con delay grande y móvil el estimador engancha, pierde y re-engancha, y el eco se filtra durante la re-convergencia; AEC3 añadió estimador continuo para esto [V, Fora Soft].
- Ventana AEC3 [I]: config por defecto `num_filters=5` matched filters de 32 sub-bloques de 4 ms solapados ≈ 512 ms. Chromium sube a `num_filters=6` con loopback del sistema y añade 170 ms de delay a la captura "para que la referencia llegue antes" [V, `media_switches.cc`] — el mismo truco que necesitamos.
- **Clave: referencia alineada en tiempo.** Propuesta:
  1. Al iniciar sesión, chirp de ~300 ms por el JBL → estimar delay mic↔altavoz con GCC-PHAT.
  2. Seguir la deriva con `AudioTrack.getTimestamp` (el CDD exige ±2 ms [V]; no verificado que incluya la latencia del sink BT; AVDTP 1.3 Delay Reporting existe y Android lo soporta [V, no primario]) → medir.
  3. Retrasar la referencia esa cantidad antes de `analyzeRender`; AEC3 solo resuelve el residual de ±50–100 ms.

### Tabla

| Opción | Cómo ataca el eco | Tolerancia delay BT | Costo | Licencia | Esfuerzo Android | Madurez |
|---|---|---|---|---|---|---|
| AEC3 (`webrtc-aec3-kmp` 1.0.3) | Filtro adaptativo lineal + supresor no lineal, referencia = PCM TTS | ~500 ms default [I]; con pre-alineación cubre 100–400 ms con jitter | Muy bajo (10 ms frames, C++) | MIT [V] | Bajo: dependencia Gradle, 16/32/48 kHz, buffers rango int16 [V] | Algoritmo maduro (Chrome/Meet); wrapper nuevo (0 estrellas, 2 meses) [V] |
| AEC3 compilado desde libwebrtc / `webrtc-audio-processing` | Igual | Igual | Igual | BSD | Alto (build NDK propio) | Alta |
| SpeexDSP AEC (MDF) | Filtro adaptativo; tail cubre delay | Malo con jitter; tail ~100 ms y buffer ajustado a mano [V, SO] | Bajo | BSD | Medio | Antigua, inferior a AEC3 [I] |
| DTLN-aec | Red LSTM mic + referencia | Desconocida, sin estimador de delay [I] | 1.8M/3.9M/10.4M params, TFLite [V] | MIT [V] | Medio | Estancado (abr-2022) [V] |
| DeepVQE / DeepVQE-S | Cross-attention alinea internamente | Buena en paper [I] | 7.5M params, 8 GFLOPs; S: 0.82M [V] | Repo no oficial | Alto (exportar ONNX) | Investigación |
| EchoFree (2025) | Kalman lineal + post-filtro neuronal | Entrenado con delays 10–512 ms [V] | 278K params, 30 MMACs/s [V] | Paper CC-BY; pesos no claros [V] | Alto | Paper |
| Gate por correlación con referencia | Detecta frames copia del TTS, descarta antes del VAD | Busca lag por ventana [I] | <1 ms/frame [I] | Propio | Bajo | Hay que validarlo |
| Embedding de hablante (sherpa-onnx) | Rechaza voz Kokoro / acepta usuario | Independiente del delay | 10–40 ms/chunk [I] | Apache-2.0 runtime [V]; modelos varían | Bajo-medio (APK ejemplo) [V] | Alta |
| Language ID (VoxLingua107 ECAPA) | Rechaza audio en inglés | Independiente | ~20M params, decenas de ms [I] | Apache [I] | Medio | Alta; solapa con "SKIP" pero más barato |
| Half-duplex + barge-in | Ignora mic durante TTS | Independiente | 0 ms | — | Bajo | Total; cuesta naturalidad |
| Speakerphone USB con AEC en DSP | El DSP cancela su propia salida | Sin BT | 0 en teléfono | — | Bajo si es UAC [I] | Alta; sin verificar con este teléfono |
| Altavoz USB-C cableado | Elimina delay BT | ~10–40 ms [I] | 0 | — | Bajo | Alta |

### Discusión
- **Defensa en capas recomendada**: (1) pre-alineación + AEC3 en señal, (2) gate por correlación (frame), (3) gate por embedding de hablante (chunk), (4) capas de texto ya implementadas (SKIP, similitud, RMS). (2) y (3) no dependen de que AEC3 converja.
- **El gate RMS es frágil**: voz ~28k vs eco ~13–18k = solo 4–6 dB de margen; falla si el usuario habla bajo o se aleja [I].
- **Embedding de hablante en modo "rechazar TTS"**, no "aceptar solo al usuario", para no bloquear a otro hispanohablante.
- **AEC neuronal**: ningún modelo maduro con pesos públicos 2025–2026 resuelve solo 400 ms con jitter; todos necesitan referencia alineada. Solo si AEC3 deja residual audible.

## 3. LATENCIA

### 3a. ASR / AST

| Opción | Español | Streaming | Tamaño | Latencia/RTF reportada | Runtime | Licencia | Esfuerzo |
|---|---|---|---|---|---|---|---|
| **Moonshine Streaming Small-es** | WER 4.9% (FLEURS/MLS, habla leída) [V] | Sí, encoder ventana deslizante, ~80 ms lookahead [V] | 123M; int8 `.ort` WER 4.938 [V] | Small EN: 148 ms de respuesta en Apple M3 [V, paper v2]; teléfono sin medir | ONNX Runtime; lib con soporte Android [V] | MIT [V] | Bajo-medio |
| Moonshine Streaming Tiny-es | WER 6.2% [V] | Sí | 34M | Tiny EN: 50 ms en M3 [V] | Igual | MIT | Bajo |
| sherpa-onnx `moonshine-base-es` (2026-02-27) | Sí | Ejemplo streaming Android [V] | 58M | — | sherpa-onnx | Community, **no comercial** [V] | Bajo |
| ML Kit GenAI Speech Recognition | Básico es-ES beta; Avanzado es-ES | Parciales streaming [V] | Sistema | Sin datos | AICore/ML Kit; **Avanzado solo Pixel 10/11** [V] | Propietaria | Bajo (alpha1) |
| Voxtral Mini 4B Realtime | 13 idiomas | Sí, delay 240 ms–2.4 s [V] | 4B | Realtime con ≥12.5 tok/s [V] | PyTorch/vLLM | Apache [I] | Demasiado grande junto a Gemma |
| Canary-1B-v2 | AST 24 idiomas →EN; BLEU medio FLEURS 29.08 [V] | No [V] | 978M | RTFx 749 en GPU [V] | NeMo | CC-BY-4.0 [V] | Solo baseline PC |
| Hibiki-Zero | es→en voz a voz simultánea; BLEU 32.3; lag medio 5.6 s [V] | Sí | 3B bf16 [V] | 3× realtime batch en H100 [V] | PyTorch | Ver repo | Solo referencia de calidad |
| Gemma 4 E4B AST (actual) | Bueno | No (hasta #3749) | ~4B | 1er token ~1.1–1.2 s tras fin de chunk | LiteRT-LM | Gemma | Integrado |

### 3b. MT (cascada)

| Opción | es→en | Tamaño | Latencia esperada | Runtime | Licencia | Esfuerzo |
|---|---|---|---|---|---|---|
| Gemma 4 E2B/E4B texto | Buena | Ya cargado | Prefill ~50 tok + ~10 tok 1ª cláusula: 200–400 ms GPU [I] | LiteRT-LM | Gemma | Bajo |
| TranslateGemma 4B | Dedicada, 55 idiomas [V] | 4B (base Gemma 3) | — | LiteRT web [V]; Android sin confirmar | Gemma | Medio |
| Opus-MT es-en (Marian) | Aceptable [I] | ~75M [I] | 50–150 ms [I] | ONNX / CTranslate2 | CC-BY [I] | Medio |
| ML Kit Translate | Media [I] | ~30 MB/idioma [I] | Decenas de ms [I] | ML Kit | Propietaria | Bajo |

AST directa vs cascada: la cascada gana en primer audio porque el ASR trabaja durante el habla; al terminar solo queda MT corto + TTS de la 1ª cláusula. Pierde algo de calidad (errores ASR, menos prosodia) [I].

### 3c. TTS (inglés)

| Opción | Streaming | Tamaño | Latencia/RTF | Runtime | Licencia | Esfuerzo |
|---|---|---|---|---|---|---|
| Kokoro-82M ONNX int8 (actual) | Por frase | ~92 MB [V] | RTF 0.641 Xeon 4c [V]; FTTS 3658 ms Ryzen (Picovoice) [V] | ORT CPU | Apache-2.0 | — |
| Kokoro en LiteRT | No | ~364 MB fp32 | RTF ≈1.8 Pixel 8a CPU; no corre en GPU; 1150 ms en NPU S26 [V] | LiteRT | Apache-2.0 | Peor |
| **Supertonic 3** | — | ~99M, 31 idiomas [V] | 2-step RTF 0.121; 0.26 s frase corta / 0.51 s media-corta en Xeon [V] | ORT | **OpenRAIL-M** (restricciones) [V] | Medio |
| Inflect-Nano-v1 | — | 4.6M [V] | RTF 0.145; 0.14–0.38 s frases cortas [V] | ONNX | Apache-2.0 [V] | Medio; calidad por validar |
| Piper (VITS) | Salida streaming | ~61 MB [V] | FTTS 1720 ms Ryzen [V] | sherpa-onnx Android [V] | MIT (rhasspy) / GPL (fork OHF) [V] | Bajo |
| Pocket TTS | Sí, AR | ~100M | RTF 0.714 [V] | PyTorch CPU | MIT [V] | No mejora |
| Kitten TTS Nano | No [V] | 42 MB | FTTS 10.5 s [V] | ONNX | Apache-2.0 | No |
| Moonshine Voice TTS | "Streaming TTS support" (commit 2026-08-24) [V] | — | Sin benchmark | Lib Moonshine | Carve-out de licencia en TTS [V] | Explorar si se adopta Moonshine |

Picovoice es benchmark de vendedor en x86 y su FTTS mide desde el 1er token del LLM; usar solo como ranking relativo.

### 3d. Runtimes (Dimensity 9400+)

| Opción | Estado | Utilidad |
|---|---|---|
| LiteRT NeuroPilot (NPU) | Soporta MT6991, AOT y on-device [V]; 9400+ no listado | Solo Gemma3-1B NPU en MT6991 [V] |
| LiteRT-LM v0.18 | KV dinámico en NPU, poda de máscara en GPU [V] | Esperar release con #3749 |
| ONNX Runtime | NNAPI en desuso [I]; QNN EP solo Qualcomm [I] | Moonshine/Kokoro/Supertonic: CPU XNNPACK + afinidad a núcleos grandes |
| ExecuTorch MediaTek, MNN, llama.cpp Vulkan | No verificados [I] | Baja prioridad |

## 4. Plan de experimentos

**Paralelos (no-Google):**
1. **E0 – Dataset (1 día)**: grabar a la vez mic + referencia PCM exacta del TTS sobre el JBL (habla sola, eco solo, doble habla). Evalúa todas las opciones de eco offline.
2. **E1 – Delay BT**: chirp + GCC-PHAT 10 min vs `AudioTrack.getTimestamp`. Prueba si el delay es predecible ±50 ms y si el timestamp incluye la latencia BT.
3. **E2 – AEC3 offline en PC** (`webrtc-aec3-kmp` tiene artefacto JVM [V]) sobre E0, con/sin pre-alineación; ERLE y residual/voz. Si sale bien → APK de prueba.
4. **E3 – Gate por correlación offline**: falsos rechazos en doble habla.
5. **E4 – Embedding de hablante** (sherpa-onnx): EER usuario vs Kokoro + latencia en teléfono.
6. **E5 – Half-duplex + barge-in** como fallback seguro.
7. **E6 – Hardware**: altavoz USB-C cableado + speakerphone UAC prestado.
8. **L1 – Moonshine es Small/Tiny**: PC (FLEURS-es + grabaciones con acento latino) → APK de prueba; tiempo a texto final, estabilidad de parciales, CPU con Gemma cargada.
9. **L2 – Bake-off TTS en teléfono**: Kokoro int8 con primer chunk de 5–8 palabras vs Supertonic 3 vs Piper vs Inflect-Nano; TTFA con 6 palabras, RTF, valoración subjetiva.
10. **L3 – Opus-MT es-en ONNX**: latencia + BLEU en nuestras frases.

**Stack Google (tras el release con #3749):**
11. **G1**: Gemma 4 E4B AST con audio streaming + encoder GPU; primer audio y OOM.
12. **G2**: Gemma 4 texto como MT en la cascada (Moonshine → Gemma texto → TTS) vs G1.
13. **G3 (opcional)**: ML Kit GenAI Speech Básico es-ES (Avanzado no disponible en Xiaomi).

## 5. Riesgos / incógnitas
- Moonshine es: entrenado con pseudo-etiquetas tipo Whisper, evaluado solo en habla leída europea (FLEURS/MLS), no espontánea latinoamericana; posibles bucles de repetición en clips cortos [V].
- `webrtc-aec3-kmp`: un solo autor, muy reciente, requiere Kotlin 2.4+ [V]. Plan B: compilar `audio_processing` de libwebrtc.
- Saltos de delay BT → fugas durante re-convergencia [V]: hacen falta los gates además de AEC3.
- Memoria: Moonshine (~120 MB) + embeddings + TTS nuevo junto a Gemma E4B en 12 GB, con los OOM previos en 0.15.
- Supertonic 3: OpenRAIL-M con restricciones de uso; números en x86.
- Sin verificar: si `getTimestamp` incluye la latencia A2DP, UAC de speakerphones con Xiaomi, cifras Opus-MT / ML Kit Translate, ExecuTorch MediaTek.
- Posiblemente desactualizado (>6 meses): DTLN-aec (2022), DeepVQE (2023), EchoFree (ago-2025), Personal VAD, paper Moonshine v2 (feb-2026, solo EN), Hibiki-Zero (feb-2026).

## Fuentes
- LiteRT-LM releases: https://github.com/google-ai-edge/LiteRT-LM/releases · PR #3749: https://github.com/google-ai-edge/LiteRT-LM/pull/3749
- LiteRT MediaTek NPU: https://developers.google.com/edge/litert/next/mediatek · LiteRT-LM NPU: https://developers.google.com/edge/litert/next/litert_lm_npu
- webrtc-aec3-kmp: https://github.com/Enaium/webrtc-aec3-kmp · https://klibs.io/project/Enaium/webrtc-aec3-kmp
- Chromium AEC loopback: https://chromium.googlesource.com/chromium/src/media/+/master/base/media_switches.cc
- AEC3 delay config: https://www.fanyamin.com/webrtc/tutorial/build/html/3.audio/audio_aec.html
- AEC sobre Bluetooth (Fora Soft): https://www.forasoft.com/learn/audio-for-video/articles-audio/aec-on-bluetooth-speakerphone-airpods
- Android CDD audio latency: https://android.googlesource.com/platform/compatibility/cdd/+/refs/heads/master/5_multimedia/5_6_audio-latency.md · Oboe #80: https://github.com/google/oboe/issues/80
- Speex tail length: https://stackoverflow.com/questions/12748277/speex-echo-cancellation-configuration
- DTLN-aec: https://github.com/breizhn/DTLN-aec · DeepVQE: https://github.com/Xiaobin-Rong/deepvqe · https://www.isca-archive.org/interspeech_2023/ristea23_interspeech.html · EchoFree: https://arxiv.org/html/2508.06271v1 · Personal VAD: https://arxiv.org/html/1908.04284v5
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx · https://k2-fsa.github.io/sherpa/onnx/speaker-identification/apk.html
- Moonshine: https://github.com/moonshine-ai/moonshine · https://moonshine-voice.readthedocs.io/en/latest/models/available-models/ · https://huggingface.co/moonshine-ai/moonshine-streaming-small-es · https://arxiv.org/html/2602.12241v1
- Hibiki-Zero: https://kyutai.org/blog/2026-02-12-hibiki-zero/ · Canary-1B-v2: https://huggingface.co/nvidia/canary-1b-v2 · Voxtral Realtime: https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602
- ML Kit GenAI Speech: https://developers.google.com/ml-kit/genai/speech-recognition/android
- TranslateGemma: https://blog.google/innovation-and-ai/technology/developers-tools/translategemma/ · https://huggingface.co/litert-community/TranslateGemma-4B-IT
- TTS benchmarks: https://picovoice.ai/blog/on-device-tts/ · https://heyneo.com/blog/kokoro-supertonic-inflect-nano-pocket-tts-cpu-benchmark
- Supertonic 3: https://huggingface.co/Supertone/supertonic-3 · Kokoro LiteRT: https://huggingface.co/litert-community/Kokoro-82M · StreamSpeech: https://github.com/ictnlp/StreamSpeech

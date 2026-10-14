# Research: TTS más veloz y audio limpio (14 octubre 2026)

Pedido de Abraham tras la ronda r14: la mejora ahora está del lado de la voz de
salida (Kokoro, velocidad) y de que no entre ruido/risa/muletilla/eco a la
traducción. Marcas: [V] verificado · [I] inferido · (>6m) fuente antigua.

## A. Voz de salida más rápida

| Motor | Tamaño | Licencia | Velocidad | Naturalidad | Streaming | Android |
|---|---|---|---|---|---|---|
| Kokoro-82M (actual) | 82M | Apache-2.0 | RTF 0.42 (medido) | referencia | no | ORT (actual) |
| **Pocket TTS** (Kyutai) | 100M | código MIT, pesos CC-BY-4.0 (gated; licencia por voz) [V] | ~200 ms al 1.er audio, 6× real en M4; usa 2 núcleos [V] | comparable a Kokoro [I] | **sí, tramas de 80 ms** [V] | sherpa-onnx, ONNX INT8, port LiteRT [V] |
| Supertonic 2/3 | 66–99M | MIT + OpenRAIL-M; **repo archivado 9-sep-2026** [V] | RTF 0.012 en M4 Pro; 0.3 en un e-reader; móvil ~0.05–0.15 [I] | buena, pero **omite palabras en frases cortas** [V] | no (no lo necesita) | ORT / sherpa-onnx [V] |
| Kitten TTS 0.8 nano/mini | 15–80M | Apache-2.0 (nano) [V] | ~0.03–0.1 [I] | menor que Kokoro [I] | por fragmentos | sherpa-onnx [V] |
| Piper (VITS) | 15–30M | **GPL-3.0** (piper1-gpl + espeak-ng) [V] | 5–8× real | claramente menor | por frase | sherpa-onnx |
| TTS del sistema Android | — | gratis | 100–300 ms [I] | robótica vs Kokoro [I] | sí | TextToSpeech |

- La app usa CMUdict (no espeak-ng/GPL) para fonemas: sin riesgo de licencia hoy.
- Acelerar Kokoro con NPU/GPU MediaTek: sin EP público en ORT; LiteRT+NeuroPilot
  sí soporta Dimensity, pero el port Kokoro→LiteRT es preview FP32/CPU (RTF 1.8
  en Pixel 8a) [V] → mucho trabajo, resultado incierto.
- "Streaming" de Kokoro por fragmentos: 1.er fragmento corto y sintetizar el
  siguiente mientras suena → 1.er audio ~0.6–0.8 s sin importar el largo [I].
  **Ojo:** ya se probó un corte por cláusulas el 13-oct y Abraham lo sintió
  entrecortado; sólo vale si el corte cae en pausas naturales.

Bake-off propuesto (20 frases reales de 3–30 palabras; tiempo a 1.er PCM, RTF,
CPU, interferencia con ASR/MT, temperatura a 10 min; A/B ciego 1–5 con Abraham):
1. **Pocket TTS** (streaming real, voz comparable) — candidato principal.
2. Supertonic 2 (muy rápido; riesgos: archivado, OpenRAIL, frases cortas).
3. Kitten mini como reserva ultrarrápida.

## B. Audio limpio (risas, muletillas, tos, residuo de eco)

- **El VAD no es la capa correcta**: risa y "mmm" son voz para cualquier VAD;
  ninguno publica rechazo de risas [I]. Picovoice Cobra exige AccessKey.
- **Clasificador de eventos de audio** por ventana de 0.5–1 s:
  YAMNet (MediaPipe `tasks-audio`, 521 clases incl. Speech/Laughter/Cough/Hum,
  ~12 ms/ventana en Pixel 6, Apache-2.0) [V]; EfficientAT mn04 (0.98M,
  ONNX con nuestro ORT) [V/I]; CED-tiny (5.5M, longitud variable) [V].
  La detección de risa empeora con ruido de fondo → calibrar con audio propio.
- **Moonshine no da confianza real**: `WordTiming.confidence` existe pero el
  core la fija en 1.0 [V]. Sí sirven los tiempos por palabra
  (`word_timestamps=true`) y `max_tokens_per_second` (6.5) contra bucles [V].
- **Señales de texto baratas**: palabras fuera de vocabulario contra un
  diccionario es-MX ("Homer Sote", "mi hocus" → 50–100 %; español real < 20 %)
  — probablemente la mejor; sílabas repetidas (ja ja ja); palabras/s fuera de
  1–5; líneas de 1–2 tokens < 0.6 s.
- **Muletillas es-MX**: eh, ehh, em, mmm, este, o sea, pues, bueno, a ver, ajá,
  mhm, ah, digo… Regla: línea sólo de muletillas → se descarta; mixta → se
  quitan y se traduce el resto (no se pierde contenido).
- **No pasar audio "denoised" al ASR**: el realce de voz empeora el WER
  (MetricGAN+: 8.8 → 25.8 % semWER, arXiv 2512.17562; SAM-Audio, 2603.04710) [V].
  GTCRN/RNNoise sólo como señal lateral (SNR / prob. de voz). AGC fijo.

Diseño en capas propuesto: AEC3 → VAD → RMS de chunk (actual) → clasificador
de eventos por ventana → Moonshine con tiempos por palabra → compuerta por línea
que descarta sólo si **≥ 2 señales** coinciden (risa/tos > habla en el clasificador,
OOV ≥ 0.5, sólo muletillas/sílabas repetidas, w/s fuera de rango, RMS bajo) y
**nunca** si P(habla) ≥ 0.6 y OOV < 0.3 → quitar muletillas → traducir. Ante la
duda, traducir de más; registrar todo lo descartado.

Evaluación offline: 300–500 chunks etiquetados (habla / risa / muletilla /
residuo de eco / ruido); meta **recall de habla ≥ 99.5 %** y maximizar rechazo.

## Fuentes
github.com/kyutai-labs/pocket-tts · huggingface.co/kyutai/pocket-tts ·
huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8 · github.com/supertone-oss-archive/supertonic ·
huggingface.co/Supertone/supertonic-2 · speechcentral.net/2026/05/23/supertonic-tts-android-… ·
github.com/KittenML/KittenTTS · k2-fsa.github.io/sherpa/onnx/tts/index.html ·
github.com/k2-fsa/sherpa-onnx/discussions/3968 · huggingface.co/litert-community/Kokoro-82M ·
developers.google.com/edge/litert/next/mediatek · soniqo.audio/benchmarks/android ·
developers.google.com/edge/mediapipe/solutions/audio/audio_classifier/android ·
github.com/tensorflow/models/…/yamnet · github.com/fschmid56/EfficientAT · github.com/richermans/ced ·
isca-archive.org/interspeech_2021/gillick21_interspeech.html · github.com/TEN-framework/ten-vad ·
github.com/moonshine-ai/moonshine (core/word-alignment.cpp) · moonshine-voice.readthedocs.io/en/latest/api/options/ ·
arxiv.org/pdf/2512.17562 · arxiv.org/abs/2603.04710 · github.com/biglinux/gtcrn

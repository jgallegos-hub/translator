# Research: alternativas locales a Gemma (13 octubre 2026)

Pregunta (Abraham): Gemma 4 E4B se comporta como LLM (alucina en audio corto,
ignora instrucciones condicionales, meta-texto, slang). ¿Hay algo local en el
teléfono, no-LLM, para traducir? Google ofrecía un paquete específico.
Marcas: [V] verificado en fuente primaria · [I] inferido · (>6m) fuente antigua.

## Resumen ejecutivo
- **En parte usamos la herramienta equivocada:** Gemma hace ASR + traducción +
  seguir instrucciones a la vez; los fallos son típicos de LLM generativo y no
  acepta audio en streaming.
- **Lo que Abraham recuerda = ML Kit Translation API**: NMT on-device (mismos
  modelos que Google Translate offline), ~30 MB/idioma, Kotlin, gratis con
  atribución a Google [V]. TranslateGemma SÍ es LLM (Gemma 3, sólo texto) [V].
  Gemini 3.5 Live Translate es voz→voz pero en la nube [V].
- **Recomendación: cascada ASR streaming → MT neuronal clásica → Kokoro**, Gemma
  fuera de la ruta crítica. Primer audio estimado ~0.8–1.4 s tras fin de frase
  (hoy 2.5–3.5 s) [I], y un NMT no inventa frases genéricas.
- Top 3: (1) Moonshine Streaming Small-es (MIT) + ML Kit Translate; (2) Moonshine
  + Bergamot/Firefox Translations es-en (31M params, 25 MB, ~5–20 ms/frase ARM,
  COMET 85.7); (3) ASR segmentado por VAD como segunda pasada (Parakeet-TDT-0.6B-v3
  vía sherpa-onnx, o Canary-180M-Flash, que también hace es→en directo).

## Opciones Google no-LLM
- **ML Kit Translation**: 50+ idiomas, offline tras descarga, "traducciones
  casuales" (sin métricas publicadas), permitido en smartphones, exige atribución,
  requiere Play Services [V].
- **SpeechRecognizer on-device** (API 31+): depende del fabricante; en Xiaomi no
  garantizado es-MX/es-US; verificar con `checkRecognitionSupport()`; toma el
  micrófono (choca con Oboe+AEC3) [I].
- **ML Kit GenAI Speech Recognition basic**: streaming, acepta PCM propio, pero
  sólo **es-ES beta**; Advanced sólo Pixel 10/11 [V].
- Pixel 10 Voice Translate on-device: sin API para terceros [V/I].

## ASR streaming (español, Android)
| Modelo | Tamaño/licencia | Precisión es | Latencia | Alucinación | Android |
|---|---|---|---|---|---|
| **Moonshine Streaming Small-es** | 123M, MIT [V] | WER 4.9 % (habla leída; no medido en habla mexicana espontánea) | 148 ms en M3 vs 1 940 ms Whisper Small [V] | puede hacer bucles en clips cortos [V] | `ai.moonshine:moonshine-voice` (0.1.x) [V] |
| Moonshine Tiny-es | 34M, MIT | WER 6.2 % | ~50 ms en M3 | igual | igual |
| sherpa-onnx Zipformer "Kroko" es | licencia sin verificar | buena (reporte) | ~1.5 s 1.er token (reporte jul-2026) | transducer, baja [I] | Kotlin `OnlineRecognizer` |
| Parakeet-TDT-0.6B-v3 | 600M, CC-BY-4.0 | alta [I] | no streaming; RTF 0.22 / 1 núcleo A76 (v2 EN) | baja [I] | sherpa-onnx |
| Canary-180M-Flash | 182M, CC-BY-4.0 (>6m) | WER es MLS 3.17 | no streaming | baja | ONNX vía sherpa-onnx [I] |
| Whisper | 39M–1.5B, MIT | buena | TTFT alto | **la peor** (1–1.4 % segmentos; 17 % con silencio) (>6m) | whisper.cpp |
| Vosk small-es 0.42 | 39 MB, Apache | WER 16 | streaming real | muy baja | AAR |

## MT texto es→en on-device
| Motor | Tamaño | Calidad | Latencia ARM | Licencia |
|---|---|---|---|---|
| **Bergamot/Firefox es-en** | 31M, 25 MB | COMET 85.72 / BLEU 26.85 (FLORES) [V] | ~5–20 ms/frase (>6m) | MPL-2.0 [I]; port Android JNI existe |
| **ML Kit Translate** | ~30 MB | "casual", sin métricas | decenas de ms [I] | gratis + atribución |
| Opus-MT es-en | ~300 MB fp32 | similar o algo menor [I] | 50–150 ms [I] | CC-BY-4.0 |
| NLLB-200-600M | 2.5 GB | buena | lenta | **CC-BY-NC** ❌ |
| TranslateGemma-4B | ~2.5 GB int4 [I] | mejor con slang [I] | 0.5–1.5 s [I] | Gemma; es LLM → sólo fallback |

Streaming: ningún NMT pequeño es wait-k; usar **re-traducción** del parcial y
hablar sólo el prefijo estable (cierre de cláusula o pausa 250–400 ms).

## End-to-end
SeamlessStreaming / unity-small: **CC-BY-NC** ❌. StreamSpeech: investigación.
Canary-180M-Flash S2TT es→en: COMET 81.5 en FLEURS (< ASR+Bergamot 85.7) [I].
Paper 2026 OfflineRealtimeTranslator (Vosk + Gemma 3 4B): 1–3 s por segmento,
lo que confirma el techo de un LLM de 4B.

## Traductores de bolsillo
Timekettle T1: offline "AI edge model", 44 pares, "0.2 s" (marketing) [V];
Pocketalk: nube multi-engine, offline básico; Vasco: fuentes en conflicto.
El mercado es mayormente nube + NMT pequeño offline de respaldo → una app de
teléfono 100 % offline, con voz elegible y ~1 s tras cada frase sería
competitiva [I].

## Arquitectura recomendada
Oboe → AEC3 → Silero VAD → **Moonshine Small-es streaming** (PCM post-AEC) →
segmentador (line_completed / pausa ≥ 300 ms / puntuación; chunker 12 s como
tope) → **normalizador de modismos mexicanos** (neta, chamba, qué onda, güey,
padre, ahorita…) → **ML Kit Translate** (luego Bergamot) → filtros
deterministas (ASR < 2 tokens, vacío, repetido; LID para no-español) → Kokoro
por cláusulas.

Latencia estimada tras fin de frase [I]: VAD 0.3 + ASR 0.15–0.4 + MT 0.01–0.05
+ Kokoro 1.er trozo 0.3–0.6 ≈ **0.8–1.4 s**. Memoria: Moonshine 150–300 MB,
Bergamot ~100 MB, ML Kit 50–100 MB; se liberan varios GB de Gemma.
Esfuerzo: ML Kit horas; Moonshine AAR 1–3 días; Bergamot JNI 3–5 días;
sherpa-onnx 1–2 días. Gemma: sólo texto, opcional, fuera de la ruta crítica
("slang repair").

## Plan de experimentos
1. Corpus: 80–150 grabaciones reales post-AEC (cortas, 1–3 palabras, slang,
   largas, inglés, ruido/silencio) con transcripción y traducción de referencia.
2. ASR en PC: Moonshine Small/Tiny-es vs Parakeet v3, Kroko, Whisper-small →
   WER en habla mexicana, alucinaciones/bucles, latencia streaming simulada.
3. MT en PC: Bergamot, Opus-MT, TranslateGemma-4B, Gemma E4B solo texto →
   COMET + juicio humano en slang (ML Kit sólo se prueba en el teléfono).
4. Simulación de tiempos en PC (WAV en tiempo real → cascada).
5. Dispositivo A: Moonshine + ML Kit detrás de un flag A/B.
6. Dispositivo B: Bergamot si gana; re-traducción incremental; LID.
7. Opcional: Parakeet como 2.ª pasada; Gemma texto como "slang repair".

## Riesgos
Moonshine-es no medido en habla mexicana espontánea y con bucles en clips
cortos; Android de Moonshine 0.1.x sin verificar con PCM propio; ML Kit GenAI
sólo es-ES; un NMT pequeño traduce el slang literal; ML Kit exige atribución a
Google; licencias Mozilla/Kroko por verificar; con el LLM fuera, Kokoro pasa a
ser lo más lento; la cascada necesita LID explícito.

## Fuentes
- developers.google.com/ml-kit/language/translation (+ /android, /translation-terms)
- developers.google.com/ml-kit/genai/speech-recognition/android
- developer.android.com/reference/android/speech/SpeechRecognizer
- blog.google/innovation-and-ai/models-and-research/gemini-models/gemini-live-3-5-translate/
- research.google/blog/real-time-speech-to-speech-translation/
- blog.google/innovation-and-ai/technology/developers-tools/translategemma/
- huggingface.co/moonshine-ai/moonshine-streaming-small-es
- moonshine-voice.readthedocs.io (available-models, accuracy, quickstart) · github.com/moonshine-ai/moonshine
- arxiv.org/abs/2602.12241 · github.com/k2-fsa/sherpa-onnx/discussions/3715
- huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 · huggingface.co/nvidia/canary-180m-flash
- alphacephei.com/vosk/models · dl.acm.org/doi/fullHtml/10.1145/3630106.3658996 · arxiv.org/html/2501.11378v1
- mozilla.github.io/translations/firefox-models/ · github.com/mozilla/firefox-translations-models
- blog.davidv.dev/posts/mobile-translator/ · picovoice.ai/blog/open-source-translation/
- github.com/facebookresearch/seamless_communication · github.com/ictnlp/StreamSpeech
- sciencedirect.com/science/article/pii/S2352711026002566
- timekettle.co (comparativa pocket translators) · vasco-translator.com

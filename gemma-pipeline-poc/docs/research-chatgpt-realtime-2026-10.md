# Research: cómo logra OpenAI la traducción de voz en tiempo real (16 octubre 2026)

Pedido de Abraham: ver cómo lo logra el traductor en tiempo real de ChatGPT y qué
nos sirve. Marcas: [V] fuente primaria · [I] secundaria/inferido · (>6m) antigua.

## OpenAI
- **`gpt-realtime-translate`** (7-may-2026), endpoint `/v1/realtime/translations` [V]:
  modelo voz→voz dedicado, **sin turnos**: el cliente manda audio continuo (incluido
  silencio) y recibe audio traducido + deltas de transcripción mientras se habla.
  Entrenado con audio de intérpretes; **espera "suficiente contexto"** antes de hablar
  (simultánea, no frase a frase). Salida en trozos de 200 ms PCM16 24 kHz; voz que imita
  al hablante; `noise_reduction: near_field`; **sin prompt ni glosario**. es→en incluido.
  **US$0.034/min** (~US$2/h). WebSocket desde móvil/servidor, WebRTC en navegador.
- **GPT-Live** (ChatGPT, 8-jul-2026): full-duplex, decide muchas veces por segundo si
  hablar/escuchar/callar; OpenAI admite que el Advanced Voice Mode anterior cortaba por
  silencio y se interrumpía con ruido [V]. En ChatGPT la traducción es por prompt [I].
- **Latencia: OpenAI no publica cifras** [V]. Medición de un competidor (LiveLingo, sep-2026):
  retraso es/en mediana **2.65 s** con `gpt-realtime-translate`, 11.7 s con GPT-Live por
  prompt [I]; Artificial Analysis: 1.34 s al primer audio en GPT-Live-1 [I].
- Realtime API: `server_vad` (ej. silencio 500 ms, prefijo 300 ms), **`semantic_vad`**
  (clasificador sobre las palabras para saber si terminó, con "eagerness") e
  `interrupt_response` [V].

## Comparables (técnica)
- Gemini 3.5 Live Translate (jun-2026): audio→audio continuo, "unos segundos" detrás,
  conserva entonación [V]; 1.51 s en la misma medición [I].
- Hibiki-Zero (Kyutai, >6m): 5.6 s promedio [V]. Apple Live Translation (>6m): on-device,
  ~5 s reportados [I].
- Patrón: todos aceptan **segundos** de retraso a cambio de no esperar el fin de frase.
  **Nuestro primer audio p50 ~1.3 s por frase ya es competitivo**; perdemos en habla
  continua larga (se acumula) [I].

## Aplicable a nuestra app (impacto × factibilidad)
1. **TTS en streaming por cláusulas** (OpenAI emite 200 ms a la vez): primer audio
   depende sólo de la 1.ª cláusula. Ojo: el corte por cláusulas del 13-oct se sintió
   entrecortado → sólo en pausas naturales / con reproducción encadenada sin hueco.
2. **MT en streaming → TTS**: consumir tokens de Haiku/Gemma y mandar al TTS al primer
   signo de puntuación.
3. **Fin de turno semántico**: silencio más corto (300–500 ms) + heurística sobre el texto
   parcial de Moonshine (termina en "que", "y", "pero", "este"… → esperar).
4. Traducción parcial especulativa (wait-k): primero sólo en subtítulos.
5. Ruido: supresión near-field sólo como señal lateral (no al ASR, ver research-tts-ruido).
6. **Probar `gpt-realtime-translate`** como modo en línea alternativo (sin glosario ni
   control de voz; ~US$2/h) y medir contra la cascada + Haiku.
7. Interrupciones half-duplex: bajar/truncar la voz si el interlocutor habla.
8. No prioritario: WebRTC (sólo nube), Hibiki-Zero.

## Fuentes
openai.com/index/advancing-voice-intelligence-with-new-models-in-the-api/ ·
developers.openai.com/api/docs/guides/realtime-translation ·
developers.openai.com/api/docs/models/gpt-realtime-translate ·
developers.openai.com/cookbook/examples/voice_solutions/realtime_translation_guide ·
developers.openai.com/api/docs/guides/realtime-vad · openai.com/index/introducing-gpt-live/ ·
learn.microsoft.com/en-us/azure/foundry/openai/concepts/gpt-realtime-translate ·
livelingo.io/guides/gpt-live-translation (competidor) ·
blog.google/innovation-and-ai/models-and-research/gemini-models/gemini-live-3-5-translate/ ·
kyutai.org/blog/2026-02-12-hibiki-zero/ · support.apple.com/en-us/123185

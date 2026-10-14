# Plan general: cascada ASR → MT (14 octubre 2026)

Base: `docs/research-alternativas-gemma-2026-10.md`. Decisión de Abraham (13-oct):
explorar la cascada; con Gemma ya no se puede pulir más. Este documento es el
plan para arrancar. **Nada se implementa hasta que Abraham lo revise.**

## Qué se queda y qué cambia

```
Oboe → AEC3 → Silero VAD → AudioChunker (12 s, corte inteligente)
        │                              │
        │                 hoy:  Gemma 4 E4B (audio → inglés)
        │                 nuevo: ASR español → [normalizador] → MT es→en
        ▼                              ▼
   (sin cambios)        AstChunkRouter (filtros eco/meta/RMS) → Kokoro fp16 → BT
```

- **Se queda:** captura, AEC3, keep-alive, VAD, chunker, filtros de eco, Kokoro,
  UI, herramientas de `tools/`.
- **Cambia:** sólo el bloque "chunk de audio → texto en inglés", detrás de una
  interfaz nueva `SpeechTranslator` con dos implementaciones, `GemmaTranslator`
  (lo actual) y `CascadeTranslator`, elegibles con un flag/toggle en la UI. Gemma
  sigue disponible para comparar A/B en el teléfono.
- **Criterio de selección (Abraham, 14-oct):** lo único obligatorio es que el
  ASR reconozca bien el habla en español mexicano. Da igual si el modelo es
  sólo-español o multilingüe; gana el que funcione mejor en el corpus.
- **Regla de producto:** no se descarta contenido. La cascada traduce todo lo
  que el ASR transcriba.

## Métricas y metas (iguales para todas las opciones)

| Métrica | Hoy (Gemma) | Meta cascada |
|---|---|---|
| Primer audio tras fin de frase (aislada) | 2.3–3.5 s | **≤ 1.5 s p50** |
| WER del ASR en habla mexicana real | n/a (Gemma no transcribe) | **≤ 10 %** |
| Frases inventadas (alucinación) en chunks cortos/silencio | ocurre ("I'm going to the store.") | **0** en el corpus |
| Calidad de traducción (juicio de Abraham 1–5) | referencia | **≥ igual a Gemma** |
| Slang (neta, chamba, güey, ahorita…) | parcial | ≥ igual a Gemma |
| RAM | varios GB (E4B) | < 1 GB |

## Fase 0: corpus y línea base (fundamento de todo; 1 sesión)

1. **Reusar lo ya grabado:** `tools/datasets/2026100*/aec_out.wav` y
   `chunks_smart/` (chunks reales post-AEC) + las traducciones de Gemma en los
   logs `r0…r11` = línea base gratis.
2. **Grabar lo que falta** (Abraham habla, el teléfono graba con el
   EchoDatasetRecorder): ~80–150 frases en 6 grupos, guiadas por una lista que
   yo preparo: cortas (1–3 palabras), medianas, largas (>8 s), slang
   mexicano, frases en inglés/mezcla, silencio/ruido.
3. **Referencias:** yo genero un borrador de transcripción y traducción con un
   ASR grande en PC; **Abraham sólo corrige** (no escribe desde cero).
4. Herramienta `tools/cascade_bench.py`: corre cualquier ASR/MT sobre el corpus
   y saca WER, alucinaciones, latencia y una tabla para el juicio humano.

**Salida:** corpus versionado (fuera de git, como los logs) + tabla de Gemma
como línea base.

## Opción 1 (la más factible): Moonshine Small-es + ML Kit Translate

**Por qué primero:** las dos piezas tienen AAR/dependencia Kotlin oficial
(`ai.moonshine:moonshine-voice:0.1.5`, `com.google.mlkit:translate`), licencia
MIT / gratis con atribución, y el corte por chunks ya existe.

| Paso | Dónde | Esfuerzo | Criterio para avanzar |
|---|---|---|---|
| 1a. Moonshine Small-es y Tiny-es sobre el corpus (`pip install moonshine-voice`) | PC | ½ sesión | WER ≤ 10 %, sin bucles en chunks cortos |
| 1b. Spike Android: ¿Moonshine acepta **nuestro PCM** post-AEC (no su propio micrófono)? ¿cómo se elige el modelo en español? | teléfono | ½–1 sesión | Transcribe un WAV del corpus en el teléfono |
| 1c. ML Kit Translate es→en (descarga del modelo ~30 MB, una vez) | teléfono | horas | Traduce la lista del corpus; tiempo < 100 ms |
| 1d. `SpeechTranslator` + `CascadeTranslator` **por chunk** (chunk completo → ASR → MT → router) + toggle Gemma/Cascada | teléfono | 1 sesión | Tests pasan; mismo flujo de UI |
| 1e. A/B en vivo, misma sesión de habla con cada motor | teléfono | 1 sesión | Metas de la tabla |

**Riesgos:**
- La API Android de Moonshine 0.1.x documenta sólo `MicTranscriber` (su propio
  micrófono). Si no acepta PCM externo, chocaría con Oboe+AEC3. Plan B: usar
  sus modelos ONNX directamente con onnxruntime (ya lo usamos para Kokoro y
  Silero) o usar sherpa-onnx.
- Moonshine-es no está medido en habla mexicana espontánea (de eso se encarga
  1a).
- ML Kit traduce el slang de forma literal (eso se resuelve en la Opción 2b).

**Mejora posterior (1f):** streaming real. Mientras hablas, Moonshine emite
texto parcial y se traduce al cerrar cada línea (`onLine`), sin esperar el fin
del chunk. Ahí se gana el último medio segundo.

## Opción 2 (intermedia): MT propia — Bergamot es-en + normalizador + LID

Entra sólo si ML Kit queda corto en calidad o en slang, o si su atribución o
Play Services estorban.

- **2a. Bergamot/Firefox es-en** (31M, COMET 85.7). Compilar el port JNI para
  arm64 (3–5 días) y evaluar antes en PC contra ML Kit y Opus-MT con el mismo
  corpus. Sin dependencia de Google; licencia MPL a verificar.
- **2b. Normalizador de modismos mexicanos.** Diccionario editable (neta→"en
  serio", chamba→"trabajo", qué onda→"qué tal", ahorita, güey…) aplicado al
  texto del ASR antes del MT. Sirve para cualquier MT y es barato (½ sesión);
  podría adelantarse a la Opción 1 si el slang sale mal.
- **2c. LID** (detección de idioma del texto): si el ASR devuelve inglés, no se
  traduce de nuevo. Reemplaza al SKIP que nunca funcionó con Gemma.
- **2d. TranslateGemma-4B como MT "premium" opcional.** Es un LLM y sólo
  acepta texto. Se mide en PC con el corpus de slang; en el teléfono, sólo si
  gana claro y cabe en latencia (~0.5–1.5 s/frase).

## Opción 3 (la más compleja, pero posible): ASR más pesado segmentado

Entra si el WER de Moonshine no alcanza la meta en habla real.

- **Parakeet-TDT-0.6B-v3** (CC-BY-4.0) o **Canary-180M-Flash** vía
  **sherpa-onnx** (`OfflineRecognizer` en Kotlin). No son streaming: procesan
  el chunk entero del VAD. Por eso encajan con nuestro chunker tal cual.
- Uso posible: (a) reemplazo directo de Moonshine, o (b) segunda pasada.
  Moonshine da el texto rápido y Parakeet lo confirma. Ojo con la regla de no
  descartar: la corrección sólo sustituye antes de hablar, nunca lo que ya se
  dijo.
- Canary también hace es→en directo (COMET 81.5 < cascada 85.7). Queda como
  dato, no como objetivo.
- Esfuerzo: integrar sherpa-onnx 1–2 días + medir RTF en Dimensity 9400+.

## Orden propuesto y puertas de decisión

```
F0 corpus ─► 1a PC ─► 1b spike ─► 1c ML Kit ─► 1d integración ─► 1e A/B
                │ WER malo                        │ calidad/slang mala
                ▼                                 ▼
            Opción 3 (Parakeet/Canary)        2b normalizador → 2a Bergamot → 2d TranslateGemma
```

- Después de 1e, si la cascada cumple las metas, pasa a ser el motor por
  defecto. Gemma queda como opción (o se retira para liberar RAM).
- 1f (streaming) y 2c (LID) son mejoras que se suman encima de lo que gane.

## Lo que necesito de Abraham en cada fase

- F0: ~20–30 min hablando con la lista de frases + revisar las transcripciones
  y traducciones borrador.
- 1b/1c: permiso para descargar los modelos (Moonshine-es en PC y teléfono;
  ML Kit es-en en el teléfono).
- 1e y siguientes: sesiones de habla como hasta ahora + juicio 1–5 de las
  traducciones.

## Resultados F0 (14-oct, sesión `20261014_103127`, 444 s, 84 chunks smart2)

Corpus: guion F0 leído con la app en vivo (Gemma + Kokoro + JBL, AEC3 ON).
ASR medido en PC sobre los mismos chunks que vio Gemma (`tools/chunker_sim.py`
smart2), frases 1–74 contra el guion (`cascade_bench.py wer … 75`).

| Motor | WER total | sólo sust.+omis. | inserciones | RTF PC |
|---|---|---|---|---|
| Moonshine Small-es | 18.3 % | 7.6 % | 61 palabras | 0.104 |
| Moonshine Tiny-es | **15.1 %** | 7.6 % | 43 palabras | **0.043** |

- Las palabras que sí dijo Abraham se reconocen ~92 %. Casi todo el error son
  **inserciones**: restos de la voz de Kokoro tras AEC3 dentro de los chunks
  (p. ej. "no se puede disimular", "El nombre de la película es Socorro"), que
  Moonshine transcribe como español inventado. Gemma, en cambio, los ignoró casi
  siempre (sólo un "I'm sorry." espurio).
- Silencios: 0 inventos en ambos (vacío). Tiny repite ("sí sí sí", "ja ja ja")
  en algunos chunks cortos.
- Frases en inglés (sección E): Moonshine-es las destroza ("tenkevermash",
  "skies me where is a battle"). Lugares: "neibi pir", "minbeniam park".
  Préstamos sueltos (check-in, link, WhatsApp, parking, snacks) bien.
- **Línea base Gemma en la misma sesión:** traducción buena en frases normales y
  largas; primer token ~1.13 s (2.2 s en largas), total 1.5–2.5 s (5–7 s largas).
  Fallos: slang ("Mande"→"Good morning"/"Okay, man", "órale vámonos"→"Hello,
  Amonus", "qué oso"→"what a bear", "me cayó el veinte"→"I got the 20", "Nos
  vemos"→"We see"), omitió 2 frases en inglés, "Roosevelt"→"Russell".

Implicaciones para la Opción 1:
1. Tiny-es es tan preciso como Small-es y 2.4× más rápido → candidato principal.
2. Hace falta un filtro contra el residuo de eco en el ASR (transcribir también
   la referencia TTS / gating por actividad de reproducción / umbral de
   confianza), o la cascada traducirá basura que Gemma hoy ignora.
3. `Transcriber.set_keyterms()` para lugares/nombres (Navy Pier, Millennium
   Park, Roosevelt…).
4. Si el inglés del hablante importa, evaluar un ASR multilingüe (Whisper /
   Parakeet v3) en la sección E.

## Resultados 1b: spike Moonshine en el teléfono (14-oct)

- La API Android (`ai.moonshine.voice.Transcriber`) acepta **nuestro PCM**:
  `loadFromFiles(dir, arch)`, `transcribeWithoutStreaming(float[], 16000)`,
  `addAudio`/streams, `setKeyterms`. Ojo: `Transcript.text()` devuelve sólo la
  primera línea; hay que unir `lines`.
- Convivencia de runtimes: el AAR trae su `libonnxruntime.so` 1.23.2 (choca con
  onnxruntime-android). Solución: `onnxruntime-android` → 1.23.2 y AAR re-empacado
  sin su ORT (`tools/vendor_moonshine.py`, arm64). Silero, Kokoro, Gemma y
  Moonshine cargan juntos.
- Velocidad en Xiaomi 15T Pro con Gemma + Kokoro cargados (84 chunks F0):

| Modelo | carga | p50/chunk | p90 | máx (12 s) | RTF | WER |
|---|---|---|---|---|---|---|
| **Tiny-es** | 0.25 s | **83 ms** | 275 ms | 465 ms | **0.030** | **14.6 %** |
| Small-es | 0.54 s | 180 ms | 626 ms | 1 141 ms | 0.066 | 16.5 % |

  (WER igual que en PC; el error sigue siendo de inserciones por residuo de eco.)
- Regresión Kokoro con ORT 1.23.2: fp16-cpu4 RTF 0.44 (igual). int8 pasó de
  0.85 a 0.45.
- Comparado con Gemma (1.er token ~1.13 s, total 1.5–2.5 s), el ASR con Tiny
  cuesta ~0.1 s. **Riesgo 1b cerrado. Siguiente: ML Kit (1c).**

## Resultados 1c: ML Kit Translate en el teléfono (14-oct)

- Modelo es→en descargado en 2.6 s (una vez). Traducción **p50 16 ms, máx 64 ms**
  (74 frases del guion) y p50 17 ms sobre la salida de Moonshine Tiny (84 chunks).
- Frases estándar: correctas pero literales ("bring me the account" por la
  cuenta, "Good evening" por buenas noches, "Forgiveness" por perdón).
- **Slang: muy malo**, peor que Gemma: "¡Qué padre está este lugar!"→"What a
  father is this place!", "No manches"→"Do not stain", "¿Qué onda?"→"What a
  wave", "Mande"→"Send", "me queda chica"→"I have a girl", "Aguas con el
  escalón"→"Waters with the step", "No hay bronca"→"There is no anger".
- Conclusión: velocidad sobrada, calidad insuficiente para habla mexicana real
  sin ayuda. Opciones: normalizador de modismos (2b), o un MT que entienda
  slang (Gemma E4B en modo texto, ya cargado; TranslateGemma).

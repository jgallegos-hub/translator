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

## Resultados: Gemma E4B como MT de texto (14-oct)

Prompt: "Translate this Mexican Spanish speech transcript into natural, idiomatic
English. Translate slang … by their meaning … Reply with the English translation
only." (`mt/GemmaTextBench.kt`). Mismo engine/GPU que la ruta de audio.

| Ruta | 1.er token | total p50 | total p90 |
|---|---|---|---|
| Gemma audio (hoy, sesión F0) | ~1.13 s | 1.5–2.5 s | 5–7 s (largas) |
| Moonshine Tiny → **Gemma texto** | 0.08 + **0.84 s** | 0.08 + **1.65 s** | 3.4–3.6 s |
| Moonshine Tiny → ML Kit | — | 0.08 + 0.02 s | 0.3 s |

- **Calidad: la mejor de las tres.** Slang 20/20 correcto o aceptable ("This
  place is awesome!", "No way, we're already late", "I'm really hungover",
  "ridiculously expensive, how embarrassing", "I finally get it", "What? I
  didn't get that"); cuenta→"check", me queda chica→"too small for me".
- Con texto de ASR malo traduce fielmente la basura ("maybe peer … my Venian
  Park", "I'm going to get lost, boss"): los errores restantes vienen del ASR
  (inglés, lugares, residuo de eco), no del MT.
- El 1.er token ~0.83 s es casi constante (independiente del largo) →
  overhead fijo por conversación/prefill; vale investigar (KV del prompt
  reutilizado, backend, E2B).

## Resultados 1e: A/B en vivo (14-oct, log `r12_cascade.log`, ~1 min por motor)

| Motor | fin de chunk → 1.er audio (p50) | MT | Kokoro 1.ª pieza |
|---|---|---|---|
| Rápido: Moonshine + ML Kit | **1.28 s** | 0.08–0.33 s total | 0.96 s |
| Natural: Moonshine + Gemma texto | 2.19 s | 1.6–2.8 s (1.er token ~0.9 s) | 1.23 s |
| Gemma audio | 3.17 s | 2.1–3.3 s (1.er token 1.1–1.7 s) | 0.97 s |

- Percepción de Abraham: Rápido se siente rápido y natural; Natural un poco más
  lento; Gemma audio ahora se percibe muy lento.
- Gemma audio inventó 2 frases ("Good morning everyone.", "I'm going to the
  street."); las cascadas no inventaron nada. El filtro por línea no tuvo
  residuo que tirar en esta ronda (0 líneas descartadas).
- Errores de ASR en nombres propios (Navy Pier→"neir"/"Negri", Roosevelt→
  "Russell"); Gemma audio sí entendió Navy Pier → probar `setKeyterms`.
- **Nuevo cuello de botella: Kokoro (~1 s antes de sonar).**

## Default + keyterms (14-oct)

- **Default del selector: Rápido (Moonshine + ML Kit)**; Moonshine y ML Kit se
  precargan al abrir la app (Moonshine 0.27 s).
- **Keyterms de Moonshine descartados por ahora.** Lista de Chicago
  (`tools/corpus/keyterms_chicago.txt`). Con `keyterm_boost` por defecto no
  cambian nada; con 4 corrigen Navy Pier / Roosevelt / check-in, pero insertan
  nombres en habla normal ("¿Mande?"→"¿O'Hare mante?", "¿Me echas la mano?"→
  "¿Navy? echa la mano", "Órale"→"O'Luisa") y el WER de Tiny sube 15.1→17.0 %.
  Con ≥ 8 entra en bucle. Alternativa futura: keyterms contextuales (sólo los
  lugares del itinerario del día) o corrección posterior del texto.

## Kokoro como nuevo cuello de botella (14-oct)

- Benchmark de hilos con ORT 1.23.2 (fp16, `kokoro_bench3.log`): 2 thr RTF 0.82,
  4 thr 0.44–0.49, **6 thr 0.42 (nuevo default)**, 8 thr 0.39; int8-cpu6 0.42.
- En vivo, síntesis ≈ 55 ms + 0.50 × duración del audio (43 piezas, r11+r12):
  no hay overhead fijo que recortar; el 1.er audio depende del largo de la 1.ª
  frase. El corte por comas ya se probó el 13-oct (sin mejora del p50 porque
  casi ninguna frase traducida tiene comas, y prosodia entrecortada) → no se
  repite.
- Siguientes palancas, en orden: (1) motor TTS más rápido con voz equivalente
  (bake-off Supertonic / Piper / Kitten, medido igual que Kokoro); (2) cierre
  de frase del VAD (`silenceEndMs` 500 ms), que ahora pesa tanto como el ASR+MT
  juntos; (3) Moonshine en streaming para tener el texto listo al terminar de
  hablar.

## Ronda de validación r13 (14-oct, modo Rápido) y ajustes

Percepción de Abraham: transcribe rápido pero tarda en hablar (algo más rápido
que Gemma); algunas frases terminan en un "ahhh"; las largas del guion bien, las
largas de conversación real no.

Log (`r13_validacion.log`):
- ASR + ML Kit: 0.07–0.9 s. La espera es Kokoro + cola: frases largas sin
  puntuación (Moonshine Tiny casi no puntúa y ML Kit copia eso) se sintetizan
  enteras: 9 s de audio → 5.2–5.5 s de síntesis antes de sonar; con habla
  continua la cola llegó a 7–10 s.
- "ahhh": hipótesis — Kokoro con texto sin puntuación final (muchas salidas de
  ML Kit acababan sin punto) alarga el final.
- Conversación real con ML Kit: literal y torpe ("tampoco hay que clavarnos en
  eso"→"you have to nail us in that", "no mira lo que pasa"→"do not look what
  happens"); ASR con términos técnicos (SharePoint→"cherpoint", CRAC→"crass").
- Residuo de eco: 2 líneas descartadas (1711, 1697), pero pasaron otras de
  1850–2200 durante la reproducción ("Luego, The Fraid Fraid", "mi hocus",
  "Se trata de un cuerpo nocional").

Ajustes (commit siguiente):
1. Toda traducción de la cascada termina en . ! o ? (y con mayúscula inicial).
2. **Modo Auto (nuevo default):** < 10 palabras → ML Kit; ≥ 10 → Gemma texto
   (mejor sentido en habla larga y su puntuación deja a Kokoro empezar por la
   1.ª oración).
3. Umbral de residuo 2400 mientras suena el TTS (1800 si no).

## Ronda r14 (modo Auto) y ajustes de voz (14-oct)

Percepción de Abraham: **mucho mejor**; transcripciones bien en su mayoría,
rápido incluso en frases largas; se nota el tiempo de Kokoro; 2 frases sin
sentido salieron de muletillas intencionales (mmm, ehh, este…); la voz se
percibe muy acelerada (speed 1.2, puesto antes para compensar el retraso de
Gemma).

Log (`r14_auto.log`): fin de chunk → 1.er audio p50 2.6 s, p90 8.5 s (cola).
ML Kit en frases < 10 palabras (0.08–0.36 s); Gemma texto en ≥ 10 (1.er token
0.9–1.4 s, total 2.6–5.2 s) con buena calidad ("I'm really down today", "nobody's
waiting for me anymore"). Una oración de Gemma de 31 palabras = 9.3 s de audio →
5.6 s de síntesis antes de sonar y 12 s de cola para las siguientes.

Ajustes:
1. **Velocidad de voz 1.2 → 1.0** (sólo cambia el ritmo de Kokoro, no la
   traducción; a 1.0 cada frase dura ~20 % más en sintetizar y sonar).
2. **Corte sólo de oraciones muy largas (≥ 20 palabras)** en sus comas (piezas
   ≤ 12 palabras); el resto sigue entero, para no repetir la prosodia
   entrecortada del 13-oct.
3. Pendiente: muletillas que Moonshine convierte en palabras (no se filtran por
   energía; requieren otra señal).

## Siguiente fase propuesta (14-oct): voz de salida + audio limpio + LLM más veloz

Base: `docs/research-tts-ruido-2026-10.md` y `docs/research-s2s-llm-offline-2026-10.md`.
Orden de más factible a más complicado:

1. **Filtro de texto barato (sin modelos nuevos):** quitar muletillas es-MX,
   descartar líneas sólo-muletilla / sílabas repetidas / con ≥ 50 % de palabras
   fuera de vocabulario. Evaluar offline con los chunks ya grabados.
2. **Gemma 4 E2B (+ MTP) como traductor de texto** (TTFT ~0.3 s esperado vs
   0.85–1.4 s): requiere subir LiteRT-LM y descargar ~2.6 GB; validar calidad
   con el corpus F0. Si aguanta, podría ser el único traductor (sin ML Kit).
3. **Bake-off de voz: Pocket TTS** (streaming real, ~0.2 s al 1.er audio)
   contra Kokoro, con A/B ciego de Abraham; Supertonic/Kitten como reserva.
4. **Clasificador de eventos de audio** (YAMNet / mn04) como 2.ª señal para
   risa/tos, con set etiquetado y meta de recall de habla ≥ 99.5 %.
5. Opcional: modo nube (Claude Haiku / Gemini Flash-Lite) con interruptor de
   privacidad; Gemini Nano (Prompt API, el 15T Pro es compatible) a medir.
6. Voz-a-voz end-to-end: no por ahora (licencias NC, lag > 5 s, sin Android).

## Plan para la próxima sesión (acordado 14-oct, cierre)

1. **Banco de Claude Haiku 5.5 en PC** (clave de API ya configurada en la PC de
   Abraham; no se escribe ni se muestra): las 74 frases del guion F0 + salidas
   de Moonshine, en dos variantes — (a) frase suelta; (b) "modo sesión":
   instrucciones fijas cacheadas + glosario de slang/lugares + 2–5 frases
   anteriores como contexto. Medir latencia real desde la red de Abraham y
   calidad vs Gemma texto y ML Kit. Costo estimado < US$0.01.
2. **Investigar el traductor en tiempo real de ChatGPT** (cómo logra baja
   latencia: audio nativo, streaming, interrupciones, voz) y qué se puede
   aplicar a nuestra app.
3. Según resultados: diseñar el modo en línea (backend propio — p. ej. Supabase
   Edge Function con la clave guardada ahí — y respaldo automático al modo Auto
   local sin señal o si tarda > ~1.5 s; aviso de privacidad).
4. Pendientes locales en orden: filtro de muletillas / palabras fuera de
   vocabulario; Gemma 4 E2B + MTP (pedir permiso para ~2.6 GB); bake-off
   Pocket TTS vs Kokoro; clasificador YAMNet para risas.

## Resultados: Claude Haiku 5.5 como MT en línea (16-oct, banco en PC)

`tools/haiku_bench.py` (SDK `anthropic`, `claude-haiku-5-5`, sin pensamiento, effort `low`,
streaming; red de casa de Abraham). Dos variantes: **suelto** (prompt mínimo, el mismo de
GemmaTextBench) y **sesión** (instrucciones de intérprete + glosario es-MX + lugares de
Chicago, cacheados; + 3 frases previas como contexto). CSV en `tools/logs/cascade/haiku_*.csv`.

| Entrada | Variante | 1.er token p50 / p90 | Total p50 / p90 / máx | Costo |
|---|---|---|---|---|
| guion (74) | suelto | 504 / 756 ms | 619 / 827 / 2200 ms | US$0.0038 ambas |
| guion (74) | sesión | 560 / 828 ms | 649 / 845 / 2370 ms | (caché: 96 k tok leídos) |
| Moonshine (84) | sesión | 583 / 719 ms | 700 / 894 / 2241 ms | US$0.0028 |

Referencia (teléfono): ML Kit ~1–10 ms; Gemma E4B texto 0.9–2.4 s total (1.er token ~0.85 s).

**Calidad:**
- Slang: igual o mejor que Gemma y muy por encima de ML Kit ("Me late" → *I really like that
  idea*, "Ya me cayó el veinte" → *Oh, now I get it*, "Mande" → *Sorry, what?*).
- **Corrige errores del ASR con el contexto/glosario** (lo que Gemma y ML Kit no hacen):
  "vamos a neybe peer y luego a mi venian park" → *Navy Pier … Millennium Park* (Gemma: "maybe
  peer … my Venian Park"); "¿por qué la hora es?" → *What time is it?*; Roosevelt → *Roosevelt Road*.
- **Suelto no sirve para la voz**: mete comillas, alternativas ("Okay." / "Alright.") y una
  explicación entera para "Mande." → el modo sesión las elimina.
- Riesgos vistos en modo sesión: (1) entradas vacías o raras → texto meta ("the current
  utterance is missing…", "Mirin is likely…") — la app ya no manda chunks vacíos, pero hace
  falta un guard de salida (filtro meta-texto existente o salida estructurada); (2) a veces
  "rellena" ASR roto con contenido plausible ("mañana y bebe" → *my wife and I*); (3) "Le
  comento…" quedó como *I'd like to point out* (bien) pero en el guion como *I told you* (mal).
- Ruido (sección H): "¡Ja! ¡Gracias! ¡Gra" → *Ha! Thanks! Thanks*; no filtra risas por sí solo.

**Conclusión:** Haiku en línea da **la mejor calidad** (slang + corrección de nombres) con
**~0.65–0.7 s** de traducción completa — más rápido que Gemma texto en el teléfono (~1.3–2 s) y
apenas por encima del primer token de Gemma. Con datos móviles se esperan +100–300 ms [I].
Costo ≈ US$0.00004 por frase (≈ 1–2 centavos de dólar por día de uso intenso). Vale la pena
construir el **modo en línea** con respaldo local (ver plan siguiente).

## Research traductor en tiempo real de ChatGPT (16-oct)
Resumen en `docs/research-chatgpt-realtime-2026-10.md`. Clave: OpenAI no traduce "más
rápido por frase" que nosotros; traduce **sin esperar el fin de frase** (simultánea,
~1.5–2.7 s detrás según mediciones de terceros). Lo aplicable: MT en streaming → TTS por
cláusulas, fin de turno semántico, y opcionalmente probar `gpt-realtime-translate`.

## Modo en línea con Supabase (16-oct, rondas r15–r18)
Ver PROGRESS.md "Banco Haiku, voz más ágil y modo en línea con Supabase" y
`backend/README.md`. Decisión de Abraham: por calidad, el stack Moonshine → Haiku (en línea)
→ Kokoro va ganando; modo Auto local queda como respaldo sin señal.

## Reflexión de arquitectura (16-oct, Abraham) → siguiente fase
Con Haiku resolviendo la calidad, el teléfono potente deja de ser requisito. Propuesta: **cliente
ligero** (micrófono + Moonshine + reproducción en la JBL) y **servidor propio** que reciba el texto,
traduzca con Haiku y sintetice la voz, devolviendo audio en streaming (varios clientes; quita el
salto de Supabase ~0.35 s). El modo local actual (Kokoro + ML Kit/Gemma) queda como respaldo sin señal.
Descartado: Raspberry/mini-PC para la voz (más hardware, CPU más lenta que el Xiaomi).
Aclaración: lo deprecado por Google es NNAPI, no Kokoro (modelo Apache-2.0 sobre ONNX Runtime).
Research de servidores y TTS en la nube: `docs/research-server-tts-2026-10.md`.

## Servidor propio con voz (20-oct, rondas r20–r22) y tarea para producción
Relay `backend/relay` (Haiku → cláusulas → ElevenLabs "Eric"): primer audio p50 ~0.9 s desde que el
teléfono manda el texto, independiente del largo. Abraham: "muy rápido, la experiencia general fue
muy buena". Despliegue preparado en `backend/Dockerfile` + `backend/fly.toml` (Fly.io `ord`).

**Antes de producción (pregunta de Abraham: ¿los retrasos dependen del teléfono?) — sí:** el margen
de reactivación del micrófono (`UNMUTE_TAIL_MS` = 800 ms) y el arranque para bocinas dormidas
(300 ms) están calibrados para Xiaomi 15T Pro + JBL. Cambian con el teléfono, la bocina y el códec
Bluetooth. Plan: medir en cada equipo — (1) latencia real de salida con `AudioTrack.getTimestamp`
(en muchos equipos incluye el Bluetooth); (2) calibración de ~2 s con un tono al conectar una bocina
nueva (como la estimación de retardo del AEC); (3) los valores fijos actuales como respaldo.
Lo independiente del teléfono: Haiku, la voz y el servidor.

## Frentes abiertos tras r24 (20-oct, cierre)
1. **Interpretación simultánea (hablar encima de la traducción).** Modo dúplex activo: el micrófono
   nunca se silencia y AEC3 resta la voz de Eric. En r24, al hablar encima, la mezcla de su voz con
   restos de eco produjo transcripciones erróneas ("Ebraron al centro…", "el alma un poco…").
   Mitigado (commit 71e512f): el filtro estricto aplica si el TTS sonó en cualquier momento del
   pedazo, umbral 2400 → 3000 (eco ≤ 2911, voz 3500–6800). No resuelve las mezclas con voz dominante.
   Abraham quiere hablar de forma continua, "como en una conversación". Opciones a evaluar: afinar AEC3
   para doble habla, transcribir sólo tramos sin TTS, separación de fuente/voz, bajar el volumen de
   Eric mientras él habla (ducking), y medirlo con datasets propios.
2. **El interlocutor habla inglés.** El micrófono también lo capta; Moonshine-es lo convertiría en
   español inventado y Eric lo "traduciría". Antes de usarlo en Chicago: detección de idioma (descartar
   inglés), botón "hablo yo", o traducción bidireccional (inglés → español a los audífonos de Abraham).
3. Pendientes previos: retención por pausas + muletillas, filtro de risas, calibración por
   teléfono/bocina, plan de pago de ElevenLabs.
Costo corriendo: 1 máquina Fly siempre encendida (traductor-t2c) — detener con
`flyctl scale count 0 --app traductor-t2c` si no se usará por un tiempo.

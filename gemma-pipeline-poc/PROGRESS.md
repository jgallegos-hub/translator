# gemma-pipeline-poc — Progress

Standalone Android POC that closes the speech-to-speech loop for the
Travel2Chicago real-time ES→EN translator: **Mic → Silero VAD → Chunker →
Gemma 4 E4B AST → Kokoro-82M TTS → Speaker**, all in one process on the
Xiaomi 15T Pro.

---

## Fase 4: Gemma AST en Pipeline ✅ COMPLETADA (junio 2026)

POC integrado validado en device. AST pipeline conectado: chunks 3–6 s del
chunker (Fase 3) → traducción ES→EN en pantalla en ~2 s. Commit que cerró
Fase 4: **`92e4a88`**, fixes post-validación en **`5253440`**.

### Resultados:
- **Primera traducción end-to-end** funcionando (<3 s desde end-of-speech)
- **Traducciones consecutivas** estables, avg latency ~2.7–3.3 s en GPU
- **Sin OOM** con Gemma cargado (~2.5 GB) sobre Silero (~2 MB) + Oboe
- **39 unit tests heredados** de Fase 3 + 10 nuevos (`WavBuilderTest`,
  `AstChunkRouterTest`) — todos passing
- **GPU → CPU fallback** funcional (no triggereado en device real, pero
  testeado con manifest stub)

### Fixes post-validación (commit `5253440`):
1. **Dispatch log noise** documentado como inevitable. LiteRT-LM 0.12.0
   emite cientos de `[litert_dispatch.cc:113] No dispatch library found`
   durante `engine.initialize()`. `EngineConfig` no expone
   `dispatchLibDir` y el log viene del lado nativo
   (`__android_log_print`), no filtrable desde Kotlin. Documentado en
   `GemmaAstEngine.kt` y README → Known noise.
2. **Prompt completeness**. Output observado se truncó en
   "start a new" cuando debía ser "start a new shift". Prompt actualizado
   a `"Translate the following Spanish audio to English completely.
   Output the full translated sentence without truncating. Respond with
   only the English translation, no other text or commentary."`
3. **Graceful router shutdown**. `stop()` (rename → `cancel()`) ahora
   tiene contraparte `suspend stopGracefully(drainTimeoutMs = 8_000L)`
   que cierra el channel y espera al consumer a drenar lo pendiente
   antes de retornar. `ViewModel.stopPipeline()` lo lanza en
   `viewModelScope` para no bloquear el stop principal; `onCleared`
   sigue con `cancel()` duro porque no es suspend.

### Lecciones técnicas:
- `Conversation.sendMessage(Contents.of(AudioBytes, Text))`: el orden
  audio-FIRST / texto-LAST es obligatorio (Fase 0 ya lo había documentado;
  ratificado aquí).
- `EngineConfig` 0.12.0 = `{modelPath, backend, audioBackend, maxNumTokens,
  cacheDir}`. Sin más settings.
- LiteRT-LM no expone `Conversation.reset()` ni `Conversation.clear()` →
  ver Fase 5 para la consecuencia.
- AndroidManifest `<application>` requiere `<uses-native-library>` para
  `libOpenCL.so` + `libvndksupport.so`, **dentro** de `<application>`,
  no como hermano. Fuera no rompe el build pero el GPU backend falla en
  runtime sin error útil.
- `largeHeap=true` + `MANAGE_EXTERNAL_STORAGE` requeridos. HyperOS
  acepta `requestLegacyExternalStorage=true` sin prompt extra.

### 12/12 criterios Go/No-Go: PASS

---

## Fase 5: Kokoro TTS ✅ COMPLETADA (junio 2026)

Pipeline end-to-end **completo**: Mic → Silero VAD → Chunker → Gemma AST
→ Kokoro TTS → JBL Go 4 (Bluetooth). Texto inglés sintetizado en voz
natural y reproducido fuera del aparato. Commits `f11f55e` → `e22c797`.

### Resultados device testing (Xiaomi 15T Pro):
- **Traducciones correctas y naturales**, ej.:
  - "Hello Luisa, how did you wake up today? How was your weekend?"
- **0 errores, 0 drops** en operación normal
- **Kokoro**: 54 voces cargadas del NPZ real, `af_heart` default,
  ~1.3 s de load, síntesis 1.5–5 s por oración
- **Gemma**: 2.7–3.3 s por traducción, GPU backend, conversation per chunk
- **78 unit tests** passing (49 heredados de Fase 4 + Phonemizer 9 +
  Tokenizer 6 + TtsRouter 6 + KokoroTtsEngineUtils 5 + TtsConfig 4 +
  VoicesNpz 9)

### Fixes aplicados durante la validación (en orden):

**Fix 1 — `TtsConfig.modelFilename`** (commit `ed3c8ea`):
Default cambiado de `kokoro-v1.0.onnx` (fp32 ~310 MB) a
`kokoro-v1.0.int8.onnx` (~88 MB). El fp32 no cabe en el RAM budget
junto con Gemma (~2.5 GB) + Silero + buffers.

**Fix 2 — Assets reales** (commit `ed3c8ea`):
- `kokoro_config.json`: vocab 178-token IPA copiado **verbatim** de
  `thewh1teagle/kokoro-onnx/src/kokoro_onnx/config.json`. Los IDs están
  baked en el modelo — usar otro mapping da audio basura.
- `cmudict_ipa.dict`: 125 074 entradas, ~3 MB, de
  `puff-dayo/Kokoro-82M-Android` (rama `latest`). Tab-separado
  `WORD\tIPA`, comentarios `;;;`, variantes `WORD(N)` dedupeadas al
  cargar.
- Stub `kokoro_dict_en_us.txt` eliminado.

**Fix 3 — ONNX I/O convention detection** (commit `ed3c8ea`):
Kokoro v1.0 tiene dos exports en circulación:
| Export | Tokens input | Speed input | Output |
|---|---|---|---|
| Newer | `input_ids` (int64) | `speed` (**int32**) | `audio` por nombre |
| Older | `tokens` (int64) | `speed` (float32) | igual |

`KokoroTtsEngine.load()` inspecciona `session.inputInfo` y selecciona la
convención correcta. Tokens se envuelven con PAD en ambos extremos
(`[0, ...tokens, 0]`, equivalente al `tokens = [[0, *tokens, 0]]` del
reference Python).

**Fix 4 — Tokenizer / Phonemizer per-character** (commit `ed3c8ea`):
El refactor durante research reveló que el diseño inicial estaba mal —
Kokoro tokeniza **char por char** del string IPA, no fonema por fonema:

| Antes | Ahora |
|---|---|
| `Phonemizer.phonemize(): List<String>` | `Phonemizer.phonemize(): String` |
| `Tokenizer.tokenize(List<String>)` con map `phoneme→ID` | `Tokenizer.tokenize(String)` con map `char→ID` |
| Tokenizer añadía BOS/EOS internamente | Tokenizer puro; engine wrappea con PAD |
| Lookup lowercase | Lookup **uppercase** (convención CMU) |

**Fix 5 — VoicesLoader NPZ format** (commit `3df8486`):
`voices-v1.0.bin` NO es un blob plano de float32 concatenados — es un
**NPZ** (ZIP de archivos `.npy`), uno por voz. Cada voz es
`[511, 1, 256]` float32. Python hace `voice = voice[len(tokens)]` antes
del PAD wrap → vector `[1, 256]` por sentencia.

Reescribí `VoicesLoader.kt` como `VoicesNpz` con un parser `.npy`
v1/v2 (magic, header len uint16/uint32, dict ASCII de Python) +
`VoiceStyles.styleFor(name, tokenCount)` que carva el slice 256-float
en offset `tokenCount * 256`. En device cargó las 54 voces reales.

**Fix 6 — Conversation per chunk** (commits `070a444` + `e22c797`):
Reusar una sola `Conversation` para todas las traducciones causaba dos
síntomas con la misma raíz:
- Traducción #4 echaba `concat(#1, #2, #3)` — el historial seguía vivo
  en el KV cache y el modelo lo re-emitía como contexto reciente.
- ~Traducción #6–#7 tiraba `LiteRtLmJniException: Failed to invoke the
  compiled model` — context window overflow con audio tokens dominando.

LiteRT-LM 0.12.0 no expone `Conversation.reset()`. Fix: crear una
`Conversation` fresca por `translate()` (~ms, despreciable vs ~2 s de
inferencia).

Pero LiteRT-LM **solo permite una `Conversation` activa por `Engine`**
(`FAILED_PRECONDITION: A session already exists` en la segunda
creación). Fix completo: trackear `currentConversation` y cerrarla
antes de crear la siguiente. `engine.close()` también la libera para no
filtrar la sesión JNI en `onCleared()`.

### Issues conocidos pendientes (no blocking):

1. **Feedback loop mic ↔ speaker** — si el speaker BT está cerca del
   USB mic, el TTS se realimenta y dispara el VAD. Workaround temporal:
   separar mic de speaker físicamente. Solución producción: mic
   unidireccional + speaker cableado USB DAC (sin BT) + mute durante
   playback.

2. **Gemma meta-texto en chunks con poco speech** — responde literales
   como `"audio not provided"` o `"I cannot translate"` cuando el chunk
   tiene mayormente silencio o ruido. Solución: post-filtro por
   keywords (`"audio not provided"`, `"I cannot"`, `"as an AI"`) o
   pre-filtro por RMS del chunk antes de mandar a Gemma.

3. **Latencia primera frase ~14 s** — acumulación 6 s (max chunk) +
   Gemma 2.8 s + Kokoro 5 s. Aceptable para validación; mejora real
   requiere streaming AST (`AudioStreamingEnabled` en LiteRT-LM) +
   streaming TTS por oración. Ambos son trabajo futuro.

4. **Mute durante playback** — sin gating, el VAD procesa la salida del
   speaker como si fuera nuevo speech, generando ciclos. Implementar:
   `TtsAudioPlayer` notifica `playing=true/false` al pipeline, y el
   pipeline ignora frames mientras `playing=true`. Cap simple en el
   ViewModel.

### Lecciones técnicas:
- `voices-v1.0.bin` y formatos parecidos en HuggingFace **siempre**
  pueden ser NPZ — verificar con `unzip -l` antes de asumir layout
  plano. El reference Python usaba `np.load()` que detecta ambos
  automáticamente; eso ocultó el formato real.
- LiteRT-LM 0.12.0: `Conversation` no es resettable y solo una activa
  por Engine. Patrón obligatorio para multi-turn audio = create+close
  per call.
- ONNX exports del mismo modelo cambian nombres de inputs entre
  versiones publicadas (`tokens` vs `input_ids`, `speed` float vs
  int32). Detect en runtime via `session.inputInfo` + ramificar el
  binding, no asumir.
- Outputs ONNX leídos por **nombre** (`results.get("audio")`), nunca
  por índice — misma lección que Silero v5 Fase 3.
- AudioTrack 24 kHz independiente del path Oboe 16 kHz convivieron sin
  glitches BT — la decisión D1 ("dos rutas separadas, no resamplear")
  fue la correcta.
- Per-char tokenization vs per-phoneme: cuando un reference repo
  define una API en su tokenizador, copiar la API exacta antes de
  inventar abstracción propia.

### 12/12 criterios Go/No-Go: PASS

---

## Estabilización post-Fase 5 ✅ COMPLETADA (julio 2026)

Los dos ciclos de contaminación observados durante el primer testing
end-to-end de Fase 5 (feedback loop mic↔speaker y meta-texto de Gemma
llegando a Kokoro) están cerrados. Commits: **`6fc65af`** + **`15f509d`**.

### Cambios:

**1 — Filtros de garbage en `AstChunkRouter`** (commit `6fc65af`):
- **Pre-filtro RMS** en `AstConfig.rmsThreshold` (default 500). Chunks
  con RMS por-sample debajo del umbral se descartan **antes** de la
  llamada a Gemma → ahorra ~2.8 s de GPU cada vez que el chunker emite
  algo con poco speech real. Contador `totalDiscardedLowEnergy`
  expuesto en UI.
- **Post-filtro meta-texto** en `AstConfig.metaTextPatterns`. Substring
  match sobre `text.trim().lowercase()` — si hay hit, la respuesta se
  dropea **antes** de emitir `TranslationReady` al bus, así Kokoro
  nunca sintetiza meta-texto por el speaker. Contador
  `totalDiscardedMeta` expuesto en UI.

**2 — Meta-text list ampliada** (commit `15f509d`): la lista original
de 6 patrones cubría respuestas de "no-input" ("not provided",
"no audio", "please provide", "no spanish", "cannot translate",
"no speech") pero dejaba pasar los **preámbulos de asistente**
observados en device — ejemplos reales:
- `"The translation of the Spanish audio is: 'I'm going to the store.'"`
- `"Here is the translation: hello"`
- `"The audio is: hello"`

6 patrones nuevos agregados al default: `"translation of"`,
`"the translation"`, `"spanish audio"`, `"translate the"`,
`"here is the"`, `"the audio"`. **Total: 12 patrones**. Nuevo test
`AstChunkRouterTest#default AstConfig patterns catch the assistant-
preamble leak observed on device` valida contra la frase exacta usando
el `AstConfig` real (sin override de patrones) — si alguien acorta la
lista sin querer, el test rompe.

**3 — Mute VAD durante TTS playback** (commit `6fc65af`): shared
`AtomicBoolean ttsPlaying`, ownado por el ViewModel:
- `TtsAudioPlayer.play()` lo levanta antes del primer
  `AudioTrack.write()` y lo baja en `finally` (garantiza cierre incluso
  si el coroutine se cancela mid-playback).
- `VadChunkingPipeline.setTtsPlayingRef(ref)` recibe la referencia
  antes de `start()`. En `handleAudioData`, si `ttsPlaying.get()` es
  `true`, hace `return` inmediato — no reassembler, no VAD, no
  chunker. Log edge-triggered: **una línea** al empezar mute, **una**
  al terminar con contador de frames droppeados. No spam.
- La UI muestra el estado live `🔇 VAD muted (TTS)` ↔ `🎙 VAD live` y
  el total de frames droppeados desde el último `start`.

**4 — Reset del chunker + VAD + reassembler en el edge de mute**
(commit `15f509d`): sin este reset, el frame post-mute concatenaba a
un buffer pre-mute con un gap de varios segundos de TTS. Gemma recibía
una utterance temporalmente rota y respondía con basura.

Nuevo helper `VadChunkingPipeline.handleMuteRisingEdge()`, invocado
una sola vez en el edge `false→true`:
- Chunker `collecting=true` **y** `currentSampleCount >= minChunkSamples`
  → `flush()` y emite el chunk (Gemma recibe algo coherente de lo que
  se dijo pre-mute).
- Chunker `collecting=true` **y** buffer `< minChunkSamples`
  → discard silencioso; muy corto para traducir.
- Incondicional: `chunker.resetAll()` (buffer + preRoll + silenceCount
  + collecting) + `processor.reset()` (state machine + LSTM state +
  context prefix Silero v5) + `reassembler.reset()` (descarta hasta
  511 samples residuales pre-mute que hubieran splicheado al primer
  frame post-mute).

`AudioChunker` extendido con `currentSampleCount: Int` (getter),
`minChunkSamples: Int` (ahora público — el pipeline usa el MISMO valor
que `feed()` internamente para no divergir), y `resetAll()` (que es
`reset()` **más** `preRoll.clear()`).

### Tests nuevos (total suite ahora ~86):
- `AstChunkRouterTest` (5 nuevos post-Fase 5): low-RMS discard, high-RMS
  pass-through, meta-text discard con `expectNoEvents()` sobre
  `TranslationReady`, case-insensitive match, clean replies pasan
  cuando la lista está configurada. + 1 nuevo del pattern-leak con
  `AstConfig` default.
- `AudioChunkerTest` (2 nuevos): `currentSampleCount` tracking,
  `resetAll` clears buffer + preRoll (verifica que un nuevo SPEECH
  frame post-reset no arrastra pre-roll viejo — el buffer nuevo tiene
  exactamente `BLOCK` samples).

### Issues conocidos pendientes (no blocking):

1. **Feedback loop mic ↔ speaker (físico)** — el mute software cortó
   el ciclo digital, pero cuando el JBL Go 4 está pegado al Saramonic
   USB, el propio Silero VAD puede confundirse con el eco durante los
   transients de fin de playback (el mute baja justo antes de que se
   apague el último buffer del `AudioTrack`). Solución producción:
   mic unidireccional apuntando lejos del speaker + speaker cableado
   USB DAC (sin BT, para latencia determinística).

2. **Gemma con slang mexicano produce traducciones garbled** —
   ejemplo real en device: "no mames" → "store no asa". El modelo
   Gemma 4 E4B está entrenado con español neutro; expresiones muy
   coloquiales (regionalismos, groserías, contracciones informales)
   caen en OOV semántico. Optimización futura: (a) tuning del prompt
   con hints de registro ("informal Latin American Spanish"), (b)
   evaluar un modelo fine-tuned para es-MX, (c) fallback a un
   translator de texto si Gemma falla en confianza.

3. **Latencia primera frase ~14 s** — sigue siendo el mismo cuello:
   acumulación 6 s (max chunk) + Gemma 2.8 s + Kokoro 5 s. Los filtros
   RMS + mute no cambian la latencia percibida en el happy path.
   Siguiente: **Fase 6 Streaming AST** (`AudioStreamingEnabled` en
   LiteRT-LM) reduce Gemma a "primera palabra en ~500 ms",
   posiblemente combinado con streaming TTS por oración → percepción
   ~2–3 s.

4. **JBL Go 4 BT se desconecta por inactividad** — en el testing
   sostenido de estabilización el speaker BT se pone a dormir después
   de ~30–60 s sin audio; al volver a hablar los primeros ~300 ms de
   TTS se pierden porque el codec A2DP tarda en re-negociar. Sin
   solución razonable en software; reemplazar con speaker cableado
   USB DAC en producción.

---

---

## Fase 6: Optimización de latencia ✅ VALIDADA EN DEVICE (julio 2026)

Commits: **`20bc326`** (Stage A) + **`937a5bf`** (Stage B) + **`54661d2`**
(Stage C) + **`c0601a9`** (UI toggles + latency counters).

### Investigación del SDK (base de todas las decisiones)

Tres agentes de research en paralelo (trace del código actual +
reverse-engineering del AAR local `com.google.ai.edge.litertlm:
litertlm-android:0.12.0` + docs upstream hasta 0.14) contestaron
definitivamente la pregunta clave: **¿puede Gemma empezar a procesar
audio mientras el usuario habla?**

**Respuesta: NO.** Ni 0.12.0 en disco ni las release notes públicas hasta
0.14 (julio 2026) exponen streaming de audio de entrada. El log nativo
`AudioStreamingEnabled: false` que veíamos en device es una salida del
backend LiteRT — no hay setter público en `EngineConfig` para volverlo
`true`. El chunker debe emitir el WAV completo antes de la primera
llamada a inferencia.

Lo que **SÍ** existe y no estábamos usando:
- `Conversation.sendMessageAsync(message, extraContext?): Flow<Message>`
  — variante Kotlin/coroutine-native que emite tokens de salida
  incrementalmente.
- `Session` con `runPrefill(List<InputData>)` + `runDecode()` — permite
  prefill incremental pero decode todavía debe esperar al final del
  audio, así que la ganancia real es ~200–400 ms. Se difiere.

Consecuencia: **el 6 s del chunker no se puede recortar por vía de API**.
Los 2.8 s de Gemma **sí** se pueden solapar con la síntesis de Kokoro
(Stage A). Los 5 s de Kokoro **sí** se pueden solapar oración-a-oración
(Stage B). Y los defaults del chunker se pueden retunear porque
`minChunkMs=3000` era conservador para Whisper — con Gemma 4 E4B se
puede probar 1500 sin perder calidad significativa (Stage C).

### Stage A — Token streaming Gemma → Kokoro (commit `20bc326`)

Feature flag: `AstConfig.streamingEnabled: Boolean = false` (OFF al merge).

- `GemmaAstEngine.translateStreaming(wav, prompt, onToken)` — nuevo
  método suspend paralelo al existente `translate`. `LiteRtGemmaAstEngine`
  lo implementa con
  `conv.sendMessageAsync(contents).onCompletion { closeCurrentConversation() }.collect { onToken(delta) }`.
  El cierre en `onCompletion` (no en `collect`) es crítico — cerrar
  mid-collect racea al finalizer JNI y dispara
  `FAILED_PRECONDITION: A session already exists` en el siguiente call.
- `AstChunkRouter.processChunkStreaming` — nuevo helper con scanner de
  terminadores in-line (`.`, `!`, `?`), buffer acumulador, y **pending
  buffer trick**: la última oración cerrada se **retiene** hasta que
  llega otra (y entonces la pending se emite con `isFinal=false`) o
  hasta que el Flow completa (y se emite con `isFinal=true`). El texto
  trailing sin terminador después del último `.` se convierte en la
  oración final.
- Meta-text filter opera sobre `sb.substring(0, lastCutOffset)` (buffer
  acumulado) — así `"The tra" + "nslation of: Hi."` (preambulo partido
  entre dos tokens) se atrapa. Confirmado en el AskUserQuestion como el
  approach elegido: drop-everything-from-that-chunk on hit; keep
  draining Flow para que el decoder JNI cierre limpio.
- `AudioEvent.TranslationReady` gana `sentenceIndex: Int? = null` +
  `isFinal: Boolean = true` (defaults preservan el shape pre-Fase-6).
- **8 tests nuevos** en `AstChunkRouterTest`: multi-sentence emission,
  trailing text becomes final, single-sentence isFinal, preamble split
  across tokens, preamble without terminator caught at flow-end,
  streaming exception → AstError, RMS pre-filter still applies,
  multiple terminators in one delta (regression guard).

### Stage B — Kokoro per-sentence streaming (commit `937a5bf`)

Feature flag: `TtsConfig.streamingEnabled: Boolean = false` (OFF al merge).

- `KokoroTtsEngine.synthesizeStreaming(text, voice, onSentence)` —
  reusa `splitIntoSentences` + `synthesizeOne` + `concatToInt16`; por
  cada oración llama `onSentence(pcm, sampleRate, sentenceIndex)`
  apenas termina el `session.run` ONNX. Aggregate `TtsResult.pcm` queda
  vacío (caller consumió por callback).
- **`TtsPlayerSink` interface** — 3 métodos (`beginUtterance`,
  `endUtterance`, `play`). `TtsAudioPlayer` lo implementa. Router
  depende de la interfaz, no de la clase concreta → tests con
  `RecordingSink` sin Android runtime.
- `TtsAudioPlayer.utteranceDepth: AtomicInteger` — `beginUtterance`
  incrementa; en el 0→1 setea `ttsPlaying=true`. `endUtterance`
  decrementa clampeado a 0; en el 1→0 lo baja. `play()` verifica
  `depth == 0` **dentro del mutex** al entrar: si sí, maneja el flag
  por-call (path legacy exacto); si no, deja el flag a los bookends
  para toda la utterance. Evita el flicker `true↔false` entre
  oraciones consecutivas — sin este truco el `handleMuteRisingEdge`
  del pipeline dispararía un reset del chunker mid-utterance.
- `TtsRouter` toma un `player: TtsPlayerSink? = null` opcional
  (default null preserva todos los tests bus-only existentes).
  `processTranslationStreaming` detecta boundary por
  `sourceChunkTimestampNs` cambiando; defensive `endUtterance` para
  la utterance anterior si nunca llegó su `isFinal` (DROP_OLDEST o
  Gemma error mid-stream); `beginUtterance` para la nueva.
- ViewModel gate: `TtsAudioReady` handler skipea `player.play()`
  cuando `ttsConfig.streamingEnabled = true` (el router ya reprodujo).
  Sigue ticando `totalSpoken` para métricas.
- **5 tests nuevos** en `TtsRouterTest`: multi-sentence
  (`begin/play×3/end`), Stage-A style dos eventos con misma
  `sourceChunkTs` (1 begin, 1 end, 2 plays), defensive endUtterance
  cuando llega una utterance nueva sin isFinal previo, engine
  exception cierra utterance por finally, non-streaming path con
  player wireado NO toca el sink.

### Stage C — Chunker retune (commit `54661d2`)

Sin flag — cambio de defaults siempre-activo.

- `ChunkerConfig` defaults: **`minChunkMs 3000 → 1500`**, **`silenceEndMs
  700 → 500`**. `maxChunkMs = 6000` y `preRollMs = 200` sin cambio.
- Justificación: con Gemma+Kokoro streaming (Stages A+B) la latencia
  audible ya no está dominada por el chunk boundary sino por el
  first-sentence emit. Halvear el min-chunk shave ~1.5 s off del
  end-of-speech → first-audio sin degradar calidad en las 10 frases
  canónicas del device test. Sliders en la UI siguen wireados por si
  el operador quiere volver a 3000 / 700 para un tipo de audio
  específico.
- Tests: `ChunkerConfigTest` con las 4 nuevas aserciones + la math
  derivada (`24_000` min-samples y `15` silence-end frames @ 16 kHz /
  512-block). Los demás `AudioChunkerTest` cases siguen verdes porque
  usan valores explícitos chicos (32–2000 ms) que no dependen del
  default.

### Follow-up — UI toggles + latency counters (commit `c0601a9`)

Antes de este commit los dos flags no tenían forma de flippearse en
runtime sin rebuild, y no había número visible para medir la ganancia.
Este commit los wirea.

- **Router-side landmarks** (last-value semantics, per-chunk):
  - `AstChunkRouter.firstTokenLatencyMs: AtomicLong` — set en la
    PRIMERA delta non-empty del path streaming (detectada con
    `sb.isEmpty()` at callback entry — el boundary más barato), o
    después de `translate()` en one-shot. Apples-to-apples.
  - `TtsRouter.firstAudioLatencyMs: AtomicLong` — set justo antes de
    `bus.emit(TtsAudioReady)` en one-shot, y en el primer
    `sink.play(pcm)` de cada utterance nueva en streaming (armado
    por `firstAudioPending = newUtterance`; el callback graba una
    vez y clarea el flag).
  Ambos medidos desde `ChunkReady.timestampNs` transportado por
  `TranslationReady.sourceChunkTimestampNs` — número end-to-end
  mic-to-first-output, no solo el engine call.
- **ViewModel**: `astConfig` / `ttsConfig` a `@Volatile var`;
  `setAstStreamingEnabled(enabled)` / `setTtsStreamingEnabled(enabled)`
  actualizan config + UI state, y si el router afectado corre lo
  cancelan + reconstruyen (pipeline + capture + player + el OTRO
  router intactos). Si el pipeline está stopped, solo config/state
  cambian y el próximo `startPipeline` usa el nuevo valor.
- **UI**: nueva sección **"3½. FASE 6 STREAMING"** entre CHUNK
  PLAYBACK y TRANSLATIONS. Dos `SwitchRow` (AST / TTS streaming) +
  panel de latencia (`First token: X ms | First audio: Y ms`,
  placeholder `—` cuando el valor es 0 para evitar el trap "0 ms =
  blazing fast" en fresh start).

### Device validation (protocolo de 3 rondas)

Corrido en Xiaomi 15T Pro con Saramonic USB + JBL Go 4, 10 frases
canónicas por ronda, sin cambios de código entre rondas — solo los
toggles de UI de streaming AST / streaming TTS.

**Resultados: 0 errores, 0 crashes en las 3 rondas.**

Métricas por stage (`firstTokenLatencyMs` y `firstAudioLatencyMs`
medidos desde `ChunkReady.timestampNs` — mic-to-first-output real):

| Configuración | First token | First audio | Notas |
|---|---|---|---|
| Baseline pre-Fase-6 (chunker 3000/700) | ~5800 ms | ~13500 ms | reference |
| Stage C only (chunker 1500/500) | ~2000 ms | ~3700 ms | flags OFF |
| + Stage A ON (AST streaming) | ~1200 ms | ~3800 ms | Gemma stream, Kokoro one-shot |
| + Stage A + B ON (todo streaming) | ~1170 ms | **~3400 ms** | Kokoro sentence-1 mientras 2 decodifica |

**Lectura de las mediciones**:
- **Stage C (chunker retune) fue la mayor ganancia** — bajó
  first-audio de ~13.5 s a ~3.7 s con solo el cambio de defaults
  (min-chunk 3000→1500, silence-end 700→500). Confirma la hipótesis
  del plan: el 6 s de acumulación del chunker era el bottleneck
  dominante pre-Fase-6, no la inferencia de Gemma.
- **Stage A (AST streaming) dio 42% adicional en first-token**
  (~2000 → ~1170 ms) — Gemma empieza a emitir tokens antes de
  terminar el decode completo. First-audio se movió poco porque
  Kokoro one-shot todavía espera la utterance entera antes de
  sintetizar.
- **Stage B (TTS streaming) tuvo impacto modesto** en las frases de
  prueba (~3800 → 3400 ms) porque casi todas eran una sola oración
  — no hay "sentence 2" mientras se sintetiza "sentence 1". El
  beneficio real de Stage B será significativo con frases largas
  multi-oración (traducciones de párrafos completos), donde
  first-audio depende solo de la primera oración y no de la suma.
- **Meta-text filter funcionó en el path streaming** — atrapó
  correctamente "not provided" cuando llegó partido entre tokens.
- **Bookends `beginUtterance` / `endUtterance` sin flicker** — el
  mute stayed `true` toda la utterance; no hubo falsos disparos del
  `handleMuteRisingEdge` del chunker entre oraciones.
- **Graceful shutdown** drenó pendientes correctamente; ningún
  chunk quedó a medio decodificar al parar el pipeline.

### Fixes post-validación

1. **Meta-text false positive**. El patrón `"the translation"` como
   substring suelto descartó una traducción legítima:
   `"This is the translation test number one."`. Los patrones
   `"the translation"` y `"the audio"` eran demasiado laxos —
   matchean texto normal que contiene esas palabras. Cambiados a
   variantes con verbo asistente:
   - `"the translation"` → `"the translation of"` + `"the translation is"`
   - `"the audio"` → `"the audio is"` + `"the audio was"`

   Esto conserva la captura de preambulos (`"The translation of the
   Spanish audio is: ..."`, `"The audio is: ..."`) sin bloquear
   traducciones que mencionan las palabras en contexto natural. El
   test `default AstConfig patterns catch the assistant-preamble leak
   observed on device` sigue verde — el preambulo original de device
   sigue cazado por `"translation of"` (que ya era pattern
   preexistente) y por el nuevo `"the translation of"`.

2. **Flip de defaults a ON**. Con las 3 rondas passing:
   - `AstConfig.streamingEnabled: Boolean = true` (era `false`)
   - `TtsConfig.streamingEnabled: Boolean = true` (era `false`)

   Los toggles en UI permanecen wireados para poder desactivar
   streaming en runtime si aparece regresión — la ruta legacy no
   se eliminó.

### Estado

- **Fases 4/5/6 core mergeadas y validadas en device**. First-boot APK
  tiene Stage A/B/C encendidos por default.
- **~15 tests nuevos** entre stages (8 AST + 5 TTS + 2 chunker),
  suite total ~102.
- **12/12 criterios Go/No-Go: PASS** para el core de Fase 6.

---

## Post-Fase-6: investigación y optimizaciones basadas en docs oficiales

Después del device validation de Fase 6 encontramos margen extra
investigando la documentación oficial de Google para Gemma multimodal
+ el AAR local `com.google.ai.edge.litertlm:0.12.0`. Cuatro cambios
implementados y mergeados; los tres últimos siguen pendientes de
device validation.

### 1. MTP / speculative decoding — investigado y descartado

Commit **`b99c771`** (feature) + **`6ed4e94`** (default OFF tras
device test).

`ExperimentalFlags.enableSpeculativeDecoding = true` está disponible
en LiteRT-LM 0.12.0 (verificado por reverse-engineering del AAR:
`ExperimentalFlags` es un `object` con la propiedad `Boolean?`, y
`ExperimentalApi` es la anotación de opt-in). Cuando true, el runtime
usa el drafter MTP embebido en el modelo para especular la siguiente
tanda de tokens y verificarlos en un decode step único — anunciado
como ~2.2× speedup en workloads decode-heavy.

**Descartado tras device test**:
- Nuestros outputs de traducción son cortos (~5–10 tokens). MTP
  acelera DECODE, no prefill. En outputs cortos la ganancia real es
  despreciable frente al overhead.
- Nuestro export `.litertlm` de Fase 0 no lleva embebido el drafter
  MTP (solo modelos post-2026-05-05 lo tienen), así que el flag sería
  un no-op silencioso incluso si la ganancia fuera real.

`AstConfig.mtpEnabled: Boolean = false` como default. El toggle de UI
queda como palanca para experimentos futuros (workloads de output
largo, model swaps).

### 2. Modelo oficial `gemma-4-E4B-it.litertlm` — probado y descartado

Commits **`ff745a7`** (bump) + **`6ed4e94`** (revert).

Probamos brevemente el export oficial `gemma-4-E4B-it.litertlm`
(post-2026-05-05, con drafter MTP embebido). Device testing:
- **Traducciones peores** — respuestas en español más frecuentes,
  inglés garbled, mayor latencia sostenida.
- **MTP no movió first-token latency** en ninguna dirección — por lo
  mismo del punto anterior.

Revertido a `gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm`
(el de Fase 0). Documentado en KDoc + README para no re-probar por
inercia.

### 3. Orden multimodal audio-after-text — implementado, pending device

Commit **`aafcce7`**.

Google's multimodal Gemma docs (textual): "For optimal performance
with multimodal inputs, place audio content **after** the text in your
prompt." Un artículo de implementación añade: "Getting this order
wrong will reduce accuracy."

Nuestro código de Fase 0 → Fase 6 usaba `Contents.of(AudioBytes,
Text)` — audio primero. Cambiado a `Contents.of(Text, AudioBytes)`
por default. `GemmaAstEngine.translate` + `translateStreaming` ganan
un parámetro `audioAfterText: Boolean = true`; el router lo pasa
desde `AstConfig.audioAfterText` (nuevo default true).

Comportamiento en el orden legacy preservado byte-for-byte cuando el
flag está en false — UI toggle presente para revertir sin rebuild.

### 4. Prompt oficial de AST — implementado, pending device

Commit **`aafcce7`** (mismo commit que el orden multimodal).

Google recomienda un formato específico para AST: pedir a Gemma que
(a) transcriba el audio en el idioma origen y (b) traduzca, con un
separador explícito para que un parser downstream pueda extraer solo
la traducción. Nuestro nuevo default:

```
Transcribe the following speech segment in Spanish, then translate
it into English. When formatting the answer, first output the
transcription in Spanish, then one newline, then output the string
'English: ', then the translation in English.
```

El router extrae todo lo que viene después de `English:` antes de
emitir `TranslationReady`:
- **One-shot**: `extractEnglishTranslation(reply)` — case-insensitive
  scan, retorna substring después del marker, fallback al reply
  completo si el marker falta (bumpea contador de diagnóstico).
- **Streaming**: gate booleano `englishGateOpen`. Cada delta scanea
  el buffer con `findEnglishMarkerEnd` (char-scan allocation-free);
  hasta que el marker aparece, tokens acumulan pero no se emite
  ninguna `TranslationReady` — evita que Kokoro hable la
  transcripción en español. Una vez visto el marker, sentence scan
  + meta-text check operan solo sobre la porción inglesa. Si el
  marker nunca llega, un fallback en end-of-flow emite el buffer
  completo como último evento (con guarda de meta-text).

`AstConfig.useOfficialAstPrompt: Boolean = true` (nuevo default);
`AstConfig.legacyPrompt` preserva el prompt Fase 4 English-only.
Toggle en UI. Contador `englishMarkerMissing` surface para
diagnóstico si el modelo empieza a saltarse el formato.

### 5. Android system TTS como Fast mode — implementado, pending device

Commit **`6ed4e94`**.

Alternativa rápida a Kokoro: `AndroidTtsEngine` wrappea
`android.speech.tts.TextToSpeech` con `Locale.US` +
`UtteranceProgressListener`. `speak(text, onStart)` retorna al llegar
`onDone`; `onStart` es el anchor de `firstAudioLatencyMs`. Load
~100 ms (`~1500 ms` Kokoro). Init en paralelo con Kokoro al bootear
la ViewModel (sin permission gate).

- **Fast mode** (Android TTS): OS renderiza a la salida del sistema
  directamente → no pasa por nuestro `TtsAudioPlayer`. Bookends
  (`beginUtterance` / `endUtterance`) siguen activándose sobre el
  sink compartido para mantener el flag de mute VAD levantado
  durante la playback → el mic no re-captura la salida del speaker.
- **Quality mode** (Kokoro, default): pipeline actual sin cambios.

`TtsConfig.useFastMode: Boolean = false` (default OFF; usuario elige
Fast mode cuando velocidad > calidad de voz). `TtsRouter` recibe un
`androidEngine: AndroidTtsEngine? = null` opcional y branchea sobre
`config.useFastMode`. Toggle en UI, con status text que reporta el
estado de init del Android TTS.

**Latencias esperadas (a validar en device)**:
- Kokoro (Quality mode): ~1.5–2.5 s/oración
- Android TTS (Fast mode): ~100–300 ms/utterance

### Estado — pendiente device validation

- Commit `b99c771` (MTP feature) mergeado; MTP en `false` post-test.
- Commit `ff745a7` (modelo oficial) revertido en `6ed4e94`.
- Commit `6ed4e94` — MTP default OFF + Fast TTS toggle listo.
- Commit `aafcce7` — audio-after-text order + prompt AST oficial +
  English marker extraction listos.

**Siguiente**: device validation de los 3 cambios activos que
tocan calidad/latencia:
1. **Orden audio-after-text** — validar que la accuracy sube (o al
   menos no baja) en las 10 frases canónicas contra el orden legacy.
2. **Prompt AST oficial + extracción** — validar en device que Gemma
   respeta el formato `English: ...` mayoría del tiempo. Ver el
   contador `englishMarkerMissing` en UI para detectar si el
   fallback se activa seguido (implica que Gemma ignora el formato).
   Evaluar si first-audio sube por el gate (esperado: mismo o
   marginalmente mayor porque el marker llega en el mismo Flow).
3. **Fast TTS mode** — encender el toggle, medir
   `firstAudioLatencyMs` bajado a ~500 ms (target). Evaluar
   inteligibilidad de la voz sistema vs Kokoro para uso en viaje.

### Device validation resultados (agosto 2026)

Sesión de device testing con los 3 cambios encendidos. Resultado
general: **todo funciona, dos ajustes finos pendientes**. Commit del
cierre: **`fde28f5`** (docs) + próximos con los fixes.

**Lo que funciona bien**:
- **Orden audio-after-text**: sin regresiones observadas. `English:`
  marker detectado en el 100 % de los chunks — Gemma respeta el
  formato con el nuevo orden multimodal.
- **Prompt oficial + extracción**: la extracción en el router (one-shot
  + streaming gate) funciona en todos los chunks; el contador
  `englishMarkerMissing` se quedó en 0 durante la sesión completa.
- **Android TTS Fast mode**: `firstAudioLatencyMs` medido ~3.4 s vs
  Kokoro 3.2–6 s. La voz del sistema es más ágil (sin el "colchón"
  de warmup de Kokoro) — Fast mode va a ser el default de viaje;
  Kokoro queda como opción de calidad para uso estacionario.
- **Calidad de traducción**: buena con ambos cambios activos (orden
  + prompt). Sin regresión perceptible vs la baseline pre-cambio.

**Dos problemas encontrados**:

1. **Prompt oficial genera transcripción española desperdiciada**.
   Los logs muestran offsets de 22–72 caracteres de texto español
   antes del marcador `English:` en cada chunk. Esto son ~1–2 s de
   decode que no se traducen en audio — el gate del router los
   descarta pero Gemma ya los generó. Fix: prompt reescrito para
   pedir SOLO la traducción con el marcador `English:` al principio,
   sin la parte de transcripción. Mantiene el marcador (extracción
   downstream sigue funcionando) pero elimina el texto español
   desperdiciado. Commit: **próximo**.

2. **Meta-text filter — falso positivo con `"the translation is"`**.
   La traducción legítima "They tell me how the translation is going"
   fue descartada. La causa: los patrones matcheaban en cualquier
   parte del texto, no solo al inicio. Los preambulos de asistente
   siempre empiezan con "The translation of..." — nunca aparecen
   mid-sentence. Fix: restringir el match de meta-text a los primeros
   `metaTextPrefixChars = 60` caracteres del reply (después de
   extraer el marcador). Preserva la captura de preambulos, elimina
   los falsos positivos en oraciones normales. Commit: **próximo**.

Ambos fixes son one-liners a nivel arquitectónico y no requieren
otra ronda de validación completa — device smoke test post-fix
alcanza.

### Estado final Fase 6

- 5 investigaciones post-Fase-6 completas: 3 shipped + 2 dropped +
  2 refinamientos post-device.
- Todo mergeado con toggles en UI para reversal sin rebuild.
- **Siguiente**: **Fase 7 — Upgrade LiteRT-LM 0.12 → 0.14**
  (GPU audio acceleration). La 0.14 expone aceleración GPU para el
  audio encoder de Gemma; hoy corremos audio en CPU (~2.8 s por
  inferencia). GPU podría bajar eso significativamente. Requiere
  bump de dependencia + validación de que la API se mantiene
  compatible (nuestro código ya usa las APIs experimentales de
  streaming + Session que existen en 0.14).

### Lecciones técnicas:
- LiteRT-LM 0.12.0 `sendMessageAsync` retorna un cold Flow. `onCompletion`
  se dispara en éxito Y error, así que es el único lugar seguro para
  cerrar la `Conversation` sin racear el finalizer JNI.
- Cada `Message` en el Flow es una DELTA (asunción sin doc oficial —
  documentada en comentario + log de la primera delta en device para
  verificar). Si en un release futuro cambian a acumulado, hay que
  flipear a `msg.substring(fullText.length)`.
- Meta-text check debe correr sobre el buffer acumulado, NO por
  oración. Preambulos como "The translation of the Spanish audio is:"
  se parten entre múltiples emisiones de tokens; per-sentence check
  los deja pasar.
- Sinks abstraídos por interfaz (`TtsPlayerSink`) tienen dos beneficios:
  (a) el router es testeable sin AudioTrack, (b) el rewiring por
  toggle no requiere mover instancias — solo cambiar el config + la
  ref del router.
- Feature flags con default OFF al merge es la única forma segura de
  shippear cambios de path sin rebuild-para-revertir. El commit que
  flippea defaults es siempre separado del que introduce la
  funcionalidad.

---

## Fase 7 — Upgrade LiteRT-LM 0.12 → 0.15: intentado y REVERTIDO (agosto 2026)

Bumpeamos la dependencia `com.google.ai.edge.litertlm:litertlm-android`
de **`0.12.0` → `0.15.0`** (última versión estable en Google Maven
al momento; saltamos 0.13 y 0.14 en un solo bump). Motivación:
0.14 introdujo aceleración GPU/NPU para el audio encoder — hoy
`audioBackend = Backend.CPU()` es el bottleneck de prefill (~800 ms
por chunk). Con GPU audio podría bajar a ~200–400 ms.

**Revertido tras device testing** — el bump no pasó smoke test:

- **GPU audio no se activó con nuestro modelo**. Aún con el flag
  `audioBackendGpu = true`, el modelo `gemma4_4b_v09_...` (Fase 0)
  no expone un audio subgraph compatible con GPU. El fallback
  automático `(GPU main + GPU audio) → (GPU main + CPU audio)` cae
  al segundo attempt sin gain observable — igual que estábamos en
  0.12 solo que con más ciclos gastados en el intento fallido de
  init GPU-audio.
- **Regresión de first-token latency**: pasó de ~1170 ms (Fase 6
  medido) a ~2000 ms sostenido en 0.15. No investigado a fondo —
  puede ser cambios en el scheduler interno del runtime, la nueva
  ruta de `sendMessageAsync` con más params opcionales, o algún
  cambio en cómo el sampler config se aplica. Sin tiempo para
  bisectar dentro de una revisión que ya rompe otras cosas.
- **Dos crashes por presión de memoria** durante uso sostenido.
  0.15 pesa notablemente más en heap (más clases, más nativo — el
  AAR pasó de ~15 MB a ~19 MB). En un dispositivo con Gemma
  4E-B GPU + Silero + Kokoro ONNX + Oboe buffers todos vivos, el
  headroom era ya justo; los ~4 MB extra fueron suficientes para
  gatillar OOM en dos runs de ~5-10 minutos.

Neto: SDK más pesado, mismo pipeline de audio, con regresión y
crashes. **Revertido a `0.12.0`**.

**Preservado del intento**:
- Flag `AstConfig.audioBackendGpu` con default `false` y KDoc que
  explica el requerimiento SDK ≥ 0.14 + modelo compatible. El code
  path en `LiteRtGemmaAstEngine.load()` (la cadena de attempts
  `(main, audio)`) también se preservó — si algún día
  re-intentamos el upgrade, es solo flippear el default y bumpear
  la versión.
- Los `Engine.setNativeMinLogSeverity` y `Capabilities` (APIs
  0.14+) que habíamos wireado fueron REMOVIDOS del código —
  imports + calls — porque no compilan contra 0.12.

### GPU audio no existe para nadie todavía (hallazgo clave)

Después del revert investigamos por qué GPU audio no activaba. La
respuesta: **no es específico a nuestro modelo — no existe hoy para
NADIE**.

- **Issue [litert-torch #1039](https://github.com/google-ai-edge/litert-torch/issues/1039)**
  confirma que la herramienta `litert-torch export_hf` (el pipeline
  oficial de Google para exportar `.litertlm`) **no exporta las
  secciones de audio necesarias para GPU**:
  - `tf_lite_audio_encoder_hw`
  - `tf_lite_audio_adapter`
  - `tf_lite_end_of_audio`

  Sin estas secciones en el `.litertlm`, el runtime no tiene los
  subgraphs GPU del audio encoder y cae a CPU silenciosamente sin
  importar qué backend se declare.

- **Incluso el modelo oficial de Google** (`gemma-4-E4B-it.litertlm`
  post-2026-05-05, el que probamos y también descartamos por
  peor calidad AST) **tiene `backend_constraint: cpu`** en la
  sección de audio del `.litertlm`. Confirmado inspeccionando el
  archivo. Es decir: el propio equipo de Google no está shippeando
  un modelo con audio-GPU habilitado — no es cosa nuestra, no es un
  problema de Fase 0.

- **El audio encoder en CPU es el estado del arte actual** para
  Gemma 4 multimodal on-device. Nuestro ~800 ms de audio prefill no
  es un bug ni un mis-config — es el performance real de la
  plataforma en agosto 2026. Ningún flag, ningún bump de SDK, ningún
  swap de modelo entre los publicados hoy va a bajarlo.

**Path forward** (para revisitar cuando Google resuelva #1039):
1. Esperar que `litert-torch export_hf` habilite el export de las
   secciones `tf_lite_audio_encoder_hw` + `tf_lite_audio_adapter`
   + `tf_lite_end_of_audio` (o que salga un modelo oficial ya con
   esas secciones y `backend_constraint: gpu`).
2. Cuando pase: bumpear LiteRT-LM a la versión que soporte esas
   secciones (0.14+ ya tiene la runtime), swap del modelo, y
   flippear `AstConfig.audioBackendGpu` a `true`. El scaffolding
   ya está listo.
3. Mientras tanto: aceptar el ~800 ms de audio prefill como
   floor y buscar otras palancas (mejor chunker, streaming input
   si algún día aparece, mic direccional para menos ruido a
   procesar).

Esto nos deja tranquilos: no hay dinero que dejar sobre la mesa
en el lado del audio encoder — cerramos ese frente para el POC y
pasamos a otras cosas.

**Investigación del AAR 0.15.0 (referencia para el próximo intento)**:

Descargué + descomprimí `litertlm-android-0.15.0.aar` y comparé la
superficie de API contra 0.12.0. Sigue documentada aquí para el
próximo intento de upgrade — no es un requisito re-hacerla.

### Investigación del AAR 0.15.0

Descargué + descomprimí `litertlm-android-0.15.0.aar` y comparé la
superficie de API contra 0.12.0. Hallazgos:

**Sin breaking changes para el path que usamos**:
- `EngineConfig` — gana `visionBackend` + `maxNumImages` entre
  0.12 y 0.15. Named-arg construction sigue funcionando; los nuevos
  campos usan defaults (Gemma AST no procesa imágenes → visionBackend
  irrelevante).
- `Conversation.sendMessage` y `sendMessageAsync` — añaden params
  opcionales (`RepetitionPenaltyConfig`, `NoRepeatNgramConfig`,
  `SuppressTokensConfig`, `ThinkingConfig`, `ResponseFormat`), todos
  con defaults null. Nuestro `conv.sendMessageAsync(contents).collect
  { ... }` sigue compilando sin cambios.
- `ConversationConfig` — muchos campos nuevos (systemInstruction,
  tools, loraConfig, thinkingConfig, enableResponseFormat, etc.),
  todos con defaults. `ConversationConfig(samplerConfig = ...)`
  compila igual.
- `Backend.GPU()` / `Backend.CPU()` / `Backend.NPU()` /
  `Backend.GOOGLE_TENSOR()` — sin cambio; `Backend` sigue siendo la
  abstract factory.
- `ExperimentalFlags.enableSpeculativeDecoding` — sigue presente
  (nullable `Boolean`). Nuestro toggle MTP (default OFF) sigue OK.
- `Content.AudioBytes` / `Content.Text` / `Contents.of(...)` — sin
  cambio.
- `SamplerConfig(topK, topP, temperature, seed)` — sin cambio.

**APIs nuevas relevantes wireadas hoy**:
- **`Engine.setNativeMinLogSeverity(LogSeverity)`** — el setter
  público que faltaba en 0.12 para silenciar el
  `[litert_dispatch.cc:113] No dispatch library found` spam.
  Llamado con `LogSeverity.WARNING` una vez al cargar el engine →
  el ruido documentado como "Known noise" en README/PROGRESS a
  través de Fase 6 desaparece de logcat.
- **`Capabilities(modelPath).hasSpeculativeDecodingSupport()`** —
  API para preguntarle al modelo si trae embebido el drafter MTP.
  Lo logueamos al cargar para que futuros swaps de modelo dejen
  ver si MTP realmente aplica o es no-op silencioso.

**APIs nuevas que NO wireamos** (potencial para más adelante):
- `Session.runPrefill(List<InputData>)` + `runDecode()`: prefill
  incremental. Documentado desde 0.12 pero no ganamos mucho hasta
  que decode también sea streaming — sigue difiriendo.
- Tool calling (`Tool`, `ToolManager`, `ToolSet`, `ToolProvider`):
  no aplica a AST audio-only.
- `RepetitionPenaltyConfig`, `NoRepeatNgramConfig`,
  `SuppressTokensConfig`: útiles si vemos repeticiones en device;
  no lo hemos visto hasta ahora.
- `ThinkingConfig`: para modelos que emiten "thinking channel". El
  nuestro no.
- `LogSeverity.VERBOSE` / `DEBUG` — disponibles para revertir el
  silenciado durante debugging.
- `AudioStreamingEnabled` (streaming de audio DE ENTRADA): **sigue
  sin existir** públicamente en 0.15 (verifiqué que no hay setter
  en `EngineConfig` ni ninguna API que lo tome como parámetro).
  Confirmación definitiva de que Fase 6 tenía razón al descartarlo.

### Estado post-revert (versión estable)

- SDK vuelve a `0.12.0`. Imports + calls de `Engine.setNativeMinLogSeverity`
  y `Capabilities` (APIs 0.14+) REMOVIDOS del código para que compile
  contra 0.12.
- Flag `AstConfig.audioBackendGpu` preservado con default `false` +
  KDoc extendido documentando el requerimiento SDK ≥ 0.14 + modelo
  compatible. La cadena de attempts `(main, audio)` en
  `LiteRtGemmaAstEngine.load()` también se preservó — sirve tanto
  para 0.12 (skip la variante GPU-audio cuando el flag es false)
  como para futuros re-intentos.
- Ruido `[litert_dispatch.cc:113]` vuelve a estar presente en 0.12
  — sigue documentado como "Known noise" hasta que hagamos otro
  intento de upgrade.
- Toda la mejora funcional de Fase 6 permanece intacta: streaming
  AST/TTS default ON, chunker retune 1500/500, prompt sin
  transcripción con marcador `English:`, orden audio-after-text,
  meta-text filter con prefix window de 60 chars, Android TTS Fast
  mode toggle.

### Full-duplex mode (mismo commit del revert)

Se agrega un flag independiente del upgrade: `AstConfig.fullDuplexMode:
Boolean = false`. Cuando está `true`:

- `VadChunkingPipeline.handleAudioData` ignora el flag `ttsPlaying`
  compartido — el chunker sigue colectando audio y emitiendo chunks
  mientras Kokoro / Android TTS habla.
- No se llama `handleMuteRisingEdge()` al empezar el TTS → no hay
  flush del chunker + no hay reset del VAD/reassembler.
- Los bookends `beginUtterance` / `endUtterance` en `TtsAudioPlayer`
  siguen funcionando y siguen escribiendo el flag `ttsPlaying`
  compartido — la UI y las métricas ven el estado real de playback,
  solo dejamos de actuar sobre él en la ruta del mic.

Uso previsto: **modo conversacional estilo OpenAI** — el usuario
puede hablar continuo mientras las traducciones salen en serie.
**Requisito físico**: mic direccional / cardioide alejado del
speaker, O headphones. Con mic omnidireccional + speaker en la misma
sala, el mic captura el output del TTS y lo re-feedea a Gemma como
"speech".

Default `false` = comportamiento half-duplex de Fase 6 (baseline
seguro). Toggle en UI en la sección "3½. FASE 6 STREAMING" con
status text que explica el trade-off.

### Después del smoke test

Continúa el plan original: **consolidación de los 6 POCs en
`translator-android/`**. Los seis POCs se consolidan en una sola
app de producción:

- `audio-hw-check/` — validación de USB routing / A2DP pairing
- `audio-capture-poc/` — Oboe capture + JNI + ring buffer
- `vad-chunking-poc/` — Silero VAD + chunker
- `gemma-ast-poc/` — Gemma 4 E4B AST validation
- `gemma-pipeline-poc/` — el POC actual (Fases 4+5+6)

Estructura target: una app con las capas `audio/`, `vad/`, `chunker/`,
`pipeline/`, `ast/`, `tts/` copiadas verbatim del POC actual (que ya
las tiene todas cohabitando). Wiring del ViewModel a un flujo de
producción sin sliders/toggles de debug (o detrás de un `DEBUG` build
flavor). Store el modelo en `MANAGE_EXTERNAL_STORAGE` o considerar
Play Feature Delivery para el download post-install del Gemma
`.litertlm` de ~3.4 GB.

Fuera del scope de Fase 7 (para más adelante):
- Selector completo de las 54 voces Kokoro (hoy tenemos toggle
  Male/Female en UI post-cierre)
- Modelo Gemma fine-tuned para es-MX (para el issue de slang)
- Streaming input via `Session.runPrefill` si aparecen tiempos < 2 s
  en el device test
- Hardware unidireccional (mic + USB DAC speaker) para el issue de
  feedback físico

---

## Cierre de POC — validación final en device (agosto 2026)

Commit del cierre: **`c623e7a`** (full-duplex enforcement + male
voice + GPU-audio finding).

### Device test — pasado

Sesión de ~10 minutos de operación continua en Xiaomi 15T Pro con
Saramonic USB + JBL Go 4. **14/14 traducciones sin errores, sin
drops**. Todas las mejoras acumuladas funcionando en conjunto:

| Feature | Estado |
|---|---|
| Full-duplex mode | ✅ "VAD live (full-duplex, TTS)" confirmado en UI durante playback; sin mute |
| Voz masculina `am_michael` | ✅ activa por default |
| Android TTS fast mode | ✅ funcional como alternativa rápida |
| Streaming AST (Stage A) | ✅ token-by-token de Gemma |
| Streaming TTS (Stage B) | ✅ per-sentence de Kokoro |
| Chunker retuneado (1500/500) | ✅ shave de latencia intacto |
| Prompt sin transcripción + `English:` marker | ✅ extracción router funcionando |
| Meta-text filter (prefix 60 chars) | ✅ sin falsos positivos observados |

**Latencias medidas**:
- First token: ~1200 ms promedio
- First audio: ~3.4 s promedio
- **Mejora total end-to-end: 14 s → 3.4 s (4× improvement)**

### Issues conocidos pendientes (no blocking para uso de POC)

1. **Slang mexicano**: Gemma no entiende expresiones coloquiales.
   Ejemplo observado: "les dan las ganas" → "Les andalganas"
   (fonetizado en vez de traducido). Requiere fine-tune del modelo
   para es-MX; fuera de scope del POC.

2. **Meta-text narrativo**: Gemma a veces envuelve la traducción
   en estilo "It says, '...'" — el filtro de prefix 60 chars no lo
   atrapa porque "It says" no está en la lista de patterns y no
   parece preamble asistente estándar. Solución: agregar patterns
   nuevos ("it says", "the speaker says", "the person says") o
   pedirle explícitamente al prompt que no narre. Fix simple para
   la siguiente sesión.

3. **Kokoro lento en frases largas**: síntesis toma ~7–8 s en
   utterances multi-oración largas. Compensado parcialmente por
   Stage B streaming (usuario oye sentence 1 mientras 2..N
   sintetizan), pero el aggregate cost sigue alto.

4. **Voz masculina más rápida**: `am_michael` es agradable pero
   ~1.5–2 s/oración. Próxima sesión: probar `am_adam`, `am_liam`,
   `am_puck` para ver si alguno es notablemente más rápido sin
   pérdida de calidad.

### Hallazgo cerrado — GPU audio

Documentado a fondo en la sección de Fase 7. Resumen:

**No existe hoy para nadie**. Issue [litert-torch #1039](https://github.com/google-ai-edge/litert-torch/issues/1039)
confirma que `litert-torch export_hf` no exporta las secciones
`tf_lite_audio_encoder_hw` / `tf_lite_audio_adapter` /
`tf_lite_end_of_audio`. Incluso el modelo oficial de Google
(`gemma-4-E4B-it.litertlm`) tiene `backend_constraint: cpu` en la
sección de audio. **CPU audio a ~800 ms de prefill es el estado
del arte** en agosto 2026 para Gemma 4 on-device.

Scaffolding preservado (flag `audioBackendGpu` + attempt chain
`(main, audio)` en `load()`) para re-intentar cuando Google resuelva
el upstream: sería solo un bump de SDK + swap de modelo + flip del
flag.

### Estado del POC

**Versión funcional estable, lista para testing en campo con
hardware de producción.**

El POC en su estado actual cubre todo el pipeline speech-to-speech
ES→EN con:
- Latencia end-to-end mejorada 4× vs baseline pre-Fase-6
- Full-duplex para conversación estilo OpenAI (requiere hardware
  direccional o headphones)
- Fallback a Fast TTS cuando se prioriza velocidad sobre calidad
- Toggles en UI para revertir cualquier feature individualmente en
  runtime si aparece regresión

**Recomendación para uso en campo**: mic USB unidireccional
(cardioide, apuntando lejos del speaker) + speaker cableado USB
DAC. Esta combinación elimina tanto el issue de feedback físico
en full-duplex como los ~300 ms extra que introduce Bluetooth
A2DP en el playback.

### Siguientes pasos

1. **Sesión corta de fixes rápidos**: agregar patterns meta-text
   para el narrativo ("it says", etc.), probar voces masculinas
   más rápidas.
2. **Testing de campo**: usar el POC en escenarios reales de
   viaje (hotel check-in, restaurante, transporte) con hardware
   de producción. Documentar issues encontrados que el testing
   controlado no expone.
3. **Consolidación en `translator-android/`** cuando el testing
   de campo dé el go — copiar las capas `audio/`, `vad/`,
   `chunker/`, `pipeline/`, `ast/`, `tts/` verbatim + wiring de
   producción sin sliders de debug.

---

## Post-cierre — bug de repetición corregido + estado estable (agosto 2026)

Commit: **`1d4f2e5`** (fix streaming: cumulative-vs-delta bug + `am_adam`
voice default + narrative meta-text patterns).

### El bug

Durante el testing de campo post-cierre apareció un síntoma nuevo: la app
repetía la frase inicial y luego entraba en un ciclo intercalando
fragmentos viejos con nuevos, incluso con el speaker separado del mic
(descartando feedback físico).

### Root cause: cumulative-vs-delta en `translateStreaming`

Después de descartar las cuatro sospechas del pipeline de captura
(pre-roll del chunker, buffer de Oboe, reassembler residual,
acumulación del `AudioChunker` — todos correctos), descompilé el AAR de
LiteRT-LM 0.12.0 (`Conversation$JniMessageCallbackImpl` +
`Contents.toString`) y confirmé que **el SDK 0.12 emite mensajes
CUMULATIVOS por callback, no deltas**. Cada `Message.toString()` es el
texto completo decodificado hasta ese momento.

La implementación en `LiteRtGemmaAstEngine.translateStreaming` asumía
deltas y hacía `sb.append(msg.toString())` en cada callback — una
respuesta `"Hello world."` que llegaba como tres callbacks cumulativos
(`"Hello"`, `"Hello world"`, `"Hello world."`) se acumulaba como
`"HelloHello worldHello world."` en el buffer. El router luego
identificaba `.` como terminador de oración y emitía fragmentos
duplicados al bus. Chunk a chunk el patrón se veía exactamente como el
usuario reportó.

### Fix

Prefix-diff contra el buffer acumulado usando un helper allocation-free
`startsWithBuffer`. Si un mensaje futuro no empieza con lo ya
acumulado, fallback defensivo: se trata como delta y se loguea un WARN
una única vez para señalar el cambio de protocolo. Contrato de
`onToken(delta)` de la interfaz no cambia — todos los tests existentes
del router siguen válidos sin modificación.

Logs de diagnóstico añadidos:
- `translateStreaming: first piece '...' at Nms (protocol will be inferred on next callback)`
- `translateStreaming: drained N callback(s) → M chars`
- `translateStreaming: SDK message does not start with accumulated text — treating as delta protocol` (sólo si algún día el SDK cambia).

### Validación post-fix en device

Sesión de testing directo post-commit:
- **12+ traducciones exitosas** en una sola sesión, 0 repeticiones, 0
  errores del pipeline.
- Logs confirmando el protocolo cumulative:
  `drained 36 callback(s) → 28 chars` — 36 mensajes acumulativos
  colapsados a 28 caracteres únicos por diff. Sin el fix, esos 36
  callbacks habrían generado ~500+ caracteres duplicados en el bus.

### Estado estable actual del POC

| Métrica | Valor medido en device |
|---|---|
| First token | ~1100–1200 ms consistente |
| First audio | ~3.4 s end-to-end |
| Mejora total vs baseline | **4× (~14 s → ~3.4 s)** |
| Traducciones sin errores | 12+/12+ en sesión post-fix |
| Repeticiones de frase | **0** (bug cerrado) |
| Duración de operación continua | 10+ minutos sin crash |
| Full-duplex mode | Funcionando sin repeticiones |
| Voz default | `am_adam` (snappier que `am_michael` en JBL) |
| Calidad de traducción | Buena incluso con audio no perfecto |

### Fixes bundle en el mismo commit

Además del fix streaming:
- **Voz default `am_adam`** — reemplaza `am_michael`. En el JBL BT
  speaker suena más natural y con decays de fonema más cortos, lo que
  reduce latencia por oración de Kokoro. `am_michael` sigue
  seleccionable en el UI.
- **Patrones meta-text `"it says"`, `"he says"`, `"she says"`** —
  atrapan el caso de wrapping narrativo que Gemma a veces produce
  ("It says 'hello'.") y que los patrones anteriores no cubrían. El
  prefix window de 60 chars ya existente los filtra al inicio de la
  respuesta.

### Siguientes pasos (revisión post-fix)

Los siguientes pasos declarados en el cierre siguen válidos. La sesión
post-cierre agregó dos features de UX:

1. **Speed slider Kokoro** (rango 0.8x–1.5x, step 0.1, default 1.0x) —
   aplicado por oración sin restart del router; `speed > 1.0` reduce
   samples generados y por tanto latencia de Kokoro. UI slider live.
2. **Selector completo de voces masculinas americanas** — `am_adam`,
   `am_michael`, `am_echo`, `am_eric`, `am_fenrir`, `am_liam`,
   `am_onyx`, `am_puck`, `am_santa` + `af_heart` femenina. Filtrado
   contra `kokoroEngine.availableVoices` para mostrar sólo las que
   existan en el modelo cargado. Layout scrollable horizontal.

---

## Validación de voz + cierre de sesión (agosto 2026)

Commits: **`ae164d0`** (infra de speed slider + selector multi-voz) →
**`fe0a20f`** (defaults refinados post-A/B).

### Resultado del A/B en device

Después de probar las 9 voces masculinas americanas del catálogo en
device con el JBL BT speaker, el usuario eligió como default:

- **Voz**: `am_puck` — mejor cadencia natural + sin cola de reverb en
  el JBL + latencia por oración de Kokoro comparable a las más
  rápidas del set masculino.
- **Speed**: `1.2x` — ~20 % más rápido que baseline `1.0x` sin
  degradar audiblemente el timbre de `am_puck`. El slider queda
  expuesto (rango 0.8–1.5x) por si en algún cuarto específico la voz
  a 1.2x suena forzada.

### UI final

El selector de voz se simplificó a las **dos opciones principales**:
- **Male (Puck)** — `am_puck`, default
- **Female (Heart)** — `af_heart`, fallback

Layout `Row` con `weight(1f)` — igual al toggle de dos-botones
original antes de la expansión. Las otras 8 voces masculinas
(`am_adam`, `am_michael`, `am_echo`, `am_eric`, `am_fenrir`,
`am_liam`, `am_onyx`, `am_santa`) quedan removidas del selector. La
infraestructura de filtrado contra `kokoroEngine.availableVoices`
sigue en `GemmaPipelineScreen` — re-agregar cualquiera es cuestión
de una línea en el `allVoices: listOf(...)`.

El speed slider queda visible siempre en el mismo panel:
- Label live `"TTS Speed: 1.2x"`
- Rango `0.8x – 1.5x`, step `0.1` (6 posiciones intermedias)
- Aplicado por oración sin restart del router (via
  `TtsRouter.setSpeed`)
- Disabled cuando Kokoro no cargó o Fast mode ON

### Estado del POC — versión estable de campo

| Métrica | Valor final |
|---|---|
| Voz default | `am_puck` |
| Speed default | 1.2x |
| First token | ~1100–1200 ms |
| First audio | ~3.4 s end-to-end |
| Mejora vs baseline | 4× (~14 s → ~3.4 s) |
| Repeticiones de frase | 0 (bug cerrado en `1d4f2e5`) |
| Pipeline errors | 0 en sesiones sostenidas |
| Modos activos | Full-duplex + streaming AST + streaming Kokoro TTS + voz personalizada |

**El POC queda cerrado como versión estable de campo.** Todos los
features acumulados (audio-after-text, official AST prompt con
extracción `English:`, meta-text filter con prefix window de 60
chars + patrones narrativos, streaming AST via prefix-diff, streaming
Kokoro por oración, full-duplex, speed slider, selector Male/Female)
están validados en device y expuestos como toggles en la UI para
revertir cualquiera individualmente si aparece regresión durante
testing de campo.

---

## Full-duplex por default + AEC hardware (agosto 2026)

Con full-duplex validado en device (14+ traducciones exitosas, 0
errores, sesiones de 10+ min) y el timing de streaming ya
estabilizado, promovimos full-duplex a **default ON** y agregamos
**cancelación de eco hardware** para que el mic no re-capture el
audio que el propio speaker acaba de reproducir.

### Cambios

- `AstConfig.fullDuplexMode`: `false` → `true`. El toggle en UI
  permanece — half-duplex queda como fallback si el AEC no basta en
  algún cuarto muy reverberante.
- **Nuevo flag** `AstConfig.aecEnabled: Boolean = true`. Wired a dos
  capas de AEC que actúan al abrir el stream de captura:
  1. **HAL-level** (primaria): el `AudioEngine` C++ abre el stream
     Oboe con `InputPreset::VoiceCommunication` (equivalente AAudio
     de `MediaRecorder.AudioSource.VOICE_COMMUNICATION`) que engancha
     el procesador AEC/NS/AGC del HAL del dispositivo. En OFF, se
     usa el preset previo `Unprocessed` (Fase 2 baseline).
  2. **Java-side** (defensa en profundidad): al arrancar el
     capture, `AudioCaptureManager.attachAec()` crea un
     `android.media.audiofx.AcousticEchoCanceler` bindeado al
     `SessionId` que Oboe alocó (`SessionId::Allocate` en el
     `AudioStreamBuilder`). Si el framework reporta
     `AcousticEchoCanceler.isAvailable() == false`, o si el session
     id no es válido, se loguea WARN y se sigue solo con el AEC del
     HAL — la primera capa es suficiente en la mayoría de devices
     modernos.

### Por qué no usamos `AudioRecord` directamente

La petición inicial mencionaba `AudioRecord.audioSessionId` — pero
el pipeline usa **Oboe (NDK)**, no `AudioRecord` (Java). La
traducción correcta es:
- El equivalente de `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
  en Oboe es `InputPreset::VoiceCommunication` — mismo path HAL,
  mismo AEC.
- El `SessionId` de la stream Oboe (obtenido con
  `stream->getSessionId()` tras abrir con `SessionId::Allocate`) es
  el mismo tipo de identificador que `AcousticEchoCanceler.create`
  acepta, así que la segunda capa Java funciona igual que si
  fuera un `AudioRecord`.

### Plumbing

- `audio_engine.h/.cpp`: `start_capture` gana dos parámetros
  (`input_preset`, `allocate_session_id`) + método
  `capture_session_id()`.
- `jni_bridge.cpp`: `nativeStartCapture` firma extendida,
  `nativeCaptureSessionId` nuevo.
- `NativeAudioEngine.kt`: `startCapture(inputDeviceId, inputPreset,
  allocateSessionId)`, `captureSessionId()`. Nuevo objeto
  `InputPreset` con constantes `UNPROCESSED = 9`,
  `VOICE_COMMUNICATION = 7`, `VOICE_RECOGNITION = 6` — mirror del
  enum Oboe.
- `AudioCaptureManager.kt`: recibe `aecEnabled` en `start`, elige
  preset, aloca session id sólo cuando AEC está ON, ata/libera el
  effect Java, log de `aecAttached=` para diagnóstico. `stop()`
  llama `releaseAec()` antes de cerrar el stream — no releasear
  el effect fuga un handle nativo de audiofx.
- `GemmaPipelineViewModel`: nuevo `setAecEnabled(Boolean)` que
  reinicia el capture si está corriendo (el InputPreset se bindea
  al `openStream`, no se puede cambiar en caliente). `startPipeline`
  pasa `astConfig.aecEnabled` a `captureManager.start`.
- `GemmaPipelineScreen`: nuevo `SwitchRow` para AEC, con texto
  contextual sobre latencia añadida (~10-20 ms) y qué preset queda
  activo. Texto del switch de full-duplex actualizado para
  reflejar que el AEC ahora mitiga el feedback.

### Interacción con otros filtros

- **RMS pre-filter** (`AstConfig.rmsThreshold`): el AEC puede
  reducir sutilmente la amplitud del signal del mic. Si empieza a
  descartarse audio válido por RMS bajo después de prender AEC,
  bajar el threshold ANTES que apagar AEC — el trade-off
  típicamente favorece mantener AEC ON.
- **VAD (Silero)**: el modelo es robusto a cambios sutiles de
  ganancia. No debería requerir re-tuning.

### Estado actual del POC

| Feature | Default | Toggle UI |
|---|---|---|
| Full-duplex | **ON** ✅ | Sí |
| AEC hardware | **ON** ✅ | Sí |
| Streaming AST | ON | Sí |
| Streaming Kokoro | ON | Sí |
| Voz | `am_puck` | Sí |
| Speed | 1.2x | Slider |
| RMS filter | 500.0 | Slider (existente) |
| Meta-text filter | activo | (no toggle) |

Todos los toggles quedan en pie para revertir cualquiera
individualmente durante field testing. La combinación default
(full-duplex + AEC) es la que ahora recomendamos para uso en campo
con hardware estándar (mic omnidireccional + speaker en la misma
habitación); si el AEC del device específico no rinde bien, cambiar
mic a unidireccional + speaker cableado sigue siendo la ruta más
robusta (documentada en el cierre previo).

---

## WebRTC AECM software — POC para hardware externo (agosto 2026)

El AEC del HAL sólo procesa el path mic-interno ↔ speaker-interno.
Cuando el usuario conecta hardware externo (Saramonic USB al mic,
JBL Go 4 por BT al speaker), el HAL AEC no tiene referencia del
audio que sale por el path externo y el echo llega al mic sin
cancelación. Este POC agrega un **AEC por software** (WebRTC AECM)
que sí puede procesar cualquier combinación de hardware — toma el
PCM que enviamos al speaker como far-end reference y lo resta del
capture del mic.

### Investigación previa

Antes de codear, evalué las opciones disponibles:

| Librería | Cobertura | Distribución | Notas |
|---|---|---|---|
| [`theeasiestway/android-webrtc-aecm`](https://github.com/theeasiestway/android-webrtc-aecm) | Sólo AECM (mobile) | `.aar` prebuilt en el repo, 4 ABIs | ~128 KB. Wrapper Kotlin: `ru.theeasiestway.libaecm.AEC` |
| [`juha-h/libwebrtc`](https://github.com/juha-h/libwebrtc) | Sólo AECM | Fuente + `.so` | Sin releases, sin Maven |
| [`Yishiba/chromium_libwebrtc_audio_preprocessing_for_android`](https://github.com/Yishiba/chromium_libwebrtc_audio_preprocessing_for_android) | **AEC3** + AECM + AGC + NS + VAD | Snapshot WebRTC 2017, sólo armeabi-v7a prebuilt | Requiere recompilar de Chromium |

**No existe Maven Central artifact** para WebRTC AEC en Android —
todas las opciones requieren vendorear binarios en `app/libs/`.

### Decisión: POC mínimo con AECM (path 4 elegido por el usuario)

Vendoreamos el `.aar` de `theeasiestway/android-webrtc-aecm`
directamente (`app/libs/libaecm-release.aar`, ~128 KB, 4 ABIs
incluyendo `arm64-v8a` para el Xiaomi). API concreta:

```java
public class ru.theeasiestway.libaecm.AEC {
    public AEC(SamplingFrequency, AggressiveMode)  // 16kHz + HIGH
    public AEC farendBuffer(short[] farend, int nSamples)         // 160 samples/call
    public short[] echoCancellation(short[] nearend, int nSamples, int msInSndCardBuf)
    public AEC prepare()
    public void close()
}
```

### Arquitectura

Nuevo `audio/AecProcessor.kt` que wrappea la librería y maneja
tres complicaciones que la API cruda no cubre:

1. **Alineación de frames a 10 ms**. AECM requiere exactamente 160
   samples por llamada a 16 kHz. Ni el output de Kokoro (oraciones
   completas) ni el drain de Oboe (chunks variables) llegan
   alineados. Se usan dos `ArrayDeque<Short>` (uno far-end, uno
   near-end) y se drenan frames de 160 samples en cada llamada.
2. **Resample 24 → 16 kHz**. Kokoro emite a 24 kHz; AECM corre a 16
   kHz. Se hace linear interpolation al ratio 3:2. Cruda por
   estándares DSP pero adecuada para AEC — la referencia no necesita
   ser perceptualmente limpia, solo estar phase-aligned con lo que
   el speaker va a emitir.
3. **Thread-safety**. `TtsAudioPlayer.play()` corre en `Dispatchers.IO`
   y `VadChunkingPipeline.handleAudioData()` corre en
   `Dispatchers.Default`. WebRTC AECM no es thread-safe, así que
   ambos entry points sincronizan sobre un `aecLock` interno.

### Puntos de integración

- **`TtsAudioPlayer.play()`** — antes de `AudioTrack.write()`, llama
  `aec.bufferFarend(pcm, sampleRate=24000)`. El resampling ocurre
  dentro del processor.
- **`VadChunkingPipeline.handleAudioData()`** — después de la
  decimación 48→16 kHz y ANTES del reassembler, llama
  `aec.process(pcm)`. Silero + chunker ven el signal limpio.

Ambos son opt-in: la referencia al `AecProcessor` es `@Volatile` y
nula cuando el flag está OFF. Zero costo con el flag apagado.

### Config + UI

- `AstConfig.webrtcAecEnabled: Boolean = false` — off por default
  (POC).
- `GemmaPipelineViewModel.setWebrtcAecEnabled(Boolean)` — hot-swap
  sin restart del capture (a diferencia del HAL AEC que sí requiere
  reabrir el stream Oboe). Lazily construye el `AecProcessor` en el
  primer flip a ON.
- UI: nuevo `SwitchRow` bajo el HAL AEC, con contador live de
  `farend` / `nearend` frames procesados para diagnóstico.

### Caveats explícitos del POC

- **Delay hardcoded a 200 ms** (`AecProcessor.DEFAULT_DELAY_MS`) —
  medio del rango típico BT A2DP (150–300 ms). Auto-estimación
  desde timestamps pareados es out of scope.
- **Sólo path Kokoro instrumentado** — el path de Android system
  TTS (Fast mode) habla directo al audio stack del OS y no tenemos
  handle al PCM para usarlo como reference. Fast mode + WebRTC AEC
  es efectivamente no-op.
- **AECM ≠ AEC3**. AECM tolera ~10 ms de jitter de delay; BT A2DP
  jittera ±50 ms. **Cancelación esperada sobre BT: 30–50 %**;
  sobre USB DAC cableado: 80–95 %.
- **Overhead**: ~10 ms de procesamiento por frame de 10 ms, negligible.

### Cómo medir en device

1. Prender pipeline con hardware externo (Saramonic + JBL).
2. Toggle full-duplex ON, HAL AEC ON (típico), WebRTC AEC OFF.
3. Hablar una frase, dejar que Kokoro/Android TTS traduzca, observar
   si el mic re-captura el output como "nueva speech" (aparece
   chunk fantasma → traducción de traducción).
4. Toggle WebRTC AEC ON, repetir. Observar si el ciclo de feedback
   se corta o se atenúa.
5. Contadores UI: `farend` frames deben crecer cada vez que Kokoro
   habla; `nearend` frames deben crecer continuamente mientras el
   pipeline corre.

### Siguiente paso (si el POC muestra cancelación útil)

- Tunear delay per-device desde el sample-rate real de Oboe + un
  estimado de A2DP.
- Considerar upgrade a AEC3 (mejor tolerancia a jitter de delay)
  — requiere ~1 semana de trabajo (recompilar Chromium para
  arm64, escribir wrapper JNI desde cero).
- Si NO muestra cancelación útil sobre BT: revertir el flag a OFF
  por default (el `.aar` queda vendoreado pero inactivo) y volver a
  la recomendación de hardware cableado del cierre previo.

---

## AEC investigación — resultados y estado actual (20 agosto 2026)

Consolidación de los tres experimentos de AEC que corrieron en
paralelo esta semana (HAL, SW WebRTC, hardware mic).

### 1. HAL-level AEC (commit `d9a6f18`)

`InputPreset::VoiceCommunication` + `AcousticEchoCanceler` bindeado
al `SessionId` de Oboe. Validado en device:

- **Con hardware interno del Xiaomi 15T Pro (mic + speaker
  built-in): funciona.** Sin feedback loop en full-duplex — el HAL
  aplica AEC calibrado al par mic/speaker interno del device.
- **Con hardware externo (Saramonic USB + JBL Go 4 BT): NO
  funciona.** El HAL no tiene calibración para los paths
  acústicos externos. Además el intento de attach del efecto
  Java falla: `AcousticEchoCanceler.create()` sobre el `SessionId`
  que Oboe alocó **retornó `null`** — la ruta Java tampoco se
  activa cuando el capture no es del mic interno.

Conclusión: el HAL AEC queda como default (útil para el caso mic
interno), pero **no cubre el escenario de producción con
Saramonic + JBL**. Toggle en UI permanece para revertir si algún
device específico lo necesita.

### 2. WebRTC AECM software (commit `254ebd4`)

POC implementado, no probado en device todavía. Recap:

- `.aar` de `theeasiestway/android-webrtc-aecm` vendoreado en
  `app/libs/`.
- `AecProcessor` con resample 24 → 16 kHz + frame alignment 10 ms
  + delay hardcoded a 200 ms (típico BT A2DP).
- Hooks: far-end en `TtsAudioPlayer.play()`, near-end en
  `VadChunkingPipeline.handleAudioData()` post-decimación.
- Flag `AstConfig.webrtcAecEnabled` con hot-swap sin restart.

Pendiente: **device test sobre BT** — expectativa 30–50 %
cancelación (WebRTC AECM no tolera bien el jitter de A2DP). Si el
número real es útil, tunear delay per-device; si no, considerar
upgrade a AEC3 o quedarse con hardware wired.

### 3. Hardware mic — Cubilux MLC-10 pasivo cardioide

Probado el Cubilux MLC-10 (cardioide pasivo). **Rechazo lateral
insuficiente (~6–10 dB)** para eliminar feedback cuando el speaker
está cerca del mic — el patrón pasivo cardioide sólo atenúa off-
axis, no cancela.

Evaluando **Cubilux ENC (~$48)** con cancelación activa (DSP
integrado en el mic USB). Ventaja teórica: la cancelación ocurre
antes de que el audio salga del mic, así que no depende de que el
SW AEC (WebRTC o HAL) estime bien el delay de A2DP. Ideal para el
escenario Saramonic-clase-omni + JBL BT que ya sabemos que rompe
el HAL AEC.

### Pendientes concretos

1. **Device test WebRTC AECM sobre BT** — medir % de cancelación
   real, revisar contadores UI `farend` / `nearend`, ver si se
   corta el ciclo de "traducción de traducción".
2. **Resolver conflicto mic + USB hub** — reportado en paralelo,
   no bloquea el AEC test pero sí el testing sostenido con mic
   externo.
3. **Evaluar hardware ENC** (Cubilux ~$48). Si funciona limpio,
   simplifica el pipeline: sin necesidad de SW AEC + tolerante a
   speaker close-in.

### Decisión provisional

- HAL AEC: **default ON** (útil con mic interno, inocuo con
  externo).
- Full-duplex: **default ON** (commit `d9a6f18` — validado en
  device con hardware interno; con externo depende de resolver
  echo por HW o SW AEC).
- WebRTC AECM: **default OFF** (POC, awaiting device test).
- Recomendación de producción: sigue siendo hardware direccional
  (Cubilux ENC o similar) + speaker cableado USB DAC. Si el ENC
  rinde, el SW AEC pasa a ser opcional.

## Anti-eco en software — 3 capas (1 octubre 2026)

### Resultado de prueba previa: ModMic USB 2 + WebRTC AECM

Probado **ModMic USB 2** (cardioide + noise canceling) + **JBL Go 4
BT** + full-duplex ON. **El loop de feedback persiste con WebRTC AECM
ON y OFF** — el AECM no rinde con el jitter de A2DP (como se
anticipaba) y el patrón cardioide del ModMic no rechaza lo suficiente
con el speaker cerca. HAL AEC sigue funcionando sólo con mic + speaker
internos. Medición útil del logcat: **picos de voz del usuario
~28 000 vs chunks de eco ~13 000–18 000** (int16) — hay margen de
energía para separar, pero no basta por sí solo.

Conclusión: atacar el eco **después del ASR**, en texto, donde sí
tenemos información que el audio no da (el eco es inglés y es una
copia de lo que acabamos de decir). Tres capas independientes, cada
una con su flag. LiteRT-LM sigue en 0.12.0.

### Capa 1 — SKIP para audio no-español (`AstConfig.skipNonSpanish = true`)

- Con el flag ON, `activePrompt` agrega `skipNonSpanishInstruction`:
  si el audio no es español, Gemma responde exactamente
  `English: SKIP`. Con el flag OFF el prompt es byte-a-byte el de
  antes.
- `AstChunkRouter` normaliza (trim, sin puntuación/espacios,
  mayúsculas) y descarta si queda `SKIP` (o `ENGLISHSKIP`, para el
  legacy prompt / respuesta cruda sin extracción). Contador
  `totalSkippedNonSpanish`, log `Chunk discarded: non-Spanish audio`.
- **Streaming:** después de abrirse el gate `English:`, el router
  **retiene el escaneo de oraciones** mientras el contenido
  normalizado sea prefijo de `SKIP`. Si diverge ("Skip the line." →
  `SKIPTHELINE`) se libera y el loop alcanza las oraciones pendientes;
  si al cerrar el Flow el contenido es `SKIP`, se descarta el chunk
  entero. Resultado: `SKIP` **nunca** llega a `TranslationReady` ni a
  TTS (cubierto con test de tokens partidos `"SK" + "IP" + "."`).
- One-shot: el check corre sobre la respuesta cruda **antes** de la
  extracción del marcador, así un `SKIP` sin marcador no suma al
  contador `englishMarkerMissing`.

### Capa 2 — Filtro de eco por similitud de texto (`echoTextFilterEnabled = true`)

- Nuevo `EchoTextHistory` (paquete `ast`), propiedad del ViewModel
  para que sobreviva a los restarts del router. Se alimenta en
  `handleEvent(TtsAudioReady)` con `sourceText` — cubre Kokoro
  one-shot, Kokoro streaming y Android Fast TTS. Re-grabar el mismo
  texto refresca su timestamp (Kokoro streaming emite varios
  `TtsAudioReady` por oración). Cap 32 entradas.
- Antes de emitir cada `TranslationReady` (cada oración en streaming,
  el reply en one-shot y en el fallback sin marcador) se compara
  contra las oraciones habladas en los últimos `echoWindowMs =
  15_000`. Similitud por tokens normalizados = `max(Jaccard,
  contención)`, contención = |A∩B| / |A| sólo si el candidato tiene
  ≥ 4 tokens (atrapa ecos cortados por el borde del chunk sin tragarse
  un "Yes." legítimo). Umbral `echoSimilarityThreshold = 0.6`.
- Hit → descarte, contador `totalEchoDropped`, log `Translation
  discarded: echo of TTS output (sim=…) text='…' matched='…'`.
- En streaming, una oración-eco se salta sin tocar `pending`, así la
  última oración real sigue cerrando la utterance con `isFinal=true`.
- Limitación conocida: si el usuario repite literalmente en español
  algo cuya traducción coincide con lo que el TTS dijo hace < 15 s,
  se descarta. Aceptable para el caso de uso.

### Capa 3 — Slider de umbral RMS

- `rmsThreshold` expuesto en UI como slider 200–4000 (default 500,
  cuantizado a 50). Hot-swap vía `AstChunkRouter.setRmsThreshold` —
  sin restart del router (no cancela el chunk en vuelo).
- El router ahora loggea el RMS de **cada** chunk: `Chunk accepted:
  RMS … >= …` y `Chunk discarded: low RMS (… < …)`.
- Nota de calibración: voz ~28 000 pico, eco ~13 000–18 000 (picos;
  el RMS por chunk es bastante menor — calibrar con los logs).

### UI

- Toggles: **Skip non-Spanish audio**, **Echo text filter**, slider
  **RMS threshold**.
- Contadores en la tarjeta TRANSLATIONS: **Skipped non-Spanish**,
  **Echo dropped**, **Low RMS**. Se refrescan también en
  `EngineStatus` (los filtros descartan sin emitir `TranslationReady`).

### Tests

- `AstChunkRouterTest` +13: SKIP one-shot (con marcador, sin marcador
  con puntuación/minúsculas, flag OFF pasa intacto), SKIP streaming
  (tokens partidos con prompt oficial, legacy prompt), "Skip the
  line." no se descarta, traducción legítima pasa con todas las capas
  ON, eco match / no-match / expiración de ventana / flag OFF, eco en
  streaming deja la última oración real como final, slider RMS
  hot-swap.
- Nuevo `EchoTextHistoryTest` (9): similitud, contención, reply corto,
  expiración + poda, dedupe, cap, helpers de normalización SKIP,
  `activePrompt` intacto con flag OFF.
- Infra: el producer del router ahora usa un `producerDispatcher`
  inyectable (default `Dispatchers.Default`). Los tests del router
  corrían con la suscripción al bus en un hilo real → carrera con
  `advanceUntilIdle()` y fallos intermitentes ya presentes en HEAD.
  Con el dispatcher inyectado: 35/35 en 16 corridas seguidas (1 flake
  aislado en tests de hilo real con `Thread.sleep`, preexistentes).
- Además: el source set de tests **no compilaba en HEAD** (faltaba
  `import kotlinx.coroutines.cancel` en dos tests, `-peak` Int vs
  Short en `WavBuilderTest`) — corregido. Quedan 8 fallas
  preexistentes fuera del paquete `ast` (`TtsConfigTest` con defaults
  viejos, carrera en `TtsRouterTest`, `FrameReassemblerTest`,
  `SileroVadProcessorTest`) — sin relación con este cambio.

### Prueba en dispositivo (pendiente — Abraham)

ModMic, full-duplex ON, JBL cerca. Revisar contadores y:
`adb logcat -s AstChunkRouter TtsRouter VadChunkingPipeline`

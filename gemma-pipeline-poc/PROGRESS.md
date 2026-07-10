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

## Siguiente: Fase 7 — Consolidación en `translator-android/`

Post device validation + flip de defaults, los seis POCs se consolidan
en una sola app de producción:

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
- Selector de voz Kokoro en UI (v1 hardcoded `af_heart`)
- Modelo Gemma fine-tuned para es-MX (para el issue de slang)
- Streaming input via `Session.runPrefill` si aparecen tiempos < 2 s
  en el device test
- Hardware unidireccional (mic + USB DAC speaker) para el issue de
  feedback físico

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

## Siguiente: Optimización de latencia + estabilización

Antes de consolidar los 5 POCs en `translator-android/` (Fase 6),
abordar los 4 issues no-blocking en orden de impacto:

1. **Mute durante playback** + RMS pre-filter — elimina los dos ciclos
   (feedback loop + meta-texto)
2. **Streaming AST** (`AudioStreamingEnabled`) — reduce latencia primera
   palabra ~2 s
3. **Streaming TTS por oración** + pre-buffer en `AudioTrack` — recorta
   los 5 s de Kokoro a percepción ~1 s
4. **Producción**: mic unidireccional + USB DAC speaker — elimina BT
   latency y el feedback físico

Cuando esos 4 estén estables, los POCs se consolidan tal cual
(`audio/`, `vad/`, `chunker/`, `pipeline/`, `ast/`, `tts/`) en una
sola app de producción.

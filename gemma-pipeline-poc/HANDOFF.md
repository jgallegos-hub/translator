# HANDOFF — Tarea actual para Claude Code

> Este archivo es **solo el canal de instrucciones chat → Code**. No reemplaza `PROGRESS.md` ni `README.md` (esos siguen siendo la documentación del proyecto).
> Cada tarea nueva **reemplaza** el contenido de este archivo. Al terminar, Code documenta el resultado en `PROGRESS.md`/`README.md`, no aquí.

---

## Tarea: Anti-eco en software (3 capas)

**Contexto:** con ModMic USB 2 (cardioide + noise canceling) + JBL Go 4 BT + full-duplex ON, el loop de feedback persiste con WebRTC AECM ON y OFF. HAL AEC solo funciona con mic+speaker internos. Observado: picos de voz del usuario ~28,000 vs chunks de eco ~13,000–18,000. Se atacará en software con 3 capas, cada una con su feature flag. No tocar la versión de LiteRT-LM (seguimos en 0.12.0).

### Capa 1 (prioridad) — Descartar audio que no sea español (`SKIP`)
- Ajustar el prompt AST: si el audio **no es español** (p. ej. inglés del propio TTS), el modelo debe responder exactamente `English: SKIP`.
- `AstChunkRouter`: si la traducción normalizada (trim, sin puntuación, mayúsculas) es `SKIP`, descartar el chunk.
  - Contador `totalSkippedNonSpanish`.
  - Log: `Chunk discarded: non-Spanish audio`.
- **Streaming:** evaluar al cierre del Flow (o en cuanto el buffer sea un prefijo inequívoco de `SKIP`) para que `SKIP` **nunca** llegue a TTS. Cubrir con test.
- Flag: `AstConfig.skipNonSpanish = true`. Con flag OFF, el prompt y el comportamiento quedan exactamente como hoy.

### Capa 2 — Filtro de eco por similitud de texto
- Mantener historial de oraciones habladas por TTS (texto + timestamp), ventana ~15 s.
- Antes de emitir `TranslationReady`, comparar contra el historial con similitud por tokens (Jaccard / overlap de palabras, texto normalizado).
- Si similitud ≥ umbral → descartar, contador `totalEchoDropped`, log con la oración del historial que hizo match.
- Flags: `echoTextFilterEnabled = true`, `echoSimilarityThreshold = 0.6`, `echoWindowMs = 15_000`.

### Capa 3 — Slider de umbral RMS
- Exponer `rmsThreshold` en UI como slider (200–4000, default 500).
- Loggear el RMS de **cada** chunk (aceptado y descartado) para calibrar.
- Nota de calibración: voz ~28,000 pico, eco ~13,000–18,000.

### UI
- Contadores visibles: **Skipped non-Spanish**, **Echo dropped**, **Low RMS**.
- Toggles para `skipNonSpanish` y `echoTextFilterEnabled`.

### Tests
- SKIP en one-shot y en streaming (SKIP no llega a TTS).
- Filtro de eco: match, no-match, expiración de ventana.
- Sin regresión en traducciones legítimas (frases en español normales siguen pasando).

### Documentación (al terminar)
- `PROGRESS.md`: resultado de prueba ModMic + AECM (no resolvió el loop) y la estrategia de 3 capas implementada.
- `README.md`: nuevas flags y controles.
- Commit + push con mensaje descriptivo; reportar hash.

### Prueba en dispositivo (la hace Abraham después)
ModMic, full-duplex ON, JBL cerca. Revisar contadores y logcat filtrado:
`adb logcat -s AstChunkRouter TtsRouter VadChunkingPipeline`

# Versión estable — 10 noviembre 2026 (tarde): filtro de voz antes del traductor

Etiqueta git: **`stable-2026-11-10-gate`** · Servidor Fly.io: app **`traductor-t2c`**, release **v7**
(imagen `registry.fly.io/traductor-t2c:deployment-01M73RAWPE4E04M9EJ14V879SP`).
Parte de [`ESTADO-ESTABLE-2026-11-10.md`](ESTADO-ESTABLE-2026-11-10.md) (todo lo de ahí sigue vigente); este
documento describe sólo lo que se agregó encima.

Abraham la confirma estable tras la ronda r30 (música y video en inglés de fondo, hablando encima de Eric):
24 de 24 fragmentos suyos traducidos, 17 bloqueados antes de Haiku, sin callar frases suyas mal transcritas
como pasaba en r29, primer audio con mediana 0.98 s.

## 1. Qué cambió

```
… AEC3 → Silero VAD → chunker
  → en paralelo:  Moonshine (texto)
                  CAM++ (huella de voz, 192 dims) → similitud máx. contra el perfil de Abraham
  → similitud < 0.40  → BLOQUEADO: no llega al traductor (ni Haiku ni respaldo)
  → similitud ≥ 0.40  → filtro de líneas por volumen → POST /speak con "verified": true
Servidor: verified → prompt SYSTEM_VERIFIED (sin reglas de "cállate si suena raro / suena a inglés")
          sin verified → prompt SYSTEM de siempre (app vieja, filtro apagado o sin perfil)
```

- **Falla abierta:** sin modelo, sin perfil o con error en el filtro, el fragmento sigue como antes.
- **Por qué el prompt verificado:** las reglas para callar inglés y texto confuso (r27) ahora las cubre el
  filtro, y sí costaban frases reales: en la réplica de r29, Haiku calló 14–17 de 75 frases de Abraham con
  el prompt de siempre y 0 de 75 con el verificado (`tools/verified_bench.py`).
- El prompt verificado prohíbe reenviar frases previas: sin esa regla, cuando Abraham decía "la primera
  frase no salió", Haiku la repetía (2/5 → 0/10).

## 2. Componentes

| Pieza | Dónde |
|---|---|
| Fbank Kaldi en Kotlin + CAM++ en el ONNX Runtime 1.23.2 de la app | `app/.../gate/SpeakerGate.kt` |
| Decisión, umbral, enrolamiento, modo sombra/bloqueo | `app/.../gate/SpeakerShadow.kt` |
| Marca "es Abraham" hasta la petición al servidor | `app/.../gate/SpeakerVerified.kt`, `mt/ServerVoiceTranslator.kt` |
| Integración (en paralelo con Moonshine) | `app/.../ast/CascadeAstEngine.kt` |
| Prompt verificado | `backend/shared/interpreter_prompt.ts` (`SYSTEM_VERIFIED`) |
| Referencia en Python, perfil desde el set etiquetado | `tools/campp_features.py` (`profile`) |
| Evaluación / réplica | `tools/gate_eval.py`, `tools/verified_bench.py` |
| Modelo (no va a git) | `/sdcard/Download/gate/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` |
| Perfil de voz de Abraham (no va a git) | externalFiles `speaker_profile.json`; semilla en `/sdcard/Download/gate/` |

¿Por qué no sherpa-onnx en Android? Trae su propio ONNX Runtime 1.28.2 y chocaría con el 1.23.2 de la app.
El fbank propio da exactamente lo mismo que sherpa (coseno 1.0000), y el teléfono da lo mismo que la PC
(diferencia ≤ 0.0005 en 33 fragmentos de r28).

## 3. Parámetros y controles

- Umbral **0.40**; fragmentos < 0.4 s no se evalúan (pasan).
- Perfil inicial: 5 fragmentos "enroll" del set del 10-nov (27.7 s), generado en la PC.
- Hooks adb (`am start -f 0x20000000 -n com.travel2chicago.gemmapipeline/.MainActivity --ez …`):
  `speaker_enforce false|true` (sombra / bloqueo), `speaker_shadow false|true`, `enroll_speaker true|false`
  (+ `enroll_reset true`), `dump_chunks true|false`. El filtro carga solo al abrir la app si el modelo existe.
- Logcat: etiqueta `SpeakerGate` → `GATE sim=… [BLOCKED|WOULD_BLOCK] spkMs=… asr='…'`; `CASCADE … verified=…`.

## 4. Cifras (r28–r30, Xiaomi 15T Pro + ModMic + JBL)

| | similitud |
|---|---|
| Abraham, frases completas | 0.58 – 0.87 |
| Abraham, respuestas cortas ("sí" 1.5 s, "ok.", "claro que sí") | 0.52 – 0.66 |
| Música, video en inglés, eco de Eric, risas | 0.06 – 0.32 |
| **Abraham, colas tras una pausa** ("no, no.", "no no no", "ay!" en r30) | **0.14 – 0.19** ← falla |

- Costo: 52–382 ms por fragmento (~30 ms por segundo de audio), en paralelo con Moonshine; el primero tras
  arrancar es el más lento (357 ms). No agregó espera medible.

## 5. Límites conocidos

1. **Colas cortas de Abraham se bloquean.** Si hace una pausa al final de la oración, el final queda solo en
   el siguiente fragmento (~1.5 s, poca voz, sin contexto) y su huella no se parece a la suya. Abraham
   confirmó que eran suyas las de r30 y también las tres de r29 ("no sé qué es lo que me ha dicho",
   "ay no no no", "la misma"). Contradice la regla de no descartar nada que él diga → prioridad 1.
2. **Errores de Moonshine ahora se traducen** en lugar de callarse ("¿Antonar?" → "Anton-ar?",
   "producciones" por "traducciones").
3. **Fragmentos mezclados** (su voz + video) pasan completos si su voz domina; sólo el filtro de volumen
   quita la parte ajena.
4. El volcado de fragmentos se apaga si la app se reinicia, y encenderlo reinicia el traductor (puede
   perder la frase en curso).
5. 8 pruebas unitarias viejas de TTS/VAD fallan desde junio (valores por defecto cambiados); no son del filtro.

## 6. Cómo regresar

- A esta versión: `git checkout stable-2026-11-10-gate`; servidor
  `flyctl deploy --app traductor-t2c --image registry.fly.io/traductor-t2c:deployment-01M73RAWPE4E04M9EJ14V879SP`.
- Sólo quitar el bloqueo sin recompilar: `--ez speaker_enforce false` (vuelve a sombra y al prompt de siempre).
- A la versión anterior completa: ver [`ESTADO-ESTABLE-2026-11-10.md`](ESTADO-ESTABLE-2026-11-10.md) §7
  (el servidor v7 es compatible con la app anterior: sin `verified` usa el prompt de siempre).

## 7. Plan para la siguiente sesión (según los comentarios de Abraham tras r30)

Principio: nada que agregue latencia o invención entra sin medirse antes offline; si no ayuda de verdad, no va.

1. **Set etiquetado de colas cortas.** Grabar una ronda con el volcado encendido desde el inicio (antes de
   Start), con pausas a propósito a mitad y al final de oraciones, más ruido y video. Etiquetar
   fragmentos: suyo completo / cola suya / ajeno / risa. Sin esto no se puede decidir nada de lo siguiente.
2. **Recuperar las colas sin abrir la puerta al ruido**, comparar offline sobre ese set:
   - a) *Continuación:* un fragmento corto que empieza poco después de uno que pasó se evalúa junto con
     ese audio previo (misma persona si la huella del conjunto sigue arriba del umbral). Costo ~0 ms.
   - b) *Zona dudosa:* fragmentos cortos con similitud intermedia no se bloquean por la huella sola; van
     a Haiku marcados como "dudoso", con las frases previas como contexto, y Haiku decide si llevan
     traducción (idea de Abraham). Va en la misma llamada de siempre, así que no suma una llamada más; hay
     que medir que no invente ni repita la frase anterior.
   - c) *Cortar menos:* que el VAD espere un poco más cuando la oración no terminó. Ataca la causa, pero
     cuesta latencia: medir cuánta.
   - Criterio: recuperar sus colas sin dejar pasar más ajenos que hoy (0 de 17 en r30), y sin sumar más de
     ~100 ms al primer audio.
3. **Frases coladas sin sentido** (errores de Moonshine o restos ajenos dentro de un fragmento suyo):
   evaluar que Haiku omita sólo la parte sin sentido con el contexto de las frases previas, sin volver a
   callar frases reales (comparar con `tools/verified_bench.py`).
4. **Contexto de sesión para Haiku** (tema de la conversación: pruebas del traductor, mantenimiento,
   viaje) para corregir errores como "producciones"→"traducciones". Con cuidado: que el contexto sirva
   para entender, nunca para rellenar.
5. **Re-enrolar en el teléfono** con más segundos e incluyendo respuestas cortas y finales de oración;
   puede subir la similitud de sus fragmentos cortos.
6. Después: puntuación por línea para fragmentos mezclados, volcado que sobreviva reinicios, Servidor como
   modo predeterminado, arreglar las 8 pruebas viejas.

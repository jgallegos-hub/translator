# Versión estable — 10 noviembre 2026

Etiqueta git: **`stable-2026-11-10`** · Servidor Fly.io: app **`traductor-t2c`**, release **v6**
(imagen `registry.fly.io/traductor-t2c:deployment-01M73D5MAKY1AFTFXGYAAQJPRF`).

Abraham la considera la versión más estable hasta ahora: traducción natural en ~1 s, voz consistente,
respaldo local que funciona sin que se note la espera, y la única falla abierta está antes del
reconocimiento (risas, inglés de otros), que es lo que se ataca después de esta etiqueta.

## 1. Cómo funciona

```
Teléfono (Xiaomi 15T Pro, app Android)
  micrófono USB ModMic → Oboe 48→16 kHz → AEC3 (cancelador de eco) → Silero VAD → chunker
  → Moonshine Tiny-es (reconocimiento en el teléfono, ~0.1 s)
  → filtro de líneas por volumen (eco/ruido)            ─┐
  → POST /speak al servidor                               │ si falla: ML Kit / Gemma texto
                                                          │ + Kokoro en el teléfono (otra voz)
Servidor (Fly.io, Chicago, Deno)                          │
  → Claude Haiku 5.5 en streaming (prompt de intérprete + glosario)
  → sólo el texto entre <say>…</say>, cortado por oraciones
  → ElevenLabs Flash v2.5, voz "Eric", velocidad 0.90, 200 ms entre oraciones
  → audio PCM 24 kHz en streaming de vuelta
Teléfono → reproduce en la bocina JBL (Bluetooth), sirve de referencia al AEC3
```

Modo de la app: **"Servidor: Haiku + voz Eric (respaldo local)"** (`TranslationEngine.SERVER_VOICE`).
Hoy hay que elegirlo en la pantalla cada vez que se reinstala (el valor por defecto sigue siendo Auto).

## 2. Componentes y dónde viven

| Pieza | Archivo | Notas |
|---|---|---|
| Cliente del servidor | `app/.../mt/ServerVoiceTranslator.kt` | frames `[tipo][largo][datos]`; reproduce en el sink de Kokoro; espera 3 s al primer byte; calentamiento `GET /health?warm=1` |
| Respaldo local | `app/.../mt/OnlineTranslator.kt` (`FallbackTranslator`) | ML Kit < 10 palabras / Gemma texto ≥ 10 + Kokoro; deja el servidor 30 s sólo tras 2 fallas seguidas |
| Cascada y filtros | `app/.../ast/CascadeAstEngine.kt` | filtro de líneas, colapso de frase duplicada, volcado opcional de pedazos |
| Reproductor | `app/.../tts/TtsAudioPlayer.kt` | reactiva el micrófono sólo cuando la cola se vació + 800 ms |
| Configuración | `app/.../ast/AstConfig.kt`, `tts/TtsConfig.kt` | ver §3 |
| Servidor | `backend/relay/main.ts` | Haiku con segunda petición a 1.2 s, cortes por oración, mapa de pronunciación, calentamiento |
| Utilidades puras + tests | `backend/relay/say.ts`, `say_test.ts` | `SayFilter`, `takeClause`, `hedged` (10 tests) |
| Prompt y glosario | `backend/shared/interpreter_prompt.ts` | aquí se "entrena" al traductor |
| Despliegue | `backend/Dockerfile`, `backend/fly.toml`, `backend/README.md` | |
| Edge Function vieja | `backend/supabase/functions/translate` | modo "En línea" anterior; copia vieja del prompt |

## 3. Parámetros clave

| Parámetro | Valor | Por qué |
|---|---|---|
| Filtro de líneas normal | RMS 1800 | eco/ruido débil |
| Filtro si Eric sonó en el pedazo | RMS 3000 | eco ≤ 2911 (r24), voz de Abraham 3500–6800 |
| Filtro mientras AEC3 aprende (< 6 s de voz) | RMS 4500 | eco inicial 4020/3195 (r25) |
| Margen para reactivar el micrófono | cola + 800 ms | retraso Bluetooth de la JBL |
| Arranque tras bocina inactiva > 3 s | 300 ms de dither | primera palabra cortada |
| Haiku | `claude-haiku-5-5`, thinking off, effort low | Sonnet 2× más lento, 20× más caro |
| Segunda petición a Haiku | 1.2 s | picos > 2 s en 2/31 frases (r26) |
| Voz | ElevenLabs Flash v2.5, Eric `cjVigY5qzO86Huf0OWal`, velocidad 0.90 | elegida a oído |
| Kokoro (respaldo) | 4 hilos, Silero 1 hilo | medido bajo carga |

Todos los retrasos están calibrados para **Xiaomi 15T Pro + JBL por Bluetooth**; antes de producción hay
que medirlos por teléfono (ver `docs/plan-cascada-2026-10.md`).

## 4. Cifras medidas

- Primer audio desde que el teléfono manda el texto: **~0.95 s** de mediana (Fly, Wi-Fi o datos móviles).
- Habla continua (~35 s, r27): Eric empieza 1.2–1.6 s después de cada pedazo; el retraso **no se acumula**.
- Hablar encima de Eric (r26): 3/3 frases transcritas bien (muestra pequeña).
- Comentarios de Haiku en voz alta: 0/45 con `<say>` (antes 9/45).
- Inglés de otra persona (r28/r29, `tools/english_probe.ps1`): a volumen normal a ~1 m se descarta por
  volumen; fuerte o de cerca, con el prompt actual pasan 3 de 12 tipos de frase (9/30 en la réplica
  offline `tools/english_bench.py`), sin silenciar frases reales de Abraham (0/42).
- Costo: Fly ~US$3.69/mes (siempre encendida); Haiku ~US$0.00004 por frase; ElevenLabs con la cuenta
  gratuita de Abraham (no alcanza para uso diario).

## 5. Límites conocidos (lo siguiente a resolver)

1. **Todo lo que entra se trata como el español de Abraham**: sus risas, el inglés de otros (si llega
   fuerte) y restos de eco se transcriben y a veces se traducen. Plan: compuerta de audio antes de
   Moonshine (¿es habla? ¿es Abraham?) — `docs/research-audio-gate-2026-11.md`.
2. Lo que Haiku no entiende queda en silencio: una frase real mal transcrita también callaría.
3. El respaldo local habla con otra voz (Kokoro).
4. Pausas para pensar cortan la frase; las muletillas ("este") se vuelven palabras inventadas.
5. El modo Servidor no es el predeterminado; retrasos calibrados sólo para este teléfono y bocina.

## 6. Cómo compilar, desplegar y probar

- App: `gradle :app:assembleDebug` (Gradle 8.11.1 del cache, JDK de Android Studio; ver memoria de
  setup) e `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Requiere los modelos Moonshine en
  `/sdcard/Download/moonshine/` y `python tools/vendor_moonshine.py` antes de compilar.
- Teléfono: `/sdcard/Download/translator/server.json` = `{"url": "https://traductor-t2c.fly.dev", "token": "<token>"}`
  (el token está en `backend/.app_token`, fuera de git).
- Servidor: `cd backend && flyctl deploy --remote-only --ha=false`; secretos en Fly (ANTHROPIC_API_KEY,
  ELEVENLABS_API_KEY, APP_TOKEN). Tests: `cd backend/relay && deno test --allow-net say_test.ts`.
- Pruebas desde la PC: `tools/relay_client.py` (latencia y WAV), `tools/model_bench.py` (modelos),
  `tools/english_bench.py` (filtro de inglés), `tools/english_probe.ps1` (inglés por las bocinas),
  `tools/speed_ab.py` (velocidad de Eric).
- Rondas en el teléfono: capturar logcat con las etiquetas `AstChunkRouter TtsRouter GemmaPipelineVM
  TtsAudioPlayer CascadeAstEngine ServerVoice OnlineTranslator VadChunkingPipeline`.

## 7. Cómo regresar a esta versión

- Código: `git checkout stable-2026-11-10` (o crear rama desde la etiqueta), compilar e instalar.
- Servidor: `flyctl deploy --app traductor-t2c --image registry.fly.io/traductor-t2c:deployment-01M73D5MAKY1AFTFXGYAAQJPRF`
  (o redesplegar desde la etiqueta con `cd backend && flyctl deploy --remote-only --ha=false`).
- Prompt: está en el código del servidor; regresar el servidor regresa también el prompt.

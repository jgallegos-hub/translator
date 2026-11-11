# Base para pruebas en campo — 11 noviembre 2026

Etiqueta git: **`campo-base-2026-11-11`** · Servidor Fly.io `traductor-t2c` **release v10**
(imagen `registry.fly.io/traductor-t2c:deployment-01M75XDEQCZJ0VWFEX6RAF5RDX`).

Abraham la confirmó estable tras una plática de trabajo de 7 minutos (r34): 33 de 33 fragmentos suyos
traducidos, 0 callados, 8 ecos de Eric bloqueados, primer audio mediana 0.98 s (p90 1.12 s), sin respaldo
local, la app abre en modo Servidor sin tocar nada. A partir de aquí los cambios se deciden en **sesiones de
calibración** con su retroalimentación y se miden contra esta base.

## Qué es esta versión

Todo lo de [`ESTADO-ESTABLE-2026-11-10.md`](ESTADO-ESTABLE-2026-11-10.md) y
[`ESTADO-ESTABLE-2026-11-10-filtro-voz.md`](ESTADO-ESTABLE-2026-11-10-filtro-voz.md), más lo de hoy:

| Cambio | Dónde | Medido |
|---|---|---|
| Servidor (Haiku + Eric, respaldo local) es el modo por defecto | `ast/AstConfig.kt` | r34 |
| Volcado de fragmentos persiste tras reinicios y no reinicia el traductor | `GemmaPipelineViewModel.kt`, `CascadeAstEngine.kt` | r32–r34 |
| Haiku no repite palabras ya dichas cuando una oración llega partida | `interpreter_prompt.ts` | `tools/r31_bench.py`: 1/20 → 0/15 por caso |
| Nombres de Chicago sólo cuando suenan al mismo nombre (no inventar otro) | `interpreter_prompt.ts` | Giordano's 4/20 → 0/20 |
| Formas mal oídas de UPS ligadas a su equipo (nunca switchgear) | `interpreter_prompt.ts` | 128/128; en vivo 6/6 |

Filtro de voz: por fragmento completo, umbral 0.40 (sin escaneo por ventanas).

## Lo que se probó y no entró

- **Regla A** (dejar pasar 0.30–0.40 si hay una línea fuerte): +2 frases suyas, +6 ajenas → descartada
  (`tools/line_gate_eval.py`).
- **Escaneo de voz por ventanas** (recorta fragmentos mezclados antes de Moonshine): limpia la basura de video
  que se colaba en r32, pero suma ~65 ms de mediana y Abraham lo percibió. Guardado en la rama
  **`exp/speaker-scan`**; se retoma si en campo los fragmentos mezclados resultan frecuentes.

## Registro de errores

`tools/calibracion/errores.tsv`: cada error real con la capa donde nace (Moonshine, Haiku, filtro de voz,
filtro de volumen, reproducción, fragmento mezclado), lo que dijo, lo que se oyó, lo que dijo Eric, lo
esperado y el estado. Sirve para ver qué se repite más y decidir en qué elemento del sistema actuar. Plan de
registro y calibración: [`propuesta-registro-calibracion-2026-11.md`](propuesta-registro-calibracion-2026-11.md).

## Uso en campo (etiqueta `campo-v1-2026-11-11`, misma tarde)

Encima de la base se agregó lo necesario para llevarlo a la calle y al trabajo (probado en r35):

| Qué | Cómo funciona |
|---|---|
| **Sigue oyendo con la pantalla apagada u otra app al frente** | servicio en primer plano de tipo micrófono (`ListeningService`): notificación fija "Traductor escuchando" con botón **Detener**; se enciende con Start y se apaga con Stop. r35: frases traducidas con pantalla apagada y con otra app al frente |
| **Una sola copia del traductor** | `launchMode="singleTask"`: tocar la notificación desde otra app ya no abre una segunda copia (antes se cargaban dos traductores) |
| **Pantalla dividida** | entrar a pantalla dividida ya no recrea la pantalla (`configChanges`) |
| **Botón "✗ Esa no"** | arriba de todo mientras traduce; marca la última frase dicha por Eric en `events.tsv` para revisarla primero |
| **Bocina despierta** | cada 3 min de silencio, 1 s de tono de 20 Hz a −40 dBFS (antes sólo ruido a −84 dBFS, que no evitaba que la JBL se apagara). 25 Hz a −30 dBFS se oía "como ronroneo"; se bajó. No mueve el detector de voz (r35: 4 tonos, 0 reacciones). Si la bocina se apaga, queda como `device_disconnected` en `events.tsv` |
| **Registro de cada salida** | `index.tsv` ahora con `asrMs`, `mtFirstMs`, `totalMs`, motor usado (servidor o respaldo) y `verified`; `events.tsv` con inicio, fin, bocina conectada/desconectada y marcas ✗ |
| **Privacidad** | de lo bloqueado por el filtro de voz (otras personas, eco) sólo quedan números: sin audio ni texto |

En el teléfono de Abraham: batería de la app en "Sin restricciones" (hecho). El inicio automático no hace falta:
la app se abre a mano y el servicio la mantiene viva mientras traduce. Después de "Detener", MIUI puede cerrarla
para liberar memoria (r35); basta con volver a abrirla.

Después de cada salida: con el teléfono en el Wi-Fi de casa, Claude baja la carpeta del día
(`Android/data/com.travel2chicago.gemmapipeline/files/chunk_dump/<fecha_hora>/`) por adb y la analiza.

Pendiente: la página de revisión en la PC (marcar cada frase), y confirmar en campo que el tono mantiene la
bocina encendida.

## Cómo regresar a esta base

- App: `git checkout campo-base-2026-11-11`, compilar e instalar.
- Servidor: `flyctl deploy --app traductor-t2c --image registry.fly.io/traductor-t2c:deployment-01M75XDEQCZJ0VWFEX6RAF5RDX`.

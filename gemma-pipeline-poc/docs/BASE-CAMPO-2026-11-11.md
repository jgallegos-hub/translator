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

## Cómo regresar a esta base

- App: `git checkout campo-base-2026-11-11`, compilar e instalar.
- Servidor: `flyctl deploy --app traductor-t2c --image registry.fly.io/traductor-t2c:deployment-01M75XDEQCZJ0VWFEX6RAF5RDX`.

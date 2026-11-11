# Propuesta: registro y sesiones de calibración (borrador, 11 nov 2026)

Objetivo: pasar de rondas de laboratorio a **pruebas reales en campo** que se puedan medir, y convertir la
retroalimentación de Abraham en mejoras repetibles, del tipo que después sirvan para otros usuarios y oficios
(ver la visión de producto: herramienta personalizable, no traductor genérico).

Base: la versión que Abraham confirme como estable (app con filtro de voz por fragmento completo, Servidor por
defecto, relay v10).

## 1. Qué registrar de cada frase

Hoy ya existe el volcado de fragmentos (WAV + `index.tsv`). Para medir de verdad falta unir, en **una sola fila
por frase**, todo lo que pasó desde que habla hasta que Eric termina:

| Capa | Dato | Para medir |
|---|---|---|
| Audio | WAV del fragmento, duración, volumen por línea | reproducir y re-evaluar offline |
| Filtro de voz | similitud, bloqueado sí/no | frases suyas perdidas, ajenas que pasan |
| Moonshine | texto por línea con tiempos, ms | errores de transcripción ("azara", "sopeses") |
| Haiku | texto enviado, historial, `verified`, respuesta, ms al primer texto, segunda petición | errores de interpretación, silencios, repeticiones |
| ElevenLabs / bocina | ms al primer audio, **momento en que realmente sonó en la bocina**, duración | latencia real y frases que no sonaron (bocina dormida) |
| Contexto | modo, ronda, ruido de fondo (si se anota) | comparar condiciones |

Un **id por frase** que viaje del teléfono al servidor (en la petición `/speak`) para juntar ambos lados.
Métrica clave de latencia: **fin de su voz → primer sonido en la bocina** (hoy medimos pedazos sueltos).

## 2. Cómo da Abraham su retroalimentación

Tres niveles, de menor a mayor esfuerzo:

1. **En el momento (un toque):** un botón grande en la pantalla, "✗ esa no", que marca la última frase como mal.
   No interrumpe la conversación; lo detallado se revisa después.
2. **Al terminar (5–10 min):** una página de revisión en la PC con cada frase de la sesión: audio, lo que
   entendió Moonshine, lo que dijo Eric y lo que marcó el filtro. Por cada una se elige:
   - ✓ bien
   - ✗ transcripción (Moonshine oyó otra cosa) → escribe lo que dijo
   - ✗ traducción (Haiku interpretó mal) → escribe lo que quería decir
   - ✗ no era yo y se tradujo
   - ✗ era yo y no se tradujo / no sonó
   - ⚠ tardó demasiado
3. **Comentario libre** por sesión, como los que da hoy ("se siente apresurado", "la primera no salió").

Las marcas quedan en un archivo de etiquetas junto al volcado, el mismo formato que ya usan
`tools/line_gate_eval.py` y los benches.

## 3. Métricas por sesión

- **Cobertura:** de sus frases, cuántas se tradujeron y sonaron (meta: ninguna perdida).
- **Falsos positivos:** cuántas veces Eric dijo algo que él no dijo (meta: cero).
- **Errores por capa:** filtro de voz / Moonshine / Haiku / reproducción, para saber dónde corregir.
- **Latencia:** fin de su voz → primer sonido, mediana y p90; y cuántas veces pasó de ~2 s.
- **Tendencia:** las mismas métricas sesión tras sesión, para ver si cada cambio mejora o empeora.

## 4. Ciclo de calibración

```
sesión en campo → revisión de Abraham → análisis por capa → corrección propuesta
      ↑                                                            ↓
 nueva versión ←── decide Abraham ←── bench con sus casos reales (antes/después)
```

- Cada error marcado se vuelve un **caso de prueba** (como `tools/r31_bench.py`): la lista crece sesión a
  sesión y ningún cambio entra si empeora un caso que ya funcionaba.
- Cada corrección se guarda como **dato de un perfil**, no como regla suelta en el prompt:
  - *perfil de usuario:* huella de voz, ritmo y pausas (umbral del VAD), muletillas;
  - *perfil de oficio:* glosario, formas mal oídas ("sopeses"→UPS), nombres frecuentes
    (lugares, compañeros, equipos); se usa en Haiku y, para palabras clave, en Moonshine.
- Calibración inicial de un usuario nuevo = leer un guion de su oficio (como `tools/rounds/r31_guion_colas.md`)
  + enrolar su voz con frases largas y cortas, en silencio y con ruido.

## 5. Cuidado con la privacidad en campo

En el trabajo el micrófono también oye a compañeros. El filtro de voz los bloquea, pero el volcado hoy guarda
**todos** los fragmentos, incluidos los suyos. Propuesta para campo:
- guardar audio sólo de los fragmentos que pasan como de Abraham; de los bloqueados, sólo los datos (similitud,
  duración), sin audio ni texto;
- el audio vive en el teléfono / PC, nunca en git (como hoy), y se borra al terminar la revisión;
- si se quiere evaluar el filtro con voces de compañeros, pedirles permiso antes.

## 6. Orden sugerido

1. Abraham confirma la versión estable → se etiqueta como base de campo.
2. Registro unificado por frase + id compartido teléfono/servidor + tiempo real de sonido en la bocina.
3. Página de revisión con las marcas (nivel 2); el botón en pantalla (nivel 1) después.
4. Primera sesión en campo + revisión + primer ciclo de calibración.
5. Con lo aprendido: esquema de perfiles (usuario / oficio).

Pendientes conocidos que conviene resolver antes o durante: la primera frase tras mucho silencio no suena
(bocina Bluetooth dormida); "burrito de azara" (glosario por dominio).

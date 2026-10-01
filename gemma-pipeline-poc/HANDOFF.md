# HANDOFF — Tarea actual para Claude Code

> Este archivo es **solo el canal de instrucciones chat → Code**. No reemplaza `PROGRESS.md` ni `README.md` (esos siguen siendo la documentación del proyecto).
> Cada tarea nueva **reemplaza** el contenido de este archivo. Al terminar, Code documenta el resultado en `PROGRESS.md`/`README.md`, no aquí.

---

## Tarea: Cierre de sesión (Oct 1, 2026) — solo documentación

No tocar código. Solo actualizar documentación y hacer commit + push.

### PROGRESS.md
Agregar o actualizar una sección **"Estado actual / Próximos pasos"** con:

- **Estado:** capas anti-eco implementadas en `2db432c`, **sin prueba en dispositivo todavía**. Sesión en pausa.
- **Siguiente (prueba en dispositivo, ModMic + full-duplex ON + JBL cerca):**
  1. Ronda 1: capas 1 y 2 ON, RMS 500 → ¿se corta el loop? anotar los 3 contadores.
  2. Ronda 2: solo capa 1.
  3. Ronda 3: solo capa 2.
  4. Calibrar el slider RMS con el RMS por chunk del log (no con picos).
  5. Falsos positivos: frases normales y con slang; revisar que no se descarten traducciones legítimas y medir si cambia la latencia del primer audio.
- **Después de la prueba:** arreglar las 8 fallas de tests que ya existían (`TtsConfigTest`, `TtsRouterTest`, `FrameReassemblerTest`, `SileroVadProcessorTest`).
- **Pendientes que siguen abiertos:** conflicto host/device entre el mic y el hub USB; slang mexicano; lentitud de Kokoro en oraciones largas.
- **LiteRT-LM:** seguimos en 0.12.0. No actualizar a 0.17.x. Esperar a 0.18 estable (PR #3749: audio encoder en GPU durante streaming, hoy solo en nightly).

### README.md
Solo si algo de lo anterior falta en la sección de estado; no duplicar.

### Commit
Mensaje: `docs: session close Oct 1 — anti-echo pending device test`. Incluir este HANDOFF.md. Reportar el hash.

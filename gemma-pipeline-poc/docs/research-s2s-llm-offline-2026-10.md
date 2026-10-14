# Research: voz-a-voz on-device y LLM offline "tipo Claude" (14 octubre 2026)

Pregunta (Abraham, tras el A/B de la cascada): ¿hay traducción voz-a-voz
end-to-end usable en el teléfono? Si usamos Gemma, ¿por qué no "Claude pero
offline"? Buscamos velocidad y calidad.
Marcas: [V] verificado en fuente primaria · [I] inferido/secundario · (>6m) fuente antigua.

## Resumen
- **No hay S2ST abierto en 2026 que supere a nuestra cascada en Android.**
  Hibiki-Zero (Kyutai, feb-2026, 3B, ES→EN con voz) tiene lag medio 5.6 s,
  pesos CC-BY-NC-SA y sólo GPU NVIDIA [V]. Seamless: CC-BY-NC [I]. StreamSpeech:
  investigación [V]. Omni (MiniCPM-o, Qwen3.5-Omni): demasiado grandes [I].
  Live Translate de Pixel/Samsung/Apple: sin API [I]. → Revisar en 6–12 meses.
- **Claude no existe offline**; lo más cercano en el teléfono es la familia
  Gemma 4. La palanca de velocidad es **Gemma 4 E2B + MTP**, no cambiar de familia.
- Cloud opcional (Claude Haiku 4.5 / Gemini Flash-Lite): TTFT ~0.15–0.6 s,
  frase corta ~0.5–1.2 s por red móvil [I]; requiere consentimiento y conexión.

## LLM de texto on-device (LiteRT-LM, GPU S26 Ultra) [V]

| Modelo | Prefill | Decode | TTFT | Tamaño |
|---|---|---|---|---|
| Gemma 4 E4B | 1 293 tok/s | 22 tok/s | 0.8 s | 3.65 GB |
| **Gemma 4 E2B** | 3 808 tok/s | 52 tok/s | **0.3 s** | 2.58 GB |

- MTP (decodificación especulativa): E2B 51→66–92 tok/s, E4B 22→37–49 tok/s
  ("hasta 2.2x", blog Google 19-may-2026) [V].
- LiteRT-LM permite guardar/restaurar el KV cache de una sesión → precalcular
  el prompt fijo [V].
- **Gemini Nano (ML Kit GenAI Prompt API): el Xiaomi 15T Pro está en la lista
  oficial (nano-v2)** [V]; offline vía AICore, sin peso extra; sin cifras de
  tok/s; posibles cuotas [I].
- TranslateGemma 4B: Gemma 3, sin build oficial LiteRT-LM, no más rápido [I].
- Qwen3.5 0.8–4B (Apache-2.0), Llama 3.2, Phi-4-mini, SmolLM3: más débiles en
  español coloquial y sin la ruta GPU+MTP de LiteRT [I]. NLLB: CC-BY-NC.
  Hunyuan-MT-7B / Seed-X-7B: demasiado grandes.

## Advertencia local (nuestro stack)
- La app usa **LiteRT-LM 0.12.0** y el export `gemma4_4b_v09…thinking.litertlm`.
  En Fase 7 probamos 0.15.0 y lo revertimos (primer token 1.17→2.0 s, dos OOM,
  audio GPU nunca activo) — ver PROGRESS.md. `AstConfig.mtpEnabled` existe pero
  nuestro export lo ignora. **MTP/E2B implica subir LiteRT-LM y descargar el
  export E2B de litert-community** (2.58 GB): medir de nuevo, ya que ahora sólo
  usamos Gemma en modo texto (la regresión de Fase 7 fue en la ruta de audio).

## Qué probar (de más a menos factible)
1. Gemma 4 **E2B** (+ MTP) como traductor de texto: TTFT esperado ~0.3 s,
   traducción completa ~0.5–0.8 s; validar calidad con el corpus F0 (slang).
   Si aguanta, podría reemplazar también a ML Kit en frases cortas.
2. Reusar el KV del prompt fijo / prompt mínimo / tope de tokens: −100–300 ms [I].
3. Streaming hacia Kokoro por cláusula mientras el LLM genera (mayor ganancia
   en primer audio de frases largas, ~0.5–1.5 s) [I].
4. Medir Gemini Nano (Prompt API) en el 15T Pro.
5. Modo nube opcional (Haiku / Flash-Lite) detrás de un interruptor de privacidad.
6. No invertir ahora en S2ST end-to-end.

Ganancia combinada esperada (1–3): primer audio p50 de ~1.3–2.6 s a ~0.8–1.5 s [I].

## Fuentes
kyutai.org/blog/2026-02-12-hibiki-zero/ · github.com/kyutai-labs/hibiki-zero ·
huggingface.co/kyutai/hibiki-zero-3b-pytorch-bf16 · github.com/kyutai-labs/hibiki ·
github.com/ictnlp/StreamSpeech · arxiv.org/pdf/2406.03049v1 · aiwiki.ai/wiki/seamless_m4t ·
arxiv.org/pdf/2604.27393 · ai.google.dev/gemma/docs/capabilities/audio ·
huggingface.co/litert-community/gemma-4-E2B-it-litert-lm ·
huggingface.co/litert-community/gemma-4-E4B-it-litert-lm ·
developers.google.com/edge/litert-lm/overview ·
developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/ ·
developers.google.com/ml-kit/genai ·
android-developers.googleblog.com/2026/07/android-on-device-inference.html ·
blog.google/innovation-and-ai/technology/developers-tools/translategemma/ ·
huggingface.co/google/translategemma-4b-it · huggingface.co/Qwen/Qwen3.5-0.8B ·
kunalganglani.com/blog/llm-api-latency-benchmarks-2026.md · telnyx.com/llm-library/gemini-2.5-flash

# Research: servidor propio + TTS en la nube (16 octubre 2026)

Objetivo (reflexión de Abraham): que cualquier teléfono sirva; mover traducción + voz a un servidor
propio que devuelva audio en streaming a varios clientes, con primer audio ~1 s tras dejar de hablar.
Marcas: [V] fuente primaria · [I] inferido/secundario · ⚠ >6 meses. Precios al 2026-10-16.
Supuesto: ~9 000 caracteres en inglés por hora de conversación; uso personal 1–2 h/día.

## 1. TTS comerciales con streaming
| Proveedor | Primer audio | US$/M caracteres | US$/hora | Gratis |
|---|---|---|---|---|
| Inworld TTS‑2 Flash | ~50 ms P90 (vendor) [V] | 15 (10 en plan Creator) [V] | 0.09–0.14 | ~70 min |
| ElevenLabs Flash v2.5 | ~75 ms de modelo [V] | 40 [V] | 0.36 | 20k car/mes |
| Cartesia Sonic 3.6 | <90 ms (marketing) [V] | 45–65 [V] | 0.41–0.59 | 20k/mes |
| Deepgram Aura‑2 | 90 ms TTFB (blog) [V] | 27–30 [V] | 0.27 | US$200 crédito |
| Google Chirp 3 HD | no publicado | 30 [V] | 0.27 | ~1M car/mes [I] |
| Azure Neural/HD Flash | no publicado | 15 [V] | 0.14 | 0.5M car/mes |
| Amazon Polly Gen./Neural | no publicado | 30 / 16 [V] | 0.27 / 0.14 | 12 meses [I] |
OpenAI tts: posible retiro en ene‑2027 según fuente secundaria [I] → descartado. Inworld: quejas de
subidas de precio [I]. Calidad [I]: Inworld/ElevenLabs/Cartesia gama alta; Deepgram/Azure/Polly más
"IVR"; Kokoro ≥ Azure/Polly. Pedir PCM 24 kHz y codificar Opus en el servidor.

## 2. Kokoro hospedado
- **DeepInfra**: US$0.62/M caracteres (~US$0.006/hora), 0.16 s de latencia (OpenRouter) [V], API
  compatible con OpenAI; voz am_puck y streaming intra‑frase por verificar [I].
- Replicate ~US$0.65/M [I ⚠]. Kokoro‑FastAPI autohospedado: GPU 4060 Ti 35–100× tiempo real,
  ~300 ms primer audio [V ⚠]; CPU de servidor ≈ nuestro PC (RTF ~0.5) → no acelera.

## 3. Dónde hospedar
- **Fly.io tiene región `ord` (Chicago)** [V]: shared‑cpu‑1x 512 MB US$3.69/mes; 2x 2 GB US$13.39;
  performance‑1x US$33. Ya no ofrece GPU.
- Hetzner EE. UU. subió precios (jun‑2026): CPX11 US$20.49/mes [V].
- GPU: Modal L4 US$0.80/h, RunPod L4 US$0.59/h, Cloud Run L4 ~US$0.67/h [V]; encendida todo el mes
  US$430–580; serverless con arranque en frío de segundos [I] → rompe la meta de 1 s.
- Red [I]: `ord` añade <10–20 ms a un usuario en Chicago; un proceso siempre vivo con keep‑alive/
  HTTP/2 hacia Anthropic debería eliminar casi todo el +0.35–0.4 s que hoy agrega Supabase.

## 4. Diseño de streaming
Teléfono ↔ servidor por WebSocket persistente: el teléfono manda el texto de Moonshine; el servidor
hace streaming de Haiku → chunker por cláusula (coma, punto, "and" o 6–8 palabras) → TTS en
streaming → audio PCM16 24 kHz (Wi‑Fi) u Opus 20 ms 24–32 kbps (datos móviles) de vuelta, con número
de secuencia por enunciado y cancelación. Referencias: Pipecat (benchmarks públicos; ~1 s voz a voz
reportado [I]), LiveKit Agents. Presupuesto estimado: Haiku directo ~0.5 s + primera cláusula
0.15–0.25 s + TTS 0.05–0.2 s + red 0.05 s + Bluetooth 0.1–0.2 s ≈ **0.9–1.2 s** [I].

## 5. Recomendación (del research; pendiente de validar con mediciones)
1. **Relay en Fly.io `ord` (US$4–13/mes) + Kokoro vía DeepInfra (am_puck)**: conserva la voz,
   < US$1/mes de TTS a escala personal, baja complejidad. Medir primer audio real y voces.
2. Mismo relay + Inworld TTS‑2 Flash o ElevenLabs Flash como TTS más rápido/respaldo (voz distinta,
   requiere A/B de oído). Deepgram Aura‑2 como respaldo con crédito gratis.
3. Kokoro autohospedado en CPU del relay: independiente, pero no más rápido que el teléfono.
4. Kokoro en GPU dedicada: sólo con decenas de usuarios simultáneos.
Pendiente de verificar: precios oficiales de OpenAI TTS, Railway/Render/DigitalOcean, soporte Opus,
streaming y voces de DeepInfra.

## Fuentes
elevenlabs.io/pricing/api · elevenlabs.io/blog/meet-flash ⚠ · cartesia.ai/pricing · deepgram.com/pricing ·
developers.deepgram.com/docs/text-to-speech-latency · inworld.ai/pricing · cloud.google.com/text-to-speech/pricing ·
azure.microsoft.com/en-us/pricing/details/speech/ · aws.amazon.com/polly/pricing/ · openrouter.ai/hexgrad/kokoro-82m ·
deepinfra.com/hexgrad/Kokoro-82M · github.com/remsky/Kokoro-FastAPI ⚠ · gigagpu.com/kokoro-tts-latency-by-gpu/ ⚠ ·
fly.io/docs/about/pricing/ · docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/ ·
modal.com/pricing · runpod.io/pricing · cloud.google.com/run/pricing · pipecat.ai/benchmarks ·
reddit.com/r/LocalLLaMA/comments/1oqe8o2 · arxiv.org/html/2603.05413v1 ⚠ · gradium.ai/content/best-low-latency-tts-apis-2026 ·
forasoft.com/blog/article/openai-realtime-api-pricing · reddit.com/r/generativeAI/comments/1sf4rs8

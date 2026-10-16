# Research: TTS en GPU/NPU del Dimensity 9400+ y alternativas (16 octubre 2026)

Pedido de Abraham: con Haiku quitando carga, probar Kokoro en la GPU e investigar los otros
motores. Marcas: [V] fuente primaria · [I] inferido · (>6m) antigua.

## Medido en el teléfono (KokoroBench, `tools/logs/kokoro_bench_nnapi.log`)
| Variante | RTF | Frase de 4 palabras |
|---|---|---|
| CPU fp16, 4 hilos (actual) | **0.45** | **715 ms** |
| NNAPI, modelo fp32 relajado a fp16 | 0.91 | 1560 ms |
| NNAPI, fp32 | 0.97 | 1710 ms |
| NNAPI, CPU_DISABLED | 0.90 | 1595 ms |
| NNAPI, modelo fp16 | no carga (`AddNnapiSplit`) | — |
NNAPI duplica el tiempo: el grafo queda partido (sin LSTM/ConvTranspose/STFT) y paga
transferencias. Además está deprecado desde Android 15 [V]. **Descartado.**

## GPU/NPU para Kokoro
- ORT WebGPU EP: Android vía Vulkan en docs, pero no viene en el AAR de Maven; sin pruebas con Kokoro [I].
- LiteRT `litert-community/Kokoro-82M` (sep-2026): 3 grafos fp32 de forma fija; en GPU OpenCL
  (Pixel 8a) no carga; en NPU sólo un demo de longitud fija [V]. Bloqueos: FFT, LSTM, atención >4D.
- MediaTek NeuroPilot vía LiteRT soporta el MT6991 [V], pero no hay grafo de Kokoro que compile.
- Conclusión: **ninguna ruta GPU/NPU madura para Kokoro en 2026.**

## Alternativas
- **Kokoro con decoder en ventanas** (re-exportar predictor + decoder por separado; decoder
  convolucional en ventanas de ~0.5 s con solapamiento y crossfade): primer audio ~0.3–0.35 s
  sin importar el largo [I]. El vocoder es ~81% del tiempo [V]. 2–4 días; requiere bajar los
  pesos PyTorch (Apache-2.0) y re-exportar.
- **Supertonic 3** (ONNX, mismo ORT; ~2.5× más eficiente que Kokoro según Picovoice [V];
  pesos OpenRAIL-M con restricciones de uso; validar inglés americano).
- **Pocket TTS** (streaming nativo; ~200 ms en M4 [V] pero 1.7 s de primer audio en el banco de
  Picovoice [V]; Pocket-TTS-LiteRT con ejemplos Kotlin; bug de calidad del decoder Mimi en GPU Mali,
  misma familia que nuestra Immortalis; CC-BY).
- Kitten (más lento que Kokoro) y Piper (peor calidad) descartados; sherpa-onnx "gpu" = sólo CUDA.

## Fuentes
huggingface.co/litert-community/Kokoro-82M · onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html ·
developer.android.com/ndk/guides/neuralnetworks/migration-guide ·
onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html ·
developers.google.com/edge/litert/next/mediatek · github.com/kyutai-labs/pocket-tts ·
huggingface.co/mlboydaisuke/Pocket-TTS-LiteRT · huggingface.co/Supertone/supertonic-3 ·
picovoice.ai/blog/on-device-tts/ · arxiv.org/html/2610.00063v1 · github.com/k2-fsa/sherpa-onnx/issues/2443 (>6m)

# Research: compuerta de audio antes del ASR (10 noviembre 2026)

Problema (Abraham, tras r28/r29): todo lo que entra al micrófono se trata como su español; sus risas, el
inglés del interlocutor y el eco de Eric se transcriben y traducen. La raíz está antes de Moonshine:
decidir (1) ¿es habla? y (2) ¿es Abraham? Marcas: [V] fuente primaria · [I] inferido · ⚠ >6 meses.

## 1. ¿Es Abraham? Verificación de hablante
- **sherpa-onnx** (Apache-2.0, activo) trae API Kotlin: `SpeakerEmbeddingExtractor` + `SpeakerEmbeddingManager`
  (`add`, `verify`, `search`) [V]. Trae su propio onnxruntime → evitar choque con nuestro ORT 1.23.2
  (usar sólo sherpa, o correr el .onnx con nuestro ORT y calcular fbank nosotros) [I].
- **3D-Speaker CAM++ en_voxceleb**: 29.6 MB, Apache-2.0, EER 0.65 % en VoxCeleb1-O, RTF 0.013 en un hilo
  (más del doble de rápido que ECAPA/ResNet34) [V ⚠]. Estimado 15–40 ms por pedazo de 3 s en el teléfono [I].
- Pedazos cortos: con 2 s el error se duplica o triplica (ECAPA 1.95 %, ResNet34 2.12 %) pero es usable [V ⚠].
- Alternativas: WeSpeaker ResNet34 26.5 MB (licencia de VoxCeleb CC-BY, revisar uso comercial) [V];
  ECAPA SpeechBrain ~80 MB sin ONNX oficial [I]; Picovoice Eagle comercial con validación en línea.
- **Idioma cruzado**: el mismo hablante en otro idioma degrada la verificación (5.19 % vs 0.88 % de EER) [V]
  → enrolar a Abraham también en inglés.

## 2. ¿Es habla? Risa, tos, ruido
- **YAMNet** (MediaPipe Audio Classifier, Kotlin): ~4 MB, Apache-2.0, 12.3 ms por ventana de ~1 s en Pixel 6;
  clases Speech 0, Laughter 13, Giggle 15, Snicker 16, Belly laugh 17, Chuckle 18, Cough 42 [V].
  Hablar riendo da Speech y Laughter a la vez → decidir por proporción de ventanas [I].
- EfficientAT mn04 (MIT, 1M parámetros, sin ONNX oficial) como alternativa más precisa. CED: GPL, descartado.

## 3. Idioma (LID)
- VoxLingua107 ECAPA (~80 MB) falla con acento extranjero; Whisper-tiny LID excede el presupuesto de
  latencia → sólo como complemento, no como compuerta.

## 4. Diseño propuesto
- Traducir si `pSpeech·(1−pRisa) ≥ τ1` **y** `similitud(Abraham) ≥ τ2` **y** `similitud(Abraham) − similitud(Eric) ≥ m`.
- **Enrolar a Eric como perfil negativo** (voz fija, fácil de rechazar) [I].
- Enrolamiento: 8–12 muestras de 3–5 s, en silencio y con ruido, ~30 % en inglés; guardar varias y usar max/top-k [I].
- Referencia de arquitectura: Personal VAD de Google (sin pesos públicos) [V ⚠].
- Evaluación: 150–300 pedazos reales etiquetados (positivos: Abraham en español; negativos: su risa/tos,
  inglés de otros, eco de Eric, Abraham en inglés); curvas FAR/FRR offline y τ2 con FRR ≤ 3–5 %.

## 5. Orden recomendado
1. CAM++ (sherpa-onnx o nuestro ORT): 1–2 días; probar también "zh_en advanced" para idioma cruzado.
2. YAMNet para risa/tos: 40–70 ms por pedazo, puede ir en paralelo.
3. LID sólo si 1 no basta.

## Fuentes
github.com/k2-fsa/sherpa-onnx · sherpa-onnx/kotlin-api/Speaker.kt · releases/tag/speaker-recongition-models ·
k2-fsa.github.io/sherpa/onnx/speaker-identification · github.com/modelscope/3D-Speaker · arxiv.org/html/2303.00332v3 ⚠ ·
arxiv.org/html/2406.02167 ⚠ · github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md ·
huggingface.co/speechbrain/spkrec-ecapa-voxceleb ⚠ · huggingface.co/speechbrain/lang-id-voxlingua107-ecapa ⚠ ·
picovoice.ai/docs/benchmark/speaker-recognition · arxiv.org/html/2604.26327v2 ·
developers.google.com/edge/mediapipe/solutions/audio/audio_classifier · tensorflow/models research/audioset/yamnet ⚠ ·
github.com/fschmid56/EfficientAT ⚠ · github.com/RicherMans/CED ⚠ · arxiv.org/pdf/2609.12642 · arxiv.org/abs/1908.04284 ⚠ ·
arxiv.org/abs/2204.03793v3 ⚠

## 6. Evaluación con el audio de Abraham (10-nov, `tools/gate_eval.py`)
Set r30 (`tools/logs/gate_dataset/`, fuera de git): 66 pedazos reales del pipeline en 5 partes (muestra de
voz, español por turnos, risas/muletillas, inglés sintético por las bocinas de la PC, hablar encima de Eric).
- **CAM++ en_voxceleb no sirve con sherpa-onnx 2.0.1**: misma voz 0.14, voces distintas 0.89 (prueba con
  David/Zira/Eric limpios). **CAM++ zh_en advanced** se comporta bien (misma voz 0.67–0.92, distintas 0.05–0.24).
- Con zh_en advanced, enrolando con los 5 pedazos de la muestra (27.7 s): Abraham mín 0.48 / mediana 0.84
  (n=29); todo lo demás máx 0.35 / mediana 0.18 (n=24: inglés 0.04–0.25, español de otra voz 0.30–0.34, eco
  de Eric 0.09–0.30, su risa 0.35). Umbral 0.36–0.45: 0 % de errores en este set.
- Costo en la PC: ~16 ms por segundo de audio (1 hilo); YAMNet ~2 ms/s. YAMNet marca risa 0.41 en los
  pedazos de risa, pero la verificación de voz ya los rechaza.
- Límites: set pequeño y en silencio; voces en inglés sintéticas; frases cortas de Abraham 0.48–0.56.
  Siguiente: integrar en modo sombra (registrar sin bloquear), probar con ruido, luego activar (~0.40).

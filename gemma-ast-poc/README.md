# Gemma 4 E4B AST — Proof of Concept

App minima para validar si Gemma 4 E4B puede hacer Audio Speech Translation
(audio espanol -> texto ingles) via LiteRT-LM en el Xiaomi 15T Pro.

## Setup

### 1. Instalar Android Studio
- Descargar: https://developer.android.com/studio
- Instalar con Android SDK 35 y NDK (seleccionar durante instalacion)
- Asegurar que JDK 17 esta incluido (Android Studio lo trae)

### 2. Abrir el proyecto
- Android Studio > File > Open > seleccionar carpeta `gemma-ast-poc/`
- Esperar a que Gradle sincronice (descarga dependencias, ~5 min primera vez)
- Si falta `gradle-wrapper.jar`, Android Studio lo genera automaticamente

### 3. Resolver version de LiteRT-LM
Si la dependencia `litertlm-android` no resuelve con version `0.3.0`:
1. Abrir `gradle/libs.versions.toml`
2. Cambiar `litertlm = "0.3.0"` por la version disponible
3. Verificar en: https://mvnrepository.com/artifact/com.google.ai.edge.litertlm
4. O usar: https://maven.google.com/web/index.html (buscar "litertlm")

### 4. Compilar APK
- Android Studio > Build > Build Bundle(s) / APK(s) > Build APK(s)
- APK en: `app/build/outputs/apk/debug/app-debug.apk`

### 5. Instalar en Xiaomi 15T Pro
```bash
# Conectar telefono por USB con Developer Options y USB Debugging activados
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 6. Obtener modelo Gemma 4 E4B
Opcion A — Download desde la app (boton "Download Model")
Opcion B — Manual:
```bash
# Descargar desde HuggingFace
# https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm

# Copiar al telefono
adb push <model-file> /data/data/com.travel2chicago.gemmatest/files/models/
```

## Que valida el POC

1. **Carga del modelo**: Tiempo de inicializacion, backend (NPU/GPU/CPU)
2. **Soporte de audio**: Refleja la API para verificar si acepta PCM audio como input
3. **Traduccion texto-texto**: Si audio no es soportado, valida que Gemma traduce ES->EN
4. **Metricas**: Latencia, tokens/segundo, memoria usada

## Resultado esperado

- Si AUDIO SOPORTADO: Gemma puede hacer AST directamente -> continuar con pipeline single-pass
- Si AUDIO NO SOPORTADO: Necesitamos STT separado (Whisper ONNX) + Gemma solo para traduccion texto

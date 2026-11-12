# Análisis: el teléfono del usuario en producción, y el diario del día (12 nov 2026)

Contexto: hoy Abraham usa un teléfono dedicado (Xiaomi 15T Pro) con micrófono USB y bocina JBL. Para que el
producto sea costeable, en producción debe correr **en el teléfono de cada usuario**. Y propone una función extra:
un **diario de lo que dijo en el día**, útil para la persona y, a la vez, para la calibración.

Datos reales de las dos primeras salidas (campo-v1):

| | Walmart | IKEA |
|---|---|---|
| Duración | 17 min | 98 min |
| Frases traducidas | 64 | 165 |
| Caracteres que dijo Eric | 2,479 | 8,053 (~4,900 por hora) |
| Minutos de su voz traducida | 3.0 | 10.8 |

---

## 1. Retos de correr en el teléfono del usuario

### 1.1 Cada fabricante mata apps a su manera (el reto más grande)
- Ya lo vimos: **MIUI PowerKeeper** silenciaba la app con la pantalla apagada (E013). Samsung ("apps en
  suspensión"), Huawei, OnePlus, Oppo y Vivo tienen reglas propias, distintas y que cambian con cada versión.
- Ajustes: (a) una **capa de compatibilidad por fabricante** (detectar marca y versión y aplicar la estrategia
  que funcione: el tono de 20 Hz con la pantalla apagada, por ejemplo); (b) un **asistente de configuración**
  al instalar, que guíe paso a paso los ajustes del sistema que sólo el usuario puede cambiar (batería sin
  restricciones, inicio automático); (c) una **auto-prueba** de 2 minutos con la pantalla bloqueada, que confirme
  que el teléfono de esa persona sí deja oír y hablar a la app; (d) una lista de teléfonos probados.

### 1.2 iPhone
- Entre hispanohablantes en EE. UU. el iPhone es muy común; sin versión para iPhone se pierde gran parte del
  mercado. iOS permite audio continuo en segundo plano (modo de audio de fondo), pero es otra app: Swift o
  multiplataforma, modelos convertidos (Moonshine, Silero, CAM++) y otras reglas de energía.
- Decisión de producto a tomar pronto: ¿Android primero y iPhone después, o los dos desde el inicio?

### 1.3 Potencia y tamaño
- Hoy corren en el teléfono Moonshine, Silero, el filtro de voz (CAM++), el cancelador de eco y, como respaldo,
  Gemma y Kokoro. La APK pesa **165 MB** y los modelos se copian a mano a `/sdcard/Download/`.
- En un teléfono de gama media o baja, Moonshine y el filtro de voz pueden tardar el doble, y Gemma (varios GB)
  no cabe. Ajustes: quitar Gemma del producto (el respaldo sin red queda en ML Kit, ~30 MB); evaluar si Kokoro
  se queda o se usa la voz del sistema sin red; descargar los modelos desde la app la primera vez; medir
  latencia en 3–4 teléfonos de distintas gamas.

### 1.4 Batería y calor en un turno completo
- El teléfono dedicado aguanta bien (Abraham lo notó en IKEA). En el teléfono personal compite con todo lo demás.
- Ajuste: medir el consumo por hora (micrófono + detector de voz + red + Bluetooth) en un turno real de 8–10 h
  y optimizar lo que más gaste.

### 1.5 Variedad de micrófonos y bocinas
- Los umbrales de volumen y de eco (1800 / 3000 / 4500) están calibrados para este Xiaomi + ModMic + JBL. Cada
  usuario tendrá otro micrófono (el del teléfono, audífonos Bluetooth, uno de collar) y otra bocina, con otra
  latencia y otro apagado automático.
- Ajuste: un **paso de calibración del audio** en el asistente (la app reproduce unos sonidos y mide eco,
  retraso y volúmenes; ya existen herramientas de chirps en el proyecto), guardado en el perfil del usuario.

### 1.6 Convivir con la vida normal del teléfono
- Llamadas entrantes, música, GPS hablando, notas de voz de WhatsApp (otra app usando el micrófono),
  notificaciones que el micrófono oye. Hoy la app pide el foco de audio completo, lo que pausa la música del
  usuario.
- Ajuste: pausar y reanudar solo ante llamadas y otras apps, recuperar el micrófono si otra app lo toma, y decidir
  la política de foco de audio (bajar la música en vez de pausarla, por ejemplo).

### 1.7 Datos móviles
- La voz de Eric llega como audio sin comprimir (PCM 24 kHz): ~48 KB por segundo. IKEA (≈9.6 min de voz de
  Eric) ≈ **28 MB**; un turno con 4 veces más plática ≈ 100 MB al día, ~2 GB al mes. En el plan de datos de un
  usuario eso pesa.
- Ajuste: pedir a ElevenLabs audio comprimido (Opus/MP3, ~10 veces menos) y decodificarlo en el teléfono;
  medir que no sume latencia.

### 1.8 Costo por usuario
- El costo variable más grande es ElevenLabs (cobra por carácter). Con ~4,900 caracteres por hora de uso real,
  un mes de trabajo diario puede llegar al orden de 0.5–1 millón de caracteres por usuario. Haiku es barato
  gracias al caché del prompt. El servidor (Fly) se reparte entre usuarios.
- Ajuste: con los registros de campo, calcular el costo real por hora de uso contra los precios vigentes de
  ElevenLabs y Anthropic, y de ahí el precio de la suscripción. Alternativas a evaluar: voces más baratas o una
  voz en el teléfono para frases cortas.

### 1.9 Privacidad, consentimiento y leyes
- Es un micrófono siempre encendido en el teléfono personal, en el trabajo, con compañeros alrededor. **Illinois
  pide el consentimiento de todas las partes para grabar conversaciones privadas** (ley de escuchas). Esto
  requiere revisión legal antes de lanzar; no es una opinión legal.
- Diseño a favor: el filtro de voz ya bloquea a otras personas antes de transcribir, y lo bloqueado no se guarda
  (ni audio ni texto). En producción: no guardar audio por defecto, sólo el texto del usuario, cifrado, con
  borrado y consentimiento explícito.

### 1.10 Tienda de aplicaciones
- Google revisa con lupa los servicios en primer plano de tipo micrófono y reproducción: hay que justificar el
  uso ante Play Store. Para pilotos se puede instalar fuera de la tienda.

### 1.11 Perfil del usuario fuera del teléfono
- La huella de voz, el glosario por oficio y las correcciones viven hoy sólo en el teléfono. Con el teléfono del
  usuario hacen falta cuentas, respaldo cifrado del perfil en el servidor y restauración al cambiar de teléfono.

---

## 2. El diario del día

### Qué sería
Al terminar el día, un resumen hecho con todo lo que el usuario dijo (su texto en español y lo que dijo Eric):
- **Eventos y conversaciones**: con quién habló y de qué (por ejemplo, la junta con Jason sobre el nivel).
- **Compromisos**: "le dije que mandaría el reporte" → lo que dijo que haría y no ha hecho.
- **Recordatorios**: fechas, citas y pendientes mencionados ("la clase de ballet es a las cinco").
- **Estado de ánimo**: momentos de enojo o frustración y su motivo (por ejemplo, la conversación sobre su
  salario), presentado como reflexión personal, no como diagnóstico.
- **Frases del día en inglés**: lo que dijo y cómo se dijo en inglés; sirve para aprender el idioma con su propio
  vocabulario de trabajo.

### Cómo se haría
- Las transcripciones ya se guardan por sesión (`index.tsv`). En la noche se mandan a Claude en lote (la API de
  Batch cuesta la mitad y no necesita respuesta inmediata); regresa un diario estructurado que la app muestra.
- Los recordatorios podrían pasar al calendario o a una lista de tareas del usuario, con su permiso.

### Doble uso: calibración
- Al leer su diario, el usuario ve sus frases y cómo salieron en inglés: es el momento natural para marcar
  "esta no fue así". La revisión de calibración queda dentro de algo que le sirve, en vez de ser una tarea extra.
- Los errores marcados alimentan el registro (`tools/calibracion/errores.tsv`) y su perfil.

### Cuidados
- Es el dato más sensible del producto: el día completo de una persona, incluyendo lo que dice de otros, su
  ánimo y su salud. Debe ser opcional, local primero, cifrado, con retención limitada y borrado fácil.
- Las voces de otras personas nunca entran: el filtro de voz las bloquea antes de transcribir. Pero lo que el
  usuario dice de otros sí queda, y eso también pide cuidado.

---

## 3. Siguientes pasos sugeridos

1. Medir en el teléfono actual consumo de batería por hora y datos por hora en un turno real.
2. Probar la app en 2–3 teléfonos Android de otras marcas y gamas (Samsung, Motorola, uno económico) con la
   auto-prueba de pantalla bloqueada.
3. Prototipo del diario con las salidas de Walmart e IKEA (offline, en la PC) para ver si le sirve a Abraham.
4. Decidir Android-primero vs. Android + iPhone, y pedir revisión legal de privacidad y consentimiento.

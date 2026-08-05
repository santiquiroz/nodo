# Nodo — Prompt de arranque (para una sesión nueva)

> **Instrucciones para el asistente que retome esto:** este archivo es tu brief completo. Léelo entero antes de tocar código. Estás iniciando **Nodo**, una app Android open source que hospeda LLMs locales y los sirve a otras apps. Trabaja con buenas prácticas, TDD donde aplique, y en español (código/commits en el estilo del dueño: sin `Co-Authored-By`, commits descriptivos por capa). El repo vive en `c:/personal/nodo` y se subirá al GitHub personal `santiquiroz` como código libre (MIT).

---

## 1. Visión

**IA local en el bolsillo — tus apps se conectan a un solo cerebro.**

Nodo carga un modelo de lenguaje **una vez** en el teléfono y lo expone a cualquier app vía un endpoint **compatible con OpenAI** en `localhost` (+ LAN opcional). En vez de que cada app embeba su propio modelo de 2-4 GB (RAM desperdiciada, descargas y arranques en frío repetidos), Nodo es el host compartido.

Es el mismo patrón que **Google AICore** (Gemini Nano como servicio del sistema) y **Ollama** en PC, pero abierto, propio, y en el teléfono.

**Por qué encaja en el ecosistema del dueño:** tiene varias apps (RevScope, y más por venir). Es el tercer pilar de un patrón que ya viene construyendo — "infraestructura compartida detrás de un endpoint configurable":
1. **Auth** → OIDC centralizado (proyecto de identidad futuro).
2. **Datos colaborativos** → `revscope-server` (apps apuntan a una URL).
3. **Inferencia** → **Nodo** (apps apuntan a `localhost`).

**Ventaja clave:** privacidad total (los datos nunca salen del teléfono) + costo cero de API en todas sus apps.

---

## 2. Requisitos del dueño (lo que pidió explícitamente)

1. **Descargar modelos fácilmente desde Hugging Face** — igual que en su proyecto *Upflow* (image-upscaler-amd), que baja modelos de HF con buena UX. Buscar, elegir, descargar con progreso reanudable.
2. **Que me diga si el modelo correrá bien en local** — chequeo de compatibilidad del dispositivo ANTES de descargar/cargar: semáforo **corre bien / justo / no cabe**, con el porqué (RAM, chip, tok/s esperado).
3. **Que muestre qué capacidades tiene el modelo** — contexto, parámetros, cuantización, modalidades (texto/visión), plantilla de chat, licencia.
4. **Servir a otras apps** — endpoint OpenAI-compatible en localhost; RevScope ya es cliente (tiene `OpenAiCompatibleProvider` con base URL configurable → apuntaría a Nodo **sin cambios**).
5. **Open source, buenas prácticas, libertad creativa.**

---

## 3. Validación de la comunidad (last30days, ago 2026)

La idea está validada y hay demanda real sin dueño claro:
- Video con tracción ["Turn Your Android Into Private AI Server"](https://www.youtube.com/watch?v=43oZPoQiqoY) (Tech Jarves): *"no es solo chat, no es solo IA offline — es un server de IA real, accesible desde Windows/macOS/otro Android."* Es exactamente esta idea. Forkeó un proyecto OSS de Google.
- Comentario (@therollo9, 49 likes): *"acabo de hacer una app que es básicamente esto, pero la tuya es mejor, voy a usar la tuya"* → mucha gente construyendo esto ahora, sin ganador claro.
- @TechJarves confirma los dos runtimes reales: *"podrás usar modelos GGUF **y** LiteRT"*.
- Ecosistema maduro: [Ollama](https://github.com/ollama/ollama) (178K estrellas) es el patrón de referencia. Comunidades vivas: r/LocalLLM, r/ollama, r/LocalLLaMA.

**Implicación:** el patrón "apuntar un cliente a un endpoint local tipo OpenAI" es el estándar de facto. No hay que inventar nada raro. Riesgo real: no es técnico, es que varios construyen lo mismo — irrelevante si es para el ecosistema personal del dueño.

---

## 4. Stack recomendado (investigación técnica profunda, ago 2026 — verificar versiones al arrancar)

### Runtime de inferencia
- **Default: llama.cpp / GGUF vía NDK+JNI.** Máxima selección de modelos de HF, `mmap` nativo. En Android nativo, la mejor base es forkear el wrapper `smollm.cpp` de **SmolChat-Android** (Kotlin+JNI). Si se fuera React Native, `mybigday/llama.rn` (lo usa PocketPal). Path oficial: `examples/llama.android` de llama.cpp.
- **Realidad de aceleración (importante — corrige suposiciones):**
  - **GPU (Adreno OpenCL):** el backend nuevo de Qualcomm solo acelera **Q4_0**, exige **KV cache f16** (q8_0 crashea), da ~3.7× en *prefill* pero puede ser ~17% MÁS LENTO en generación por sync CPU↔GPU. Vulkan en GPUs móviles es más lento que CPU. → **CPU es el default pragmático**; ofrecer GPU solo como toggle para modelos Q4_0.
  - **NPU (Hexagon):** LiteRT-LM en Android **NO** expone NPU todavía (NPU es preview solo-Windows). El ÚNICO camino real a la NPU del 8 Elite es **Qualcomm Genie / QNN**, y requiere modelos **pre-compilados por chipset** desde Qualcomm AI Hub — no sirve para "cualquier GGUF de HF". Ver `qualcomm/GenieX` (BSD-3): un SDK C que corre GGUF O bundles QNN, con bindings Kotlin y **servidor OpenAI-compatible incluido**.
  - **Perf medida (8 Elite, CPU):** 7B ≈ 8 tok/s gen / 24 tok/s prefill; modelos 1-3B varias× más rápido → **el sweet spot** para un teléfono de 12 GB.
- **Lane acelerado (opcional, v2, modelos curados):** LiteRT-LM Kotlin para **Gemma 3n E2B/E4B** (`.litertlm`, GPU) desde [litert-community en HF]; GenieX/QNN para NPU real. Marcarlos con badge "NPU/GPU-optimizado". La MediaPipe LLM Inference API está **deprecada** — no usarla.

### Descarga desde Hugging Face (REST directo, sin la lib de Python)
- **Buscar GGUF:** `GET https://huggingface.co/api/models?filter=gguf&search=<q>&sort=downloads&limit=30`.
- **Tamaños por archivo (para el semáforo pre-descarga):** `GET https://huggingface.co/api/models/{repo_id}/tree/{revision}?recursive=true&expand=true` → cada entry trae `size`.
- **Descargar:** `GET https://huggingface.co/{repo_id}/resolve/{revision}/{path}` — soporta **`Range`** → reanudable/segmentado. Envolver en **WorkManager** para sobrevivir muerte de proceso.
- **Modelos gated:** `Authorization: Bearer <hf_token>` (token read-scoped) tras aceptar términos.

### Chequeo de compatibilidad (motor puro + tests)
- **Leer dispositivo:** `ActivityManager.getMemoryInfo()` → `availMem` (presupuestar contra esto, NO `totalMem`) + `Build.SOC_MODEL`/`SOC_MANUFACTURER` (API 31+).
- **Fórmula de huella:** pesos(GB) = `params_B × bytes/param` (Q4_K_M ≈ **0.55 B/param**; 3B≈1.7GB, 7B≈4GB). KV cache(GB) = `2 × capas × kv_heads × head_dim × contexto × 2 / 1e9`. Total = pesos + KV + **~1-1.5 GB de overhead**.
- **Semáforo:** 🟢 corre bien = `total < 0.6 × availMem` y SoC flagship; 🟡 justo = `total < availMem` pero pasa la línea 0.6; 🔴 no cabe = `total ≥ availMem` (riesgo OOM). Regla gruesa: 12 GB (S25 Ultra) = hasta ~7-8B Q4. Copiar el patrón de **PocketPal**: gauge de RAM en vivo + benchmark de un toque (la señal más honesta).

### Servir (OpenAI-compatible)
- **Ktor (motor CIO) + SSE**, NO NanoHTTPD (Ktor es coroutine-native, SSE de primera). En un **foreground service**. Endpoints: `POST /v1/chat/completions` (streaming), `GET /v1/models`, `/v1/completions`, `/health`. Adaptador opcional Ollama `/api/chat`. Túnel ngrok/Cloudflare opcional para fuera de LAN.
- **Single-flight:** una NPU/GPU = concurrencia 1 → **Mutex/cola** al frente; peticiones solapadas se serializan o devuelven 429.
- **Tipo de foreground service (ojo):** Android 14+ exige `foregroundServiceType` y **no hay tipo "AI"**. `dataSync` está **capado a ~6h/día en Android 15**; `specialUse` necesita justificación en Play Console; **`connectedDevice`** es defendible si sirves clientes en LAN. Notificación persistente + stop del usuario + idle-unload tras N min.

### Metadata del modelo (GGUF)
- El bloque KV de GGUF trae TODO sin cargar pesos: `general.architecture/name/file_type` (quant), `{arch}.context_length`, `.block_count` (capas), `.attention.head_count(_kv)`, y **`tokenizer.chat_template`** (Jinja — aplicarla automáticamente, no hardcodear formatos). Se puede **Range-leer los primeros MB** desde HF para previsualizar metadata ANTES de descargar. Multimodal (llava/Gemma 3n visión) usa un archivo `mmproj` aparte.

### Proyectos OSS para forkear/estudiar (confirmar licencias)
| Proyecto | Licencia | Qué tomar |
|---|---|---|
| **[HostAI](https://github.com/wannaphong/android-hostai)** | Apache-2.0 | Esqueleto de servidor OpenAI en foreground service + SSE (la mejor referencia de serving) |
| **[SmolChat-Android](https://github.com/shubham0204/SmolChat-Android)** | Apache-2.0 | Motor GGUF nativo (`smollm.cpp` JNI en Kotlin) — mejor base nativa |
| **[PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai)** | MIT | UX de descarga HF + UI de capacidad (RAM/benchmark) |
| **[techjarves/mobile-server](https://github.com/techjarves/mobile-server)** / [AI Edge Gallery](https://github.com/google-ai-edge/gallery) | Apache-2.0 | Ktor server + tunneling; ver PR "Edge Server" upstream |
| **[GenieX](https://github.com/qualcomm/GenieX)** | BSD-3 | Camino NPU + su propio servidor OpenAI |
| ChatterUI | **AGPL-3.0 ⚠️** | Solo estudiar (copyleft — no forkear a un repo permisivo) |

### Gotchas críticos (diseñar desde el día 1)
- **`mmap` SIEMPRE** — cargar un Q4 3B a heap = OOM/LMK garantizado; mmap deja al kernel paginar (llama.cpp lo hace por default).
- **Throttling térmico = el asesino silencioso:** el rate cae **40-60% tras 60-90 s** de generación sostenida; NPU ~5× peor tras ~10 min. Instrumentar `PowerManager.getThermalStatus()`/`addThermalStatusListener` y bajar hilos/contexto en `THERMAL_STATUS_SEVERE+`.
- **Storage:** modelos de GB → `getExternalFilesDir()` (sin permiso, se borra al desinstalar) o SAF para carpeta elegida; nunca MediaStore. Avisar si falta espacio antes de bajar.
- **Latencia de primera carga:** mmap page-in + KV alloc + token de warmup = segundos → pre-calentar al arrancar el service y mostrar estado "cargando modelo".

---

## 5. Dirección de UI/UX

**Estética:** herramienta de desarrollador, oscura y técnica. Legible, densa pero ordenada, sin adornos. (Se cargó la skill `ui-ux-pro-max`; aplicar sus reglas críticas.)

**Pantallas v1 (bottom nav ≤5):**
1. **Modelos** — biblioteca local: modelos descargados como tarjetas (nombre, tamaño, cuantización, estado). Botón para explorar HF.
2. **Explorar HF** — búsqueda + filtros (GGUF, tamaño, popularidad). Cada resultado muestra el **semáforo de compatibilidad** ANTES de descargar (🟢 corre bien / 🟡 justo / 🔴 no cabe) + tamaño + licencia. Descarga con progreso reanudable.
3. **Servidor** — dashboard: modelo activo, estado (caliente/frío), URL local + LAN copiable, toggle on/off del foreground service, contador de peticiones, temperatura/RAM. (Estilo del panel MCP de RevScope.)
4. **Chat** — chat de prueba contra el modelo cargado, para verificar antes de conectar apps.
5. **Ajustes** — token de HF, puerto, exponer en LAN, tema.

**Reglas de UI/UX obligatorias (de la skill):** touch targets ≥48dp; feedback de tap 80-150ms; contraste texto ≥4.5:1 en dark; iconos vectoriales (Lucide/vector-icons), **nunca emojis como iconos estructurales** (los emojis del semáforo son datos, no navegación); estados de carga con skeleton/shimmer para descargas y carga de modelo (>1s); números tabulares para tamaños/tok-s; semáforo debe llevar icono+texto, no solo color (accesibilidad); notificación del foreground service clara.

**Tarjeta de modelo (componente central):** nombre + org, tamaño en disco, params, cuantización (Q4_K_M…), contexto, modalidades, licencia, y el semáforo con el porqué ("Cabe en RAM: 3.2/12 GB · ~40 tok/s estimado en tu Snapdragon 8 Elite").

---

## 6. Arquitectura propuesta (Android nativo, Kotlin + Compose + Hilt)

Multi-módulo, mismo estilo que RevScope:
- `:app` — NavGraph, Hilt, pantallas.
- `:core:inference` — runtime(s): interfaz `InferenceEngine` (start/stop/generate/stream) con impl `LlamaCppEngine` (y `LiteRtEngine` en v2). Modelo caliente, cola.
- `:core:models` — Hugging Face Hub (buscar/listar/descargar reanudable), metadata GGUF, catálogo local, storage.
- `:core:capability` — chequeo de dispositivo **puro y testeable**: `DeviceProfile` (RAM/SoC/NPU) + `ModelFootprint` + `CompatibilityVerdict` (VERDE/AMARILLO/ROJO + razón). Tests con casos fijos.
- `:core:serving` — foreground service + servidor HTTP OpenAI-compatible (NanoHTTPD/Ktor), SSE streaming, mapeo `/v1/chat/completions` ↔ engine.
- `:feature:*` — modelos, explorar, servidor, chat, ajustes.

**Contrato con clientes:** OpenAI-compatible. Objetivo de prueba de integración: apuntar el proveedor custom de **RevScope** a `http://localhost:<puerto>` y verificar que el chat mecánico responde sin tocar RevScope.

**Interfaz `InferenceEngine`** — abstracción para no casarse con un runtime (como `AiProvider` en RevScope). Permite meter LiteRT después sin reescribir el serving.

---

## 7. Plan por fases (hitos de v1)

**Fase 0 — Esqueleto.** Proyecto Gradle multi-módulo, Compose, Hilt, nav de 5 tabs con placeholders. Build verde. Commit inicial + push a GitHub (MIT, README ya existe).

**Fase 1 — Motor + chat local.** `:core:inference` con `LlamaCppEngine` (GGUF). Cargar un modelo desde archivo local, generar texto, streaming. Pantalla Chat funcional contra un modelo puesto a mano. **Gate físico:** correr Gemma/Llama pequeño en el S25 Ultra del dueño y medir tok/s reales.

**Fase 2 — Chequeo de compatibilidad.** `:core:capability` puro + tests. `DeviceProfile` real del dispositivo. Semáforo en la UI.

**Fase 3 — Descarga desde Hugging Face.** `:core:models`: buscar, filtrar GGUF, descargar reanudable con progreso, catálogo local. Explorar HF con el semáforo antes de descargar. Tarjetas de capacidad desde metadata GGUF.

**Fase 4 — Servir.** `:core:serving`: foreground service + servidor OpenAI-compatible + SSE. Dashboard de servidor. **Gate de integración:** RevScope apuntando a Nodo, respondiendo local.

**Fase 5 — Pulido + release.** LAN opcional, token HF para gated, ajustes, manejo de OOM/térmico, primer APK release firmado (debug keystore, sideload) + release en GitHub. Considerar LiteRT-LM (NPU) como v2.

---

## 8. Primeras acciones de la sesión nueva

1. Leer este archivo y el `README.md`. La §4 ya trae el stack investigado (ago 2026) — solo **verificar versiones actuales** de las librerías clave antes de fijar dependencias (llama.cpp Android, Ktor, HF Hub API), no re-investigar desde cero.
2. `superpowers:brainstorming` para cerrar las decisiones abiertas: (a) ¿solo llama.cpp/GGUF en v1, o meter el lane LiteRT/GenieX ya? (recomendado: solo GGUF en v1); (b) ¿forkear SmolChat como base del motor + HostAI para el serving, o desde cero? (recomendado: forkear ambos, son Apache-2.0); (c) nombre definitivo ("Nodo" tentativo; alternativas: Neura, Enchufe, Relay).
3. `superpowers:writing-plans` para el plan detallado de Fase 0-1.
4. Empezar la Fase 0 (esqueleto multi-módulo + push a GitHub) siguiendo TDD donde aplique (el motor de compatibilidad de §6 es puro → tests primero).
5. Objetivo de la primera sesión productiva: llegar a Fase 1 (cargar un GGUF pequeño a mano y chatear) y medir tok/s reales en el S25 Ultra.

## 9. Contexto del dueño (para no repreguntar)

- Santiago, Colombia. Rig casa: RX 7800 XT, teléfono Samsung S25 Ultra (Snapdragon 8 Elite, 12 GB) — dispositivo de prueba real, ADB por WiFi (serial mDNS `adb-R5CY82F28DM-...`, usar `-s`).
- Delegación: Codex sin créditos; Copilot CLI disponible para trabajo mecánico. Gradle sin wrapper en repos nuevos → usar el cacheado `~/.gradle/wrapper/dists/gradle-8.11.1-bin/.../bin/gradle` con `-p <ruta>` (el cwd de Bash se resetea entre llamadas).
- Estilo de commits: en español, agrupados por capa; **nunca** `Co-Authored-By`.
- Patrón favorito del dueño: motores puros + tests, features opt-in con gate, offline-first, fallo silencioso ante red/servicio ausente.
- Apps hermanas: **RevScope** (`c:/personal/OBD2`, github santiquiroz/revscope) — cliente OpenAI-compatible listo, es el target de integración. **revscope-server** (github santiquiroz/revscope-server) — el patrón self-host/OIDC que Nodo espeja on-device. **Upflow** (`~/.openclaw/workspace/image-upscaler-amd`) — referencia de la UX de descarga de modelos de Hugging Face.

---

*Semilla creada 2026-08-04. Ejecutar en una sesión con contexto fresco.*

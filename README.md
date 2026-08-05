# Nodo

> **IA local en tu bolsillo — tus apps se conectan a un solo cerebro.**

Nodo es una app Android open source que **hospeda modelos de lenguaje (LLM) localmente** en el teléfono y los expone a otras apps a través de un endpoint **compatible con OpenAI** en `localhost` (y opcionalmente en la red WiFi). Descargas modelos fácilmente desde Hugging Face, Nodo te dice **si correrán bien en tu dispositivo**, te muestra **qué capacidades tienen**, y los sirve — una sola vez cargado, todas tus apps lo usan.

## Por qué

Correr un LLM on-device ya es práctico en teléfonos modernos (Snapdragon 8 Elite / S25 Ultra). Pero si cada app embebe su propio modelo de 2-4 GB, desperdicias RAM, descargas y arranques en frío. Nodo carga el modelo **una vez** y lo comparte. Mismo patrón que Google AICore (Gemini Nano como servicio del sistema) u Ollama en PC, pero abierto, tuyo, y en el bolsillo.

Encaja en un ecosistema de varias apps: RevScope, y cualquier otra que quiera IA local, gratis, privada (los datos nunca salen del teléfono) y sin costo de API.

## Qué hace (visión v1)

- **Explorar + descargar modelos de Hugging Face** — buscar, filtrar por formato (GGUF / LiteRT), ver tamaño y licencia; descargas reanudables.
- **Chequeo de compatibilidad del dispositivo** — antes de descargar, Nodo estima si el modelo correrá bien aquí (RAM disponible vs huella del modelo, chip/NPU, tok/s esperado): semáforo **corre bien / justo / no cabe**.
- **Tarjetas de capacidad del modelo** — contexto, parámetros, cuantización, modalidades (texto/visión), plantilla de chat, licencia.
- **Servir** — un foreground service mantiene el modelo caliente y expone `/v1/chat/completions` (con streaming) en localhost + LAN opcional. Cualquier app compatible con OpenAI lo consume sin cambios.
- **Chat de prueba** integrado para verificar el modelo.

## Estado

🚧 **Sirviendo modelos de verdad.** Motor llama.cpp (JNI, submódulo `b10276`) cargando GGUF con mmap, chat con streaming, y un servidor OpenAI-compatible en un foreground service. Verificado en un S25 Ultra: **65 tok/s** (Qwen2.5-0.5B Q4) / **23 tok/s** (1.5B Q4) — ver [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

- **Motor** — `InferenceEngine` + `LlamaCppEngine` (mmap, plantilla de chat del GGUF, streaming token a token, errores tipados).
- **Compatibilidad** — lee la metadata de cada GGUF sin cargar los pesos y dice si corre aquí: 🟢 corre bien / 🟡 justo / 🔴 no cabe, con la huella en GB y los tok/s estimados.
- **Chat** — pantalla de prueba con selector de modelo y velocidad real por respuesta.
- **Servidor** — `POST /v1/chat/completions` (respuesta completa o SSE), `/v1/models`, `/health`; foreground service, exposición opcional en la WiFi y 429 ante peticiones solapadas.
- **Integración verificada** — un test replica byte por byte el cliente de [RevScope](https://github.com/santiquiroz/revscope) y obtiene respuestas del modelo local; ver [`docs/INTEGRACION-REVSCOPE.md`](docs/INTEGRACION-REVSCOPE.md).
- **Tool calling** — los modelos 3B emiten llamadas a herramientas bien formadas on-device; ver [`docs/MODELOS.md`](docs/MODELOS.md).

Siguiente: descarga de modelos desde Hugging Face dentro de la app, con el semáforo mostrándose antes de bajar nada.

Kickoff y plan: [`docs/KICKOFF.md`](docs/KICKOFF.md) · [`docs/plans/`](docs/plans/)

## Cómo usarlo hoy

1. Copia un `.gguf` a `Android/data/com.santiquiroz.nodo/files/models/` (por ADB: `adb push modelo.gguf /sdcard/Android/data/com.santiquiroz.nodo/files/models/`).
2. Abre Nodo → pestaña **Servidor** → elige el modelo → **Iniciar servidor**.
3. Copia la URL local y pégala en cualquier cliente compatible con OpenAI.

## Licencia

MIT.

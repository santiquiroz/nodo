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

🚧 **Fase 1 funcional.** Motor llama.cpp (JNI, submódulo `b10276`) cargando GGUF con mmap, chat con streaming token a token, y benchmarks reales en S25 Ultra: **65 tok/s** (Qwen2.5-0.5B Q4) / **23 tok/s** (1.5B Q4) — ver [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

- Hecho: esqueleto multi-módulo (Compose+Hilt), `InferenceEngine` + `LlamaCppEngine`, pantalla de Chat con selector de modelo y stats tok/s, tests de ViewModel (TDD) + smoke instrumentado en dispositivo.
- Siguiente: Fase 2 (chequeo de compatibilidad del dispositivo), Fase 3 (descarga desde Hugging Face), Fase 4 (servidor OpenAI-compatible).
- Plan detallado: [`docs/plans/2026-08-04-fase-0-1-esqueleto-motor-chat.md`](docs/plans/2026-08-04-fase-0-1-esqueleto-motor-chat.md) · Kickoff: [`docs/KICKOFF.md`](docs/KICKOFF.md)

## Licencia

MIT.

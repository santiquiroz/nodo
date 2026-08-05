# Benchmarks — S25 Ultra (Snapdragon 8 Elite, 12 GB)

Nodo 0.1.0 · llama.cpp b10276 · CPU 6 hilos · ctx 4096 · Q4_K_M · mmap

Medido con test instrumentado (`LlamaEngineSmokeTest`), prompt de 20 tokens, generación máx 128 tokens, por ADB WiFi.

## Corrida 1 — 2026-08-04 23:42 (motor recién compilado, un solo test)

| Modelo | Cuant | Tamaño | tok/s gen | 1er token | Tokens gen |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | Q4_K_M | 398 MB | 65.6 | 519 ms | 39 |
| Qwen2.5-1.5B-Instruct | Q4_K_M | 986 MB | 23.5 | 662 ms | 67 |

## Corrida 2 — 2026-08-05 07:31 (tras fixes de motor; 2 tests seguidos)

| Modelo | Cuant | Tamaño | tok/s gen | 1er token | Tokens gen |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | Q4_K_M | 398 MB | 48.5 | 294 ms | 30 |
| Qwen2.5-1.5B-Instruct | Q4_K_M | 986 MB | 13.1 | 1827 ms | 29 |

**La corrida 2 es ~1.8× más lenta en generación y el 1.5B tarda 2.8× más en el primer token.** No está explicado todavía; hipótesis no verificadas, en orden de plausibilidad:
1. Page cache frío — los GGUF se re-empujaron por ADB minutos antes (1.4 GB), y con `mmap` el primer recorrido de los pesos pagina desde disco.
2. Throttling térmico/estado de carga distinto — corrida 2 ejecuta el test de contexto agotado (512 tokens de generación abortada) *antes* del benchmark, en el mismo proceso.
3. Ruido de gobernador de CPU (no se fijó afinidad ni modo de rendimiento).

Los fixes del motor entre corridas (sampler por generación, chequeo de `n_past` contra `n_ctx`, códigos de estado) no añaden trabajo en el bucle caliente, así que la regresión no debería venir de ahí — pero **no está descartado**. Pendiente: repetir con el device en reposo térmico y los modelos ya en page cache, varias corridas, y tomar la mediana.

## Calidad

Respuestas coherentes en español en ambos modelos y ambas corridas. El 1.5B da respuestas más precisas; el 0.5B alucina detalles ("a veces, jugar videojuegos").

## Notas

- Contexto agotado se detecta y reporta como `Failure("Contexto agotado…")` — verificado en device con `contextLength = 128` y un prompt largo (regresión del bug donde terminaba como `Done` exitoso).
- RSS del proceso pendiente de medir (el test termina antes de poder capturar `dumpsys meminfo`).
- Próximos candidatos: Qwen2.5-3B / Llama-3.2-3B Q4_K_M (~2 GB), sweet spot calidad/velocidad para 12 GB.
- El primer token incluye el prefill del prompt completo: v1 limpia el KV y re-decodifica todo el historial en cada turno. Cache KV incremental es la optimización obvia pendiente.

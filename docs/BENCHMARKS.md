# Benchmarks — S25 Ultra (Snapdragon 8 Elite, 12 GB)

Fecha: 2026-08-04 · Nodo 0.1.0 · llama.cpp b10276 · CPU 6 hilos · ctx 4096 · Q4_K_M · mmap

Medido con test instrumentado (`LlamaEngineSmokeTest`), prompt de 20 tokens, generación máx 128 tokens, pantalla bloqueada.

| Modelo | Cuant | Tamaño | tok/s gen | 1er token | Tokens gen | Calidad |
|---|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | Q4_K_M | 398 MB | **65.6** | 519 ms | 39 | Coherente, español correcto |
| Qwen2.5-1.5B-Instruct | Q4_K_M | 986 MB | **23.5** | 662 ms | 67 | Coherente, más detallada |

Notas:
- Sin throttling térmico observable en corridas cortas (~5 s); falta medir generación sostenida (>60 s) donde el KICKOFF anticipa caída de 40-60%.
- RSS de proceso pendiente de medir (el test termina antes de poder capturar `dumpsys meminfo`).
- Próximos candidatos: Qwen2.5-3B / Llama-3.2-3B Q4_K_M (~2 GB, estimado ~12-15 tok/s), sweet spot calidad/velocidad para 12 GB.
- El primer token incluye prefill del prompt completo (v1 limpia KV y re-decodifica todo el historial en cada turno — optimización de cache KV incremental pendiente).

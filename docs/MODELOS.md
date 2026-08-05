# Modelos evaluados — S25 Ultra (Snapdragon 8 Elite, 12 GB)

Todos Q4_K_M, CPU 6 hilos, ctx 4096, mmap. Medido 2026-08-05 con `LlamaEngineSmokeTest` y `ToolCallingViabilidadTest`.

## Velocidad y capacidades

| Modelo | Tamaño | tok/s | Contexto nativo | Plantilla con tools | ¿Emite `tool_call`? |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | 398 MB | 60.1 | 32768 | sí | no probado |
| Qwen2.5-1.5B-Instruct | 986 MB | 22.0 | 32768 | sí | no probado |
| **Qwen2.5-3B-Instruct** | 1929 MB | 10.3 | 32768 | **sí** | **sí** |
| Qwen2.5-3B-Instruct-abliterated | 1929 MB | 4.7 | 32768 | sí | **no** |
| **Dolphin3.0-Llama3.2-3B** | 2019 MB | 4.2 | 131072 | **no** | **sí** |

Los tok/s de los últimos modelos de cada corrida están contaminados por throttling térmico: se ejecutan 5 modelos seguidos sin pausa y el orden importa mucho. **Los valores de 3B (10.3 / 4.7 / 4.2) no son comparables entre sí.** Para una cifra honesta hace falta medir cada modelo en frío, varias corridas, y tomar la mediana. La única conclusión sólida del cuadro es el orden de magnitud: 0.5B ≈ 60, 1.5B ≈ 22, 3B ≈ 10 o menos.

## Tool calling — prueba de viabilidad

Sin tocar el JNI: se inyecta la definición de la herramienta en el mensaje de sistema con el formato que genera la plantilla de Qwen (bloque `<tools>` + instrucción de responder con `<tool_call>`), y se mide si el modelo emite la llamada.

Pregunta: *"¿Qué temperatura hace hoy en Medellín?"* con una herramienta `buscar_web(consulta)` disponible.

- **Qwen2.5-3B-Instruct** → `{"name": "buscar_web", "arguments": {"consulta": "Temperatura Medellín hoy"}}` ✅
- **Dolphin3.0-Llama3.2-3B** → `{"name": "buscar_web", "arguments": {"consulta": "temperatura Medellín"}}` ✅ (aunque su GGUF **no** trae plantilla con tools: obedece la instrucción del sistema, que es justo lo que promete Dolphin — "controlás el system prompt")
- **Qwen2.5-3B-Instruct-abliterated** → respondió en lenguaje natural pidiendo la fecha, **sin emitir la llamada** ❌

**Conclusión:** el tool calling con modelos 3B on-device funciona hoy, sin enlazar `common/chat.cpp`, solo con inyección en el system prompt. La abliteración degrada el seguimiento de instrucciones estructuradas — es el trade-off esperado al quitar el alineamiento tocando los pesos.

## Recomendación

- **Para búsqueda web / agentes:** Qwen2.5-3B-Instruct.
- **Si además se quiere un modelo sin restricciones:** Dolphin3.0-Llama3.2-3B, que es permisivo por diseño (entrenado sin alineamiento, no lobotomizado a posteriori) y **conserva** la capacidad de llamar herramientas. Mejor opción que un abliterated para este caso.
- Los abliterated sirven para chat libre, no para flujos estructurados.
- Una sola muestra por modelo: esto es una prueba de viabilidad, no una evaluación. Antes de decidir en serio hacen falta varias corridas y prompts variados.

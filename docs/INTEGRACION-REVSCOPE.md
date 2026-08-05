# Integración Nodo ↔ RevScope

Análisis del código real de RevScope (`c:/personal/OBD2`, 2026-08-05) para dimensionar qué debe implementar Nodo en la Fase 4.

## Lo que RevScope ya tiene

`core/intelligence/.../provider/OpenAiCompatibleProvider.kt` es un cliente OpenAI-compatible con base URL editable, pensado para LM Studio y similares. El usuario elige proveedor `custom` en Ajustes y escribe la URL (`AI_CUSTOM_BASE_URL` en `PreferencesKeys.kt:74`).

**Contrato exacto que emite** (`OpenAiCompatibleProvider.kt:46-83`):

```
POST {baseUrl}/chat/completions        ← añade el sufijo si la URL no lo trae
Content-Type: application/json
Authorization: Bearer <key>            ← solo si la clave no está en blanco

{"model": "<string>", "messages": [{"role":"system",...}?, {"role":"user",...}], "max_tokens": N}
```

No manda `stream`, ni `temperature`, ni `tools`, ni `response_format`. Cliente: `HttpURLConnection` plano, sin reintentos.

**Lo que exige de la respuesta** (`AiResponseParsers.kt:35-41`): `choices[0].message.content`, con getters obligatorios (`getJSONArray`/`getJSONObject`/`getString`). Cualquier campo faltante → `Result.failure`. No mira `usage`, `finish_reason`, `id`, `model` ni `object`: Nodo puede omitirlos.

**Timeouts** (`OpenAiCompatibleProvider.kt:87-88`): conexión 10 s, **lectura 20 s**.

## Los tres bloqueadores reales

### 1. El read timeout de 20 s no le alcanza a ningún modelo grande

Ninguna función de RevScope cabe en 20 segundos con un 3B a ~10 tok/s:

| Función | Archivo | max_tokens | ¿Web? | 3B (10 tok/s) | 1.5B (22 tok/s) | 0.5B (60 tok/s) |
|---|---|---|---|---|---|---|
| Explicar código DTC | `dtc/DtcExplainer.kt:10` | 350 | no | 35 s ❌ | 16 s ✅ | 6 s ✅ |
| Resumen de viaje | `debrief/TripDebriefGenerator.kt:45` | 400 | no | 40 s ❌ | 18 s ✅ | 7 s ✅ |
| Chat con mecánico | `workshop/MechanicChatViewModel.kt:201` | 800 | no | 80 s ❌ | 36 s ❌ | 13 s ✅ |
| Info local | `local/LocalInfoFetcher.kt:12` | 200 | **sí** | 20 s ❌ | 9 s ✅ | 3 s ✅ |
| Pico y placa | `restriction/RestrictionRulesFetcher.kt:12` | 400 | **sí** | 40 s ❌ | 18 s ✅ | 7 s ✅ |
| Brief de zona | `zone/ZoneBriefFetcher.kt:11` | 320 | **sí** | 32 s ❌ | 15 s ✅ | 5 s ✅ |

Y como RevScope pide respuesta completa (sin `stream`), no hay forma de ir mandando tokens para mantener viva la conexión… con una excepción: el read timeout de `HttpURLConnection` se aplica **por operación de lectura**, no al total. Si Nodo emite bytes periódicamente, nunca expira.

Tres salidas, de menos a más invasiva:
- **Padding keep-alive** (cero cambios en RevScope): Nodo responde con `Transfer-Encoding: chunked` y va emitiendo espacios en blanco mientras genera, y al final el JSON. `JSONObject` ignora el whitespace inicial. Es un hack, pero es el único camino que no toca RevScope.
- **Subir el read timeout** en RevScope a 120 s cuando el proveedor es local (1 línea, pero cambia RevScope).
- **Enseñarle SSE a RevScope**: lo correcto a largo plazo, pero es rediseñar `AiProvider` (hoy devuelve `Result<String>`, no un `Flow`).

### 2. HTTP plano está bloqueado

Ningún `AndroidManifest.xml` de RevScope declara `usesCleartextTraffic` ni `networkSecurityConfig`, y `targetSdk = 35` (`app/build.gradle.kts:16`). Desde Android 9 el default bloquea cleartext, así que **`http://localhost:8080` falla hoy**, antes siquiera de llegar a Nodo.

Esto implica de paso que **el soporte de LM Studio por `http://` de RevScope nunca funcionó** — vale confirmarlo aparte, es un bug latente propio.

Arreglo mínimo en RevScope: un `network_security_config.xml` que permita cleartext solo a `localhost`, `127.0.0.1` y el rango de LAN, referenciado desde el manifest. Es la única modificación **obligatoria**.

### 3. La mitad de las funciones exigen búsqueda web

`LocalInfoFetcher`, `RestrictionRulesFetcher` (pico y placa) y `ZoneBriefFetcher` piden `needsWebSearch = true`, y `OpenAiCompatibleProvider.supportsWebSearch` es `false` por diseño (`OpenAiCompatibleProvider.kt:25`). Con Nodo tal como está, esas tres darían respuestas inventadas sobre datos que cambian a diario.

Aquí conecta lo de tool calling (ver `MODELOS.md`): si Nodo expone `tools` y devuelve `tool_calls`, RevScope podría marcar `supportsWebSearch = true` para el proveedor local — pero eso exige que RevScope sepa ejecutar la herramienta y hacer el segundo turno, que hoy no sabe.

## Flujo de una petición, paso a paso

1. El usuario toca "Explicar" en un código DTC. `DtcExplainer` arma `AiRequest(system=..., user="P0301...", maxTokens=350)`.
2. `AiProviderFactory.current()` lee la selección de Ajustes; como es `custom` con base URL `http://127.0.0.1:8080/v1`, construye `OpenAiCompatibleProvider`.
3. POST a `http://127.0.0.1:8080/v1/chat/completions` con los dos mensajes y `max_tokens: 350`.
4. El foreground service de Nodo recibe; si el modelo está frío, lo carga (segundos); toma el Mutex de `LlamaCppEngine`; aplica la plantilla de chat del GGUF a los mensajes.
5. Genera. Va emitiendo padding para que RevScope no corte a los 20 s.
6. Al terminar responde `{"choices":[{"message":{"role":"assistant","content":"..."}}]}`.
7. `parseOpenAiChatResponse` extrae el texto; `DtcExplainer` lo muestra. Costo de API: cero.

## Por dónde empezar

**Explicar códigos DTC**, con el Qwen2.5-1.5B. Razones: no necesita web, 350 tokens caben en el timeout con el 1.5B (~16 s), el conocimiento sobre códigos OBD2 es estático y un 1.5B lo maneja, y es exactamente la función donde pagar API por algo que se consulta una vez cada tanto duele más.

Segunda: el **resumen de viaje**, que corre de fondo y nadie mira esperando.

Dejar el **chat con mecánico** para el final: 800 tokens no caben ni con el 1.5B.

## Riesgos

- **Nodo matado por el sistema.** Si el foreground service muere, RevScope recibe connection refused y lo reporta como fallo de red — degradación aceptable, pero conviene que RevScope pueda caer de vuelta a un proveedor en la nube.
- **Modelo frío.** La primera petición tras arrancar paga la carga completa (segundos). Precalentar al arrancar el service y mantener el modelo caliente con idle-unload largo.
- **Concurrencia.** Una sola GPU/CPU: el `Mutex` de `LlamaCppEngine` ya serializa. Si RevScope dispara dos peticiones a la vez, la segunda espera — y con el timeout de 20 s, muere. El servidor debería devolver 429 en vez de encolar.
- **Térmica.** Generación sostenida cae 40-60% tras 60-90 s. Un resumen de viaje largo puede empezar rápido y terminar lentísimo.

## Qué no hacer

- No meter la búsqueda web dentro de Nodo para que RevScope "la reciba gratis". Rompe el argumento de privacidad y convierte al host en agente. Lo correcto es exponer `tools` y que el cliente ejecute.
- No implementar la API de Responses ni la de Anthropic. RevScope solo usa `chat/completions` para el proveedor custom.
- No rellenar `usage` con números inventados: RevScope no lo lee, y un dato falso ahí contamina a cualquier otro cliente.

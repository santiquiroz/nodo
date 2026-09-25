package com.santiquiroz.nodo.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Traduce entre el mundo OpenAI (tools, tool_calls, role tool) y el texto plano que
 * entiende el modelo.
 *
 * El puente JNI usa `llama_chat_apply_template`, que no acepta herramientas, así que
 * Nodo construye el bloque a mano con el formato exacto que la plantilla de Qwen2.5
 * genera — verificado leyendo el Jinja del GGUF. Dolphin 3.0 no trae plantilla con
 * herramientas pero obedece la misma instrucción desde el mensaje de sistema.
 */
object ProtocoloDeHerramientas {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val ABRE_LLAMADA = "<tool_call>"
    private const val CIERRA_LLAMADA = "</tool_call>"

    /** El bloque que se concatena al mensaje de sistema. Copia literal de la plantilla de Qwen. */
    fun bloqueDeSistema(herramientas: List<DefinicionDeHerramienta>): String {
        if (herramientas.isEmpty()) return ""
        val firmas = herramientas.joinToString("\n") { json.encodeToString(JsonObject.serializer(), aJsonDeOpenAi(it)) }
        return buildString {
            append("\n\n# Tools\n\n")
            append("You may call one or more functions to assist with the user query.\n\n")
            append("You are provided with function signatures within <tools></tools> XML tags:\n")
            append("<tools>\n")
            append(firmas)
            append("\n</tools>\n\n")
            append("For each function call, return a json object with function name and arguments ")
            append("within <tool_call></tool_call> XML tags:\n")
            append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
        }
    }

    /** El resultado de una herramienta viaja como turno de usuario envuelto en <tool_response>. */
    fun comoRespuestaDeHerramienta(contenido: String): String =
        "<tool_response>\n$contenido\n</tool_response>"

    fun aJsonDeOpenAi(definicion: DefinicionDeHerramienta): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", definicion.nombre)
                put("description", definicion.descripcion)
                put("parameters", definicion.parametros)
            },
        )
    }

    /**
     * Extrae las llamadas del texto generado. Acepta las etiquetas de Qwen y, como red de
     * seguridad, un JSON suelto con name+arguments: los modelos pequeños se olvidan de las
     * etiquetas a menudo y aun así la intención es inequívoca.
     */
    fun extraerLlamadas(texto: String, generarId: (Int) -> String = { "call_$it" }): List<LlamadaDeHerramienta> {
        val etiquetadas = extraerEntreEtiquetas(texto)
        val crudas = if (etiquetadas.isNotEmpty()) etiquetadas else extraerJsonSuelto(texto)
        return crudas.mapIndexedNotNull { indice, bruto ->
            aLlamada(bruto, generarId(indice))
        }
    }

    /** El texto que queda para el usuario una vez quitadas las llamadas. */
    fun textoSinLlamadas(texto: String): String =
        if (extraerEntreEtiquetas(texto).isEmpty()) quitarJsonSuelto(texto) else quitarEtiquetadas(texto)

    private fun quitarEtiquetadas(texto: String): String =
        texto.replace(Regex("$ABRE_LLAMADA.*?$CIERRA_LLAMADA", RegexOption.DOT_MATCHES_ALL), "").trim()

    private fun quitarJsonSuelto(texto: String): String {
        val objeto = objetoDeLlamadaSuelta(texto) ?: return quitarEtiquetadas(texto)
        return sinVallas(texto).removePrefix(objeto).trim()
    }

    private fun extraerEntreEtiquetas(texto: String): List<String> =
        Regex("$ABRE_LLAMADA\\s*(\\{.*?\\})\\s*$CIERRA_LLAMADA", RegexOption.DOT_MATCHES_ALL)
            .findAll(texto)
            .map { it.groupValues[1] }
            .toList()

    /**
     * Llama 3.x no envuelve la llamada en ninguna etiqueta: el turno entero es el JSON,
     * y además nombra los argumentos `parameters`. Aceptamos las dos claves.
     */
    private fun extraerJsonSuelto(texto: String): List<String> = listOfNotNull(objetoDeLlamadaSuelta(texto))

    private fun objetoDeLlamadaSuelta(texto: String): String? {
        val limpio = sinVallas(texto)
        if (!limpio.startsWith("{")) return null
        val objeto = recortarObjeto(limpio) ?: return null
        val tieneArgumentos = objeto.contains("\"arguments\"") || objeto.contains("\"parameters\"")
        return objeto.takeIf { it.contains("\"name\"") && tieneArgumentos && esLlamadaValida(it) }
    }

    private fun sinVallas(texto: String): String = texto.replace(Regex("```(?:json)?"), "").trim()

    private fun esLlamadaValida(objeto: String): Boolean = aLlamada(objeto, id = "") != null

    /** Corta el primer objeto JSON balanceado, ignorando llaves dentro de cadenas. */
    private fun recortarObjeto(texto: String): String? {
        var profundidad = 0
        var enCadena = false
        var escapado = false
        texto.forEachIndexed { indice, caracter ->
            when {
                escapado -> escapado = false
                caracter == '\\' && enCadena -> escapado = true
                caracter == '"' -> enCadena = !enCadena
                enCadena -> Unit
                caracter == '{' -> profundidad++
                caracter == '}' -> {
                    profundidad--
                    if (profundidad == 0) return texto.substring(0, indice + 1)
                }
            }
        }
        return null
    }

    private fun aLlamada(bruto: String, id: String): LlamadaDeHerramienta? = runCatching {
        val objeto = json.parseToJsonElement(bruto).jsonObject
        val nombre = objeto["name"]?.jsonPrimitive?.content ?: return null
        // Qwen y Hermes dicen "arguments"; Llama 3.x dice "parameters"
        val argumentos = objeto["arguments"] ?: objeto["parameters"]
        // OpenAI exige arguments como STRING de JSON, no como objeto
        val argumentosJson = when {
            argumentos == null -> "{}"
            argumentos is kotlinx.serialization.json.JsonPrimitive && argumentos.isString -> argumentos.content
            else -> json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), argumentos)
        }
        LlamadaDeHerramienta(id = id, nombre = nombre, argumentosJson = argumentosJson)
    }.getOrNull()
}

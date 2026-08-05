package com.santiquiroz.nodo.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Un resultado de búsqueda, ya reducido a lo que le sirve al modelo. */
data class ResultadoWeb(val titulo: String, val fragmento: String, val url: String)

/** De dónde salen los resultados. Intercambiable porque ninguna opción es buena para todos. */
interface BuscadorWeb {
    val nombre: String
    suspend fun buscar(consulta: String, maximo: Int): Result<List<ResultadoWeb>>
}

/**
 * SearXNG: metabuscador que el usuario hospeda. Sin API key, sin telemetría de terceros
 * y con el mismo patrón de "apunta a tu propia URL" que ya usa revscope-server.
 *
 * La instancia debe tener habilitado el formato JSON en su settings.yml.
 */
class BuscadorSearxng(private val baseUrl: String) : BuscadorWeb {
    override val nombre = "SearXNG"

    override suspend fun buscar(consulta: String, maximo: Int): Result<List<ResultadoWeb>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl.trimEnd('/')}/search?q=${URLEncoder.encode(consulta, "UTF-8")}" +
                    "&format=json&language=es&safesearch=0"
                val cuerpo = leer(url)
                Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(cuerpo).jsonObject["results"]?.jsonArray.orEmpty()
                    .take(maximo)
                    .map { entrada ->
                        val objeto = entrada.jsonObject
                        ResultadoWeb(
                            titulo = objeto["title"]?.jsonPrimitive?.content.orEmpty(),
                            fragmento = objeto["content"]?.jsonPrimitive?.content.orEmpty(),
                            url = objeto["url"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
            }
        }
}

/**
 * Serper: resultados reales de google.com, no un índice recortado. Es la única vía
 * viable a Google hoy — la Custom Search JSON API está cerrada a clientes nuevos y,
 * según el propio Google, sus motores no incluyen resultados en tiempo real.
 *
 * 2.500 consultas gratis al registrarse; después cuesta alrededor de un dólar por mil.
 */
class BuscadorSerper(private val apiKey: String) : BuscadorWeb {
    override val nombre = "Google (Serper)"

    override suspend fun buscar(consulta: String, maximo: Int): Result<List<ResultadoWeb>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cuerpo = """
                    {"q":${Json.encodeToString(String.serializer(), consulta)},
                     "gl":"co","hl":"es","num":$maximo}
                """.trimIndent()
                val respuesta = postear(
                    url = "https://google.serper.dev/search",
                    cuerpo = cuerpo,
                    cabeceras = mapOf("X-API-KEY" to apiKey, "Content-Type" to "application/json"),
                )
                val raiz = Json { ignoreUnknownKeys = true }.parseToJsonElement(respuesta).jsonObject

                // Cuando Google muestra una respuesta directa, va primero: es lo más útil
                // para un modelo pequeño, que sintetiza mal diez fragmentos contradictorios.
                val directa = raiz["answerBox"]?.jsonObject?.let { caja ->
                    val texto = listOfNotNull(
                        caja["answer"]?.jsonPrimitive?.content,
                        caja["snippet"]?.jsonPrimitive?.content,
                    ).firstOrNull()
                    texto?.let {
                        ResultadoWeb(
                            titulo = caja["title"]?.jsonPrimitive?.content ?: "Respuesta directa de Google",
                            fragmento = it,
                            url = caja["link"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
                }

                val organicos = raiz["organic"]?.jsonArray.orEmpty().take(maximo).map { entrada ->
                    val objeto = entrada.jsonObject
                    ResultadoWeb(
                        titulo = objeto["title"]?.jsonPrimitive?.content.orEmpty(),
                        fragmento = listOfNotNull(
                            objeto["date"]?.jsonPrimitive?.content,
                            objeto["snippet"]?.jsonPrimitive?.content,
                        ).joinToString(" — "),
                        url = objeto["link"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }
                listOfNotNull(directa) + organicos
            }
        }
}

/**
 * Brave Search: índice propio, independiente de Google. Su plan gratuito desapareció
 * a comienzos de 2026, así que hoy exige tarjeta y facturación medida.
 */
class BuscadorBrave(private val apiKey: String) : BuscadorWeb {
    override val nombre = "Brave Search"

    override suspend fun buscar(consulta: String, maximo: Int): Result<List<ResultadoWeb>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.search.brave.com/res/v1/web/search" +
                    "?q=${URLEncoder.encode(consulta, "UTF-8")}&count=$maximo&country=co&search_lang=es"
                val cuerpo = leer(url, mapOf("X-Subscription-Token" to apiKey, "Accept" to "application/json"))
                Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(cuerpo).jsonObject["web"]?.jsonObject
                    ?.get("results")?.jsonArray.orEmpty()
                    .take(maximo)
                    .map { entrada ->
                        val objeto = entrada.jsonObject
                        ResultadoWeb(
                            titulo = objeto["title"]?.jsonPrimitive?.content.orEmpty(),
                            fragmento = objeto["description"]?.jsonPrimitive?.content.orEmpty(),
                            url = objeto["url"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
            }
        }
}

/**
 * Gemini con la herramienta de búsqueda de Google. Da la mejor calidad para preguntas
 * locales del día porque devuelve la respuesta ya sintetizada con citas, en vez de diez
 * fragmentos que un modelo de 3B tiene que interpretar.
 *
 * El precio es de honestidad: la consulta sale del teléfono hacia Google y, en el plan
 * gratuito, Google usa esos datos para mejorar sus productos. Por eso la pantalla de
 * Ajustes lo dice sin rodeos.
 */
class BuscadorGemini(private val apiKey: String) : BuscadorWeb {
    override val nombre = "Gemini con Google Search"

    override suspend fun buscar(consulta: String, maximo: Int): Result<List<ResultadoWeb>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cuerpo = """
                    {"contents":[{"parts":[{"text":${Json.encodeToString(String.serializer(), consulta)}}]}],
                     "tools":[{"google_search":{}}]}
                """.trimIndent()
                val respuesta = postear(
                    url = "https://generativelanguage.googleapis.com/v1beta/models/$MODELO:generateContent",
                    cuerpo = cuerpo,
                    cabeceras = mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
                )
                val candidato = Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(respuesta).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("Gemini no devolvió ninguna respuesta")

                val texto = candidato["content"]?.jsonObject?.get("parts")?.jsonArray
                    .orEmpty()
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                    .joinToString("")

                val fuentes = candidato["groundingMetadata"]?.jsonObject
                    ?.get("groundingChunks")?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["web"]?.jsonObject }
                    .map { web ->
                        ResultadoWeb(
                            titulo = web["title"]?.jsonPrimitive?.content.orEmpty(),
                            fragmento = "",
                            url = web["uri"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
                    .take(maximo)

                listOf(ResultadoWeb("Respuesta de Google", texto, "")) + fuentes
            }
        }

    companion object {
        private const val MODELO = "gemini-flash-latest"
    }
}

private fun postear(url: String, cuerpo: String, cabeceras: Map<String, String>): String {
    val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 30_000
        doOutput = true
        setRequestProperty("User-Agent", "Nodo/0.2 (Android)")
        cabeceras.forEach { (clave, valor) -> setRequestProperty(clave, valor) }
    }
    conexion.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
    val codigo = conexion.responseCode
    if (codigo !in 200..299) {
        val detalle = conexion.errorStream?.bufferedReader()?.readText()?.take(200).orEmpty()
        conexion.disconnect()
        error(mensajeDeError(codigo, detalle))
    }
    return conexion.inputStream.bufferedReader().use { it.readText() }
}

private fun mensajeDeError(codigo: Int, detalle: String): String = when (codigo) {
    401, 403 -> "El buscador rechazó la clave de acceso"
    429 -> "Se agotó la cuota de búsquedas por ahora"
    else -> "El buscador respondió $codigo${if (detalle.isBlank()) "" else ": $detalle"}"
}

private fun leer(url: String, cabeceras: Map<String, String> = emptyMap()): String {
    val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Nodo/0.1 (Android)")
        cabeceras.forEach { (clave, valor) -> setRequestProperty(clave, valor) }
    }
    val codigo = conexion.responseCode
    if (codigo !in 200..299) {
        conexion.disconnect()
        error(mensajeDeError(codigo, ""))
    }
    return conexion.inputStream.bufferedReader().use { it.readText() }
}

/**
 * La herramienta que ve el modelo. Manda la consulta a internet, así que solo existe
 * cuando el usuario la habilita explícitamente en Ajustes: es la única parte de Nodo
 * que rompe el "los datos nunca salen del teléfono".
 */
class HerramientaDeBusqueda(private val buscador: BuscadorWeb) : Herramienta {

    override val definicion = DefinicionDeHerramienta(
        nombre = NOMBRE,
        descripcion = "Busca información actual en internet. Úsala cuando la respuesta " +
            "dependa de datos que cambian (noticias, horarios, precios, clima, normativas vigentes).",
        parametros = Json.decodeFromString(
            JsonObject.serializer(),
            """
            {"type":"object",
             "properties":{"consulta":{"type":"string","description":"Términos de búsqueda, en el idioma de la pregunta"}},
             "required":["consulta"]}
            """.trimIndent(),
        ),
    )

    override suspend fun ejecutar(argumentosJson: String): String {
        val consulta = extraerConsulta(argumentosJson)
            ?: return "No se entendió qué buscar."
        return buscador.buscar(consulta, MAXIMO_RESULTADOS).fold(
            onSuccess = { resultados ->
                if (resultados.isEmpty()) "Sin resultados para \"$consulta\"."
                else resultados.joinToString("\n\n") { resultado ->
                    "${resultado.titulo}\n${resultado.fragmento}\nFuente: ${resultado.url}"
                }
            },
            // El fallo vuelve como texto: el modelo puede decirle al usuario qué pasó
            onFailure = { "La búsqueda falló: ${it.message}" },
        )
    }

    private fun extraerConsulta(argumentosJson: String): String? = runCatching {
        Json.parseToJsonElement(argumentosJson).jsonObject["consulta"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val NOMBRE = "buscar_web"
        private const val MAXIMO_RESULTADOS = 4
    }
}

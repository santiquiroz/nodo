package com.santiquiroz.nodo.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * Brave Search: 2.000 consultas al mes gratis con una API key propia. Es la opción
 * realista cuando no se quiere hospedar nada.
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
        error(
            when (codigo) {
                401, 403 -> "El buscador rechazó la clave de acceso"
                429 -> "Se agotó la cuota de búsquedas por ahora"
                else -> "El buscador respondió $codigo"
            },
        )
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

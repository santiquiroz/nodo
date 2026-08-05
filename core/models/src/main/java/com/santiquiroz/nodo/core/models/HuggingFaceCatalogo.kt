package com.santiquiroz.nodo.core.models

import kotlinx.serialization.json.Json

/**
 * Traducción pura de las respuestas de la API de Hugging Face al modelo de dominio.
 * Sin red: así se prueba con las respuestas reales guardadas como texto.
 */
object HuggingFaceCatalogo {

    private val json = Json { ignoreUnknownKeys = true }

    private const val PREFIJO_LICENCIA = "license:"
    private const val TAG_GATED = "gated"

    // Ordenadas de menor a mayor calidad: sirve para elegir una por defecto sensata
    private val CUANTIZACIONES_CONOCIDAS = listOf(
        "IQ1_S", "IQ1_M", "IQ2_XXS", "IQ2_XS", "IQ2_S", "IQ2_M", "Q2_K_S", "Q2_K_L", "Q2_K",
        "IQ3_XXS", "IQ3_XS", "IQ3_S", "IQ3_M", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q3_K_XL",
        "IQ4_XS", "IQ4_NL", "Q4_K_S", "Q4_K_M", "Q4_K_L", "Q4_1",
        // Variantes con los pesos reordenados para las instrucciones vectoriales de ARM
        "Q4_0_4_4", "Q4_0_4_8", "Q4_0_8_8", "Q4_0",
        "Q5_K_S", "Q5_K_M", "Q5_K_L", "Q5_0", "Q5_1", "Q6_K_L", "Q6_K",
        "Q8_0", "BF16", "F16", "F32",
    )

    fun repos(respuestaJson: String): List<RepoDeModelos> =
        runCatching { json.decodeFromString<List<RepoDto>>(respuestaJson) }
            .getOrDefault(emptyList())
            .mapNotNull { dto ->
                dto.identificador?.let { id ->
                    RepoDeModelos(
                        id = id,
                        descargas = dto.downloads,
                        meGusta = dto.likes,
                        licencia = dto.tags.firstOrNull { it.startsWith(PREFIJO_LICENCIA) }
                            ?.removePrefix(PREFIJO_LICENCIA),
                        esGated = dto.tags.any { it == TAG_GATED },
                    )
                }
            }

    fun archivosGguf(respuestaJson: String, repoId: String): List<ArchivoGguf> =
        runCatching { json.decodeFromString<List<ArchivoDto>>(respuestaJson) }
            .getOrDefault(emptyList())
            .filter { it.type == "file" && it.path.endsWith(".gguf", ignoreCase = true) }
            .filterNot { esFragmento(it.path) }
            .map { dto ->
                ArchivoGguf(
                    repoId = repoId,
                    ruta = dto.path,
                    tamanoBytes = dto.tamanoReal,
                    cuantizacion = cuantizacionDelNombre(dto.path),
                )
            }
            .sortedBy { it.tamanoBytes }

    /** Los modelos partidos ("-00001-of-00003.gguf") no se pueden cargar sueltos. */
    private fun esFragmento(ruta: String): Boolean =
        Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(ruta)

    fun cuantizacionDelNombre(ruta: String): String {
        val nombre = ruta.substringAfterLast('/').removeSuffix(".gguf").uppercase()
        // De mayor a menor longitud: "Q4_K_M" debe ganarle a "Q4_K" y "Q4_0" a "Q4"
        return CUANTIZACIONES_CONOCIDAS
            .sortedByDescending { it.length }
            .firstOrNull { nombre.endsWith("-$it") || nombre.endsWith(".$it") || nombre.endsWith("_$it") }
            ?: "DESCONOCIDA"
    }
}

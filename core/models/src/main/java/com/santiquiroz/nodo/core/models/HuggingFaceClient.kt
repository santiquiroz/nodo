package com.santiquiroz.nodo.core.models

import com.santiquiroz.nodo.core.capability.GgufMetadata
import com.santiquiroz.nodo.core.capability.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val BASE = "https://huggingface.co"
private const val TIEMPO_CONEXION_MS = 15_000
private const val TIEMPO_LECTURA_MS = 30_000

/** Suficiente para el bloque de metadata de cualquier GGUF que hemos visto. */
private const val BYTES_DE_CABECERA = 2_000_000L

@Singleton
class HuggingFaceClient @Inject constructor() {

    var token: String? = null

    suspend fun buscar(consulta: String, limite: Int = 30): Result<List<RepoDeModelos>> =
        pedirTexto(
            "$BASE/api/models?filter=gguf&search=${URLEncoder.encode(consulta, "UTF-8")}" +
                "&sort=downloads&direction=-1&limit=$limite",
        ).map { HuggingFaceCatalogo.repos(it) }

    suspend fun archivosDe(repoId: String): Result<List<ArchivoGguf>> =
        pedirTexto("$BASE/api/models/$repoId/tree/main?recursive=true&expand=true")
            .map { HuggingFaceCatalogo.archivosGguf(it, repoId) }

    /**
     * Lee la metadata bajando solo los primeros megas con Range: así el semáforo puede
     * decidir si el modelo cabe ANTES de comprometerse a bajar varios GB.
     */
    suspend fun especificacionDe(archivo: ArchivoGguf): Result<ModelSpec> = withContext(Dispatchers.IO) {
        runCatching {
            val conexion = abrir(archivo.urlDeDescarga).apply {
                setRequestProperty("Range", "bytes=0-$BYTES_DE_CABECERA")
            }
            val codigo = conexion.responseCode
            if (codigo !in 200..299) {
                conexion.disconnect()
                error(mensajeDeError(codigo, archivo.repoId))
            }
            val metadata = conexion.inputStream.use { GgufMetadata.leerDe(it.buffered(), soloLoEsencial = true) }
                ?: error("El archivo no parece un GGUF válido")
            val spec = GgufMetadata.aModelSpec(metadata, archivo.nombreDeArchivo, archivo.tamanoBytes)
                ?: error("La metadata del GGUF no trae arquitectura")
            // La lectura esencial corta antes de general.file_type; el nombre del archivo
            // es la fuente fiable de la cuantización en los repos de Hugging Face.
            spec.copy(cuantizacion = archivo.cuantizacion)
        }
    }

    private suspend fun pedirTexto(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conexion = abrir(url)
            val codigo = conexion.responseCode
            if (codigo !in 200..299) {
                conexion.disconnect()
                error(mensajeDeError(codigo, url))
            }
            conexion.inputStream.bufferedReader().use { it.readText() }
        }
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIEMPO_CONEXION_MS
            readTimeout = TIEMPO_LECTURA_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nodo/0.1 (Android)")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun mensajeDeError(codigo: Int, contexto: String): String = when (codigo) {
        401, 403 -> "Este modelo exige aceptar sus términos en Hugging Face y un token de acceso"
        404 -> "No se encontró: $contexto"
        429 -> "Hugging Face está limitando las peticiones, intenta en un momento"
        else -> "Hugging Face respondió $codigo"
    }
}

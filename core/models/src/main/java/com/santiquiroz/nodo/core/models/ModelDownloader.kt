package com.santiquiroz.nodo.core.models

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ProgresoDescarga {
    data class EnCurso(val bytesDescargados: Long, val bytesTotales: Long) : ProgresoDescarga {
        val fraccion: Float get() = if (bytesTotales > 0) bytesDescargados.toFloat() / bytesTotales else 0f
    }

    data class Terminada(val archivo: File) : ProgresoDescarga
    data class Fallida(val motivo: String) : ProgresoDescarga
}

@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: HuggingFaceClient,
) {

    fun carpeta(): File? = context.getExternalFilesDir("models")

    fun archivoDestino(nombre: String): File? = carpeta()?.let { File(it, nombre) }

    fun yaDescargado(archivo: ArchivoGguf): Boolean =
        archivoDestino(archivo.nombreDeArchivo)?.let { it.exists() && it.length() == archivo.tamanoBytes } == true

    /**
     * Descarga reanudable: si ya hay un parcial, pide solo lo que falta con Range. Escribe
     * sobre un `.parcial` y renombra al final, para que un corte nunca deje un .gguf a medias
     * que luego el motor intente cargar.
     */
    fun descargar(archivo: ArchivoGguf): Flow<ProgresoDescarga> = flow {
        val destino = archivoDestino(archivo.nombreDeArchivo)
        if (destino == null) {
            emit(ProgresoDescarga.Fallida("No hay carpeta de modelos disponible"))
            return@flow
        }
        if (yaDescargado(archivo)) {
            emit(ProgresoDescarga.Terminada(destino))
            return@flow
        }
        val parcial = File(destino.parentFile, "${archivo.nombreDeArchivo}.parcial")
        val espacio = destino.parentFile?.usableSpace ?: 0
        if (espacio < archivo.tamanoBytes - parcial.length()) {
            emit(ProgresoDescarga.Fallida("No hay espacio: faltan ${(archivo.tamanoBytes - espacio) / 1_000_000} MB"))
            return@flow
        }

        val resultado = runCatching { transferir(archivo, parcial, client.token()) { emit(it) } }
        resultado.onFailure { error ->
            currentCoroutineContext().ensureActive()   // una cancelación no es un fallo que reportar
            emit(ProgresoDescarga.Fallida(error.message ?: "Fallo de red"))
            return@flow
        }

        emit(finalizar(archivo, parcial, destino))
    }.flowOn(Dispatchers.IO)

    private fun finalizar(archivo: ArchivoGguf, parcial: File, destino: File): ProgresoDescarga = when {
        parcial.length() != archivo.tamanoBytes ->
            ProgresoDescarga.Fallida("La descarga quedó incompleta, se puede reanudar")
        !integridadVerificada(archivo, parcial) -> descartarCorrupto(parcial)
        !parcial.renameTo(destino) -> ProgresoDescarga.Fallida("No se pudo guardar el archivo final")
        else -> ProgresoDescarga.Terminada(destino)
    }

    // Sin hash publicado (archivo fuera de LFS) solo queda la comprobación de tamaño
    private fun integridadVerificada(archivo: ArchivoGguf, parcial: File): Boolean =
        archivo.sha256?.let { IntegridadDeDescarga.coincideConSha256(parcial, it) } ?: true

    private fun descartarCorrupto(parcial: File): ProgresoDescarga {
        parcial.delete()
        return ProgresoDescarga.Fallida(
            "El archivo descargado no coincide con el de Hugging Face (SHA-256); se borró, vuelve a descargarlo",
        )
    }

    private suspend inline fun transferir(
        archivo: ArchivoGguf,
        parcial: File,
        tokenDeSesion: String?,
        emitir: (ProgresoDescarga) -> Unit,
    ) {
        val (conexion, desde) = conectar(archivo.urlDeDescarga, tokenDeSesion, parcial.length())
        var escritos = desde
        conexion.inputStream.use { entrada ->
            RandomAccessFile(parcial, "rw").use { salida ->
                salida.setLength(desde)
                salida.seek(desde)
                val bufer = ByteArray(256 * 1024)
                var ultimoAviso = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val leidos = entrada.read(bufer)
                    if (leidos <= 0) break
                    salida.write(bufer, 0, leidos)
                    escritos += leidos
                    if (escritos - ultimoAviso >= INTERVALO_AVISO_BYTES) {
                        emitir(ProgresoDescarga.EnCurso(escritos, archivo.tamanoBytes))
                        ultimoAviso = escritos
                    }
                }
            }
        }
        emitir(ProgresoDescarga.EnCurso(escritos, archivo.tamanoBytes))
    }

    // 206 con el rango pedido = reanudar; 200 = empezar de cero; 206 con otro rango = volver a pedir desde 0
    private fun conectar(url: String, tokenDeSesion: String?, pedido: Long): Pair<HttpURLConnection, Long> {
        val conexion = abrirConexion(url, tokenDeSesion, pedido)
        val codigo = conexion.responseCode
        if (codigo !in 200..299) {
            conexion.disconnect()
            error(mensajeDeError(codigo))
        }
        val desde = IntegridadDeDescarga.offsetDeEscritura(codigo, conexion.getHeaderField("Content-Range"), pedido)
        if (desde != null) return conexion to desde
        conexion.disconnect()
        check(pedido > 0) { "Hugging Face respondió un rango que no se pidió" }
        return conectar(url, tokenDeSesion, 0)
    }

    private fun abrirConexion(url: String, tokenDeSesion: String?, pedido: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nodo/0.1 (Android)")
            tokenDeSesion?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (pedido > 0) setRequestProperty("Range", "bytes=$pedido-")
        }

    private fun mensajeDeError(codigo: Int): String = when (codigo) {
        401, 403 -> "Modelo restringido: acepta sus términos en Hugging Face y añade tu token"
        404 -> "El archivo ya no está en Hugging Face"
        416 -> "El archivo cambió en el servidor, bórralo y vuelve a empezar"
        else -> "Hugging Face respondió $codigo"
    }

    fun borrar(nombre: String): Boolean = archivoDestino(nombre)?.delete() == true

    companion object {
        // Avisar cada 4 MB: suficiente para una barra fluida sin inundar la UI
        const val INTERVALO_AVISO_BYTES = 4L * 1024 * 1024
    }
}

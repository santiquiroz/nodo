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

        val resultado = runCatching { transferir(archivo, parcial) { emit(it) } }
        resultado.onFailure { error ->
            currentCoroutineContext().ensureActive()   // una cancelación no es un fallo que reportar
            emit(ProgresoDescarga.Fallida(error.message ?: "Fallo de red"))
            return@flow
        }

        if (parcial.length() != archivo.tamanoBytes) {
            emit(ProgresoDescarga.Fallida("La descarga quedó incompleta, se puede reanudar"))
            return@flow
        }
        if (!parcial.renameTo(destino)) {
            emit(ProgresoDescarga.Fallida("No se pudo guardar el archivo final"))
            return@flow
        }
        emit(ProgresoDescarga.Terminada(destino))
    }.flowOn(Dispatchers.IO)

    private suspend inline fun transferir(
        archivo: ArchivoGguf,
        parcial: File,
        emitir: (ProgresoDescarga) -> Unit,
    ) {
        val yaDescargado = parcial.length()
        val conexion = (URL(archivo.urlDeDescarga).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nodo/0.1 (Android)")
            client.token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (yaDescargado > 0) setRequestProperty("Range", "bytes=$yaDescargado-")
        }

        val codigo = conexion.responseCode
        // 206 = el servidor honró el rango; 200 con parcial existente = hay que empezar de cero
        val reanudando = codigo == HttpURLConnection.HTTP_PARTIAL
        if (codigo !in 200..299) {
            conexion.disconnect()
            error(
                when (codigo) {
                    401, 403 -> "Modelo restringido: acepta sus términos en Hugging Face y añade tu token"
                    404 -> "El archivo ya no está en Hugging Face"
                    416 -> "El archivo cambió en el servidor, bórralo y vuelve a empezar"
                    else -> "Hugging Face respondió $codigo"
                },
            )
        }

        val desde = if (reanudando) yaDescargado else 0L
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

    fun borrar(nombre: String): Boolean = archivoDestino(nombre)?.delete() == true

    companion object {
        // Avisar cada 4 MB: suficiente para una barra fluida sin inundar la UI
        const val INTERVALO_AVISO_BYTES = 4L * 1024 * 1024
    }
}

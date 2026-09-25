package com.santiquiroz.nodo.core.models

import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

object IntegridadDeDescarga {

    private val CONTENT_RANGE = Regex("""^bytes (\d+)-\d+/(\d+|\*)$""", RegexOption.IGNORE_CASE)
    private const val TAMANO_BUFER = 1024 * 1024

    fun rangoEmpiezaEn(contentRange: String?, offset: Long): Boolean =
        contentRange?.trim()
            ?.let { CONTENT_RANGE.matchEntire(it) }
            ?.groupValues?.get(1)
            ?.toLongOrNull() == offset

    // null = el servidor devolvió otro trozo del que se pidió: hay que empezar de cero
    fun offsetDeEscritura(codigo: Int, contentRange: String?, pedido: Long): Long? = when {
        codigo != HttpURLConnection.HTTP_PARTIAL -> 0L
        rangoEmpiezaEn(contentRange, pedido) -> pedido
        else -> null
    }

    fun coincideConSha256(archivo: File, esperado: String): Boolean =
        sha256De(archivo).equals(esperado.trim(), ignoreCase = true)

    private fun sha256De(archivo: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        archivo.inputStream().use { entrada ->
            val bufer = ByteArray(TAMANO_BUFER)
            while (true) {
                val leidos = entrada.read(bufer)
                if (leidos <= 0) break
                digest.update(bufer, 0, leidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

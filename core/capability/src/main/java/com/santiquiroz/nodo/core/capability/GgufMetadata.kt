package com.santiquiroz.nodo.core.capability

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lee el bloque de metadata de un GGUF sin tocar los pesos: trae arquitectura, contexto,
 * capas, cabezas y la plantilla de chat. Sirve tanto para un archivo local como para los
 * primeros megas descargados por rango desde Hugging Face.
 */
object GgufMetadata {

    private const val MAGIC = 0x46554747   // "GGUF" en little-endian
    private const val TIPO_STRING = 8
    private const val TIPO_ARRAY = 9

    fun leer(archivo: File): Map<String, Any>? =
        runCatching { archivo.inputStream().buffered().use { leerDe(it) } }.getOrNull()

    /**
     * @param soloLoEsencial corta en cuanto tiene los campos del semáforo, sin llegar al
     * vocabulario. Imprescindible al leer por rango desde la red: `tokenizer.ggml.tokens`
     * son cientos de miles de cadenas y el bloque completo supera de largo unos pocos MB.
     */
    fun leerDe(entrada: InputStream, soloLoEsencial: Boolean = false): Map<String, Any>? {
        val datos = DataInputStream(entrada)
        if (leerEntero(datos) != MAGIC) return null
        leerEntero(datos)          // versión
        leerLargo(datos)           // número de tensores
        val pares = leerLargo(datos)
        if (pares <= 0 || pares > 100_000) return null

        val metadata = mutableMapOf<String, Any>()
        repeat(pares.toInt()) {
            val clave = leerCadena(datos)
            val tipo = leerEntero(datos)
            if (soloLoEsencial && tipo == TIPO_ARRAY) {
                saltarArreglo(datos)
            } else {
                metadata[clave] = leerValor(datos, tipo)
            }
            if (soloLoEsencial && tieneLoEsencial(metadata)) return metadata
        }
        return metadata
    }

    private fun tieneLoEsencial(metadata: Map<String, Any>): Boolean {
        val arquitectura = metadata["general.architecture"] as? String ?: return false
        return listOf(
            "$arquitectura.block_count",
            "$arquitectura.attention.head_count",
            "$arquitectura.attention.head_count_kv",
            "$arquitectura.embedding_length",
            "$arquitectura.context_length",
        ).all { metadata.containsKey(it) }
    }

    /** Traduce la metadata cruda al ModelSpec que consume el semáforo. */
    fun aModelSpec(metadata: Map<String, Any>, nombre: String, tamanoBytes: Long?): ModelSpec? {
        val arquitectura = metadata["general.architecture"] as? String ?: return null
        return ModelSpec(
            nombre = nombre,
            parametrosMilesDeMillones = (metadata["general.parameter_count"] as? Long)
                ?.let { it / 1_000_000_000.0 },
            cuantizacion = nombreDeCuantizacion(metadata),
            capas = entero(metadata, "$arquitectura.block_count"),
            cabezasKv = entero(metadata, "$arquitectura.attention.head_count_kv"),
            dimensionEmbedding = entero(metadata, "$arquitectura.embedding_length"),
            cabezasAtencion = entero(metadata, "$arquitectura.attention.head_count"),
            contextoEntrenado = entero(metadata, "$arquitectura.context_length").takeIf { it > 0 } ?: 4096,
            tamanoArchivoBytes = tamanoBytes,
        )
    }

    fun plantillaDeChat(metadata: Map<String, Any>): String? =
        metadata["tokenizer.chat_template"] as? String

    // Índices del enum llama_ftype de llama.cpp, no nombres. Ojo: Q4_K_M es 15 y Q4_K_S es 14.
    private val NOMBRES_DE_CUANTIZACION = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L",
        14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K",
        19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S", 22 to "IQ3_XS", 23 to "IQ3_XXS",
        24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S",
        29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16",
    )

    private fun nombreDeCuantizacion(metadata: Map<String, Any>): String =
        NOMBRES_DE_CUANTIZACION[entero(metadata, "general.file_type")] ?: "DESCONOCIDA"

    private fun entero(metadata: Map<String, Any>, clave: String): Int =
        when (val valor = metadata[clave]) {
            is Int -> valor
            is Long -> valor.toInt()
            else -> 0
        }

    private fun leerValor(datos: DataInputStream, tipo: Int): Any = when (tipo) {
        0 -> datos.readUnsignedByte()
        1 -> datos.readByte().toInt()
        2 -> leerCorto(datos)
        3 -> leerCorto(datos)
        4, 5 -> leerEntero(datos)
        6 -> java.lang.Float.intBitsToFloat(leerEntero(datos))
        7 -> datos.readUnsignedByte() != 0
        TIPO_STRING -> leerCadena(datos)
        TIPO_ARRAY -> leerArreglo(datos)
        10, 11 -> leerLargo(datos)
        12 -> java.lang.Double.longBitsToDouble(leerLargo(datos))
        else -> throw IllegalArgumentException("tipo GGUF desconocido: $tipo")
    }

    /** Consume el arreglo sin materializarlo: el vocabulario no cabe ni interesa. */
    private fun saltarArreglo(datos: DataInputStream) {
        val tipoElemento = leerEntero(datos)
        val n = leerLargo(datos)
        repeat(n.toInt().coerceAtLeast(0)) { leerValor(datos, tipoElemento) }
    }

    private fun leerArreglo(datos: DataInputStream): List<Any> {
        val tipoElemento = leerEntero(datos)
        val n = leerLargo(datos)
        // Los vocabularios traen cientos de miles de entradas y no aportan al semáforo
        if (n > 1_000_000) throw IllegalArgumentException("arreglo GGUF absurdo: $n")
        return List(n.toInt()) { leerValor(datos, tipoElemento) }
    }

    private fun leerCadena(datos: DataInputStream): String {
        val largo = leerLargo(datos)
        if (largo < 0 || largo > 10_000_000) throw IllegalArgumentException("cadena GGUF absurda: $largo")
        val bytes = ByteArray(largo.toInt())
        datos.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun leerCorto(datos: DataInputStream): Int {
        val bytes = ByteArray(2).also { datos.readFully(it) }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    }

    private fun leerEntero(datos: DataInputStream): Int {
        val bytes = ByteArray(4).also { datos.readFully(it) }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun leerLargo(datos: DataInputStream): Long {
        val bytes = ByteArray(8).also { datos.readFully(it) }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }
}

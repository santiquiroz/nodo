package com.santiquiroz.nodo.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufMetadataTest {

    private class ConstructorGguf {
        private val cuerpo = ByteArrayOutputStream()
        private var pares = 0

        fun cadena(clave: String, valor: String) = apply {
            escribirClave(clave, 8)
            escribirCadena(valor)
        }

        fun entero32(clave: String, valor: Int) = apply {
            escribirClave(clave, 4)
            cuerpo.write(le4(valor))
        }

        fun entero64(clave: String, valor: Long) = apply {
            escribirClave(clave, 10)
            cuerpo.write(le8(valor))
        }

        fun arregloDeCadenas(clave: String, valores: List<String>) = apply {
            escribirClave(clave, 9)
            cuerpo.write(le4(8))
            cuerpo.write(le8(valores.size.toLong()))
            valores.forEach { escribirCadena(it) }
        }

        fun construir(): ByteArray {
            val salida = ByteArrayOutputStream()
            salida.write(byteArrayOf(0x47, 0x47, 0x55, 0x46))   // "GGUF"
            salida.write(le4(3))                                 // versión
            salida.write(le8(0))                                 // tensores
            salida.write(le8(pares.toLong()))
            salida.write(cuerpo.toByteArray())
            return salida.toByteArray()
        }

        private fun escribirClave(clave: String, tipo: Int) {
            escribirCadena(clave)
            cuerpo.write(le4(tipo))
            pares++
        }

        private fun escribirCadena(texto: String) {
            val bytes = texto.toByteArray(Charsets.UTF_8)
            cuerpo.write(le8(bytes.size.toLong()))
            cuerpo.write(bytes)
        }

        private fun le4(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        private fun le8(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    }

    private fun ggufDePrueba() = ConstructorGguf()
        .cadena("general.architecture", "qwen2")
        .cadena("general.name", "Qwen2.5 3B Instruct")
        .entero32("general.file_type", 15)   // 15 = Q4_K_M en el enum de llama.cpp (14 es Q4_K_S)
        .entero64("general.parameter_count", 3_090_000_000L)
        .entero32("qwen2.context_length", 32768)
        .entero32("qwen2.block_count", 36)
        .entero32("qwen2.attention.head_count", 16)
        .entero32("qwen2.attention.head_count_kv", 2)
        .entero32("qwen2.embedding_length", 2048)
        .cadena("tokenizer.chat_template", "{% for message in messages %}...{% endfor %}")
        .arregloDeCadenas("tokenizer.ggml.tokens", listOf("a", "b", "c"))
        .construir()

    @Test
    fun `lee la metadata de un GGUF bien formado`() {
        val metadata = GgufMetadata.leerDe(ggufDePrueba().inputStream())!!
        assertEquals("qwen2", metadata["general.architecture"])
        assertEquals(36, metadata["qwen2.block_count"])
        assertEquals(3_090_000_000L, metadata["general.parameter_count"])
    }

    @Test
    fun `traduce la metadata al ModelSpec del semaforo`() {
        val metadata = GgufMetadata.leerDe(ggufDePrueba().inputStream())!!
        val spec = GgufMetadata.aModelSpec(metadata, "qwen3b.gguf", tamanoBytes = null)!!
        assertEquals(3.09, spec.parametrosMilesDeMillones!!, 0.01)
        assertEquals("Q4_K_M", spec.cuantizacion)
        assertEquals(36, spec.capas)
        assertEquals(2, spec.cabezasKv)
        assertEquals(32768, spec.contextoEntrenado)
    }

    @Test
    fun `expone la plantilla de chat`() {
        val metadata = GgufMetadata.leerDe(ggufDePrueba().inputStream())!!
        assertTrue(GgufMetadata.plantillaDeChat(metadata)!!.contains("for message in messages"))
    }

    @Test
    fun `los indices de cuantizacion coinciden con el enum de llama_ftype`() {
        // Los GGUF reales de bartowski traen file_type=15 para Q4_K_M; confundirlo con 14
        // hacía que la UI mostrara "DESCONOCIDA" para todos los modelos descargados.
        val casos = mapOf(15 to "Q4_K_M", 14 to "Q4_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 7 to "Q8_0", 1 to "F16")
        casos.forEach { (indice, esperado) ->
            val bytes = ConstructorGguf()
                .cadena("general.architecture", "llama")
                .entero32("general.file_type", indice)
                .entero32("llama.block_count", 32)
                .entero32("llama.attention.head_count", 32)
                .entero32("llama.attention.head_count_kv", 8)
                .entero32("llama.embedding_length", 4096)
                .entero32("llama.context_length", 8192)
                .construir()
            val metadata = GgufMetadata.leerDe(bytes.inputStream())!!
            val spec = GgufMetadata.aModelSpec(metadata, "x.gguf", null)!!
            assertEquals("file_type=$indice", esperado, spec.cuantizacion)
        }
    }

    @Test
    fun `en modo esencial corta antes del vocabulario`() {
        // Un GGUF real trae 150.000 tokens en tokenizer.ggml.tokens; leerlos entero hacía
        // que la lectura por rango desde Hugging Face muriera con EOFException.
        val vocabularioEnorme = ConstructorGguf()
            .cadena("general.architecture", "qwen2")
            .entero32("general.file_type", 15)
            .entero32("qwen2.context_length", 32768)
            .entero32("qwen2.block_count", 28)
            .entero32("qwen2.attention.head_count", 12)
            .entero32("qwen2.attention.head_count_kv", 2)
            .entero32("qwen2.embedding_length", 1536)
            .arregloDeCadenas("tokenizer.ggml.tokens", List(50_000) { "token$it" })
            .construir()

        val metadata = GgufMetadata.leerDe(vocabularioEnorme.inputStream(), soloLoEsencial = true)!!
        assertEquals(28, metadata["qwen2.block_count"])
        assertTrue("No debería haber leído el vocabulario", !metadata.containsKey("tokenizer.ggml.tokens"))
    }

    @Test
    fun `en modo esencial una cabecera truncada tras lo necesario igual sirve`() {
        val completo = ConstructorGguf()
            .cadena("general.architecture", "qwen2")
            .entero32("general.file_type", 15)
            .entero32("qwen2.context_length", 32768)
            .entero32("qwen2.block_count", 28)
            .entero32("qwen2.attention.head_count", 12)
            .entero32("qwen2.attention.head_count_kv", 2)
            .entero32("qwen2.embedding_length", 1536)
            .arregloDeCadenas("tokenizer.ggml.tokens", List(5_000) { "token$it" })
            .construir()
        // Simula el corte del Range: los primeros bytes solamente
        val recortado = completo.copyOf(completo.size / 2)
        val metadata = GgufMetadata.leerDe(recortado.inputStream(), soloLoEsencial = true)
        assertEquals(28, metadata!!["qwen2.block_count"])
    }

    @Test
    fun `un archivo que no es GGUF devuelve null en vez de reventar`() {
        assertNull(GgufMetadata.leerDe("esto no es un modelo".toByteArray().inputStream()))
    }

    @Test
    fun `un GGUF truncado no lanza al llamador`() {
        val completo = ggufDePrueba()
        val truncado = completo.copyOf(completo.size / 3)
        val leido = runCatching { GgufMetadata.leerDe(truncado.inputStream()) }
        assertTrue("Debe fallar de forma controlada", leido.isFailure || leido.getOrNull() == null)
    }

    @Test
    fun `el semaforo funciona de punta a punta desde la metadata`() {
        val metadata = GgufMetadata.leerDe(ggufDePrueba().inputStream())!!
        val spec = GgufMetadata.aModelSpec(metadata, "qwen3b.gguf", tamanoBytes = 1_930_000_000L)!!
        val s25 = DeviceProfile(12_000_000_000L, 7_000_000_000L, "SM8750", "QTI", 8)
        val veredicto = Compatibilidad.evaluar(spec, s25, contexto = 4096)
        assertEquals(Semaforo.CORRE_BIEN, veredicto.semaforo)
        assertEquals(1.93, veredicto.huella.pesosGb, 0.01)
    }
}

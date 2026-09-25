package com.santiquiroz.nodo.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1_000_000_000L

class CompatibilidadTest {

    private val s25Ultra = DeviceProfile(
        ramTotalBytes = 12 * GB,
        ramDisponibleBytes = 7 * GB,
        socModelo = "SM8750",
        socFabricante = "QTI",
        nucleos = 8,
    )

    private val gamaMedia = DeviceProfile(
        ramTotalBytes = 6 * GB,
        ramDisponibleBytes = 3 * GB,
        socModelo = "SM6375",
        socFabricante = "QTI",
        nucleos = 8,
    )

    // Cifras reales del Qwen2.5 Q4_K_M (docs/MODELOS.md)
    private fun qwen(parametrosB: Double, capas: Int) = ModelSpec(
        nombre = "qwen-${parametrosB}b",
        parametrosMilesDeMillones = parametrosB,
        cuantizacion = "Q4_K_M",
        capas = capas,
        cabezasKv = 2,
        dimensionEmbedding = 2048,
        cabezasAtencion = 16,
        contextoEntrenado = 32768,
    )

    @Test
    fun `los pesos de un Q4_K_M salen a 0,55 bytes por parametro`() {
        val huella = Huella.calcular(qwen(3.0, 36), contexto = 4096)
        // 3B * 0.55 = 1.65 GB
        assertEquals(1.65, huella.pesosGb, 0.01)
    }

    @Test
    fun `el KV cache crece con el contexto`() {
        val corto = Huella.calcular(qwen(3.0, 36), contexto = 4096)
        val largo = Huella.calcular(qwen(3.0, 36), contexto = 32768)
        assertTrue("Más contexto debe pesar más", largo.kvCacheGb > corto.kvCacheGb * 7)
    }

    @Test
    fun `el total suma pesos, KV y el overhead del runtime`() {
        val huella = Huella.calcular(qwen(3.0, 36), contexto = 4096)
        assertEquals(huella.pesosGb + huella.kvCacheGb + Huella.OVERHEAD_GB, huella.totalGb, 0.001)
    }

    @Test
    fun `un modelo holgado en un flagship sale en verde`() {
        val veredicto = Compatibilidad.evaluar(qwen(1.5, 28), s25Ultra, contexto = 4096)
        assertEquals(Semaforo.CORRE_BIEN, veredicto.semaforo)
    }

    @Test
    fun `un modelo que cabe pero aprieta sale en amarillo`() {
        // 7 GB disponibles: el umbral verde son 4.2 GB, así que ~5 GB queda en amarillo
        val veredicto = Compatibilidad.evaluar(qwen(7.0, 32), s25Ultra, contexto = 4096)
        assertEquals(Semaforo.JUSTO, veredicto.semaforo)
    }

    @Test
    fun `un modelo mas grande que la RAM disponible sale en rojo`() {
        val veredicto = Compatibilidad.evaluar(qwen(14.0, 40), s25Ultra, contexto = 4096)
        assertEquals(Semaforo.NO_CABE, veredicto.semaforo)
    }

    @Test
    fun `el mismo modelo puede ser verde en un flagship y rojo en gama media`() {
        val modelo = qwen(3.0, 36)
        assertEquals(Semaforo.CORRE_BIEN, Compatibilidad.evaluar(modelo, s25Ultra, 4096).semaforo)
        assertEquals(Semaforo.NO_CABE, Compatibilidad.evaluar(modelo, gamaMedia, 4096).semaforo)
    }

    @Test
    fun `la velocidad estimada se calibra con las mediciones reales del S25`() {
        // Medido en docs/MODELOS.md: 0.5B=60, 1.5B=22, 3B=10 tok/s
        val medio = Compatibilidad.evaluar(qwen(1.5, 28), s25Ultra, 4096).tokensPorSegundoEstimados
        assertEquals(20.0, medio, 5.0)
        val grande = Compatibilidad.evaluar(qwen(3.0, 36), s25Ultra, 4096).tokensPorSegundoEstimados
        assertEquals(10.0, grande, 3.0)
    }

    @Test
    fun `un dispositivo mas lento estima menos tokens por segundo`() {
        val modelo = qwen(1.5, 28)
        val rapido = Compatibilidad.evaluar(modelo, s25Ultra, 4096).tokensPorSegundoEstimados
        val lento = Compatibilidad.evaluar(modelo, gamaMedia, 4096).tokensPorSegundoEstimados
        assertTrue("El de gama media debe estimar menos", lento < rapido)
    }

    // Llama 3.2 3B: 8 cabezas KV, así que el KV cache pesa mucho más que en Qwen
    private val llama3b = ModelSpec(
        nombre = "llama-3.2-3b",
        parametrosMilesDeMillones = 3.2,
        cuantizacion = "Q4_K_M",
        capas = 28,
        cabezasKv = 8,
        dimensionEmbedding = 3072,
        cabezasAtencion = 24,
        contextoEntrenado = 131072,
    )

    @Test
    fun `el mismo modelo pasa de verde a amarillo al subir el contexto configurado`() {
        assertEquals(Semaforo.CORRE_BIEN, Compatibilidad.evaluar(llama3b, s25Ultra, 4096).semaforo)
        assertEquals(Semaforo.JUSTO, Compatibilidad.evaluar(llama3b, s25Ultra, 32768).semaforo)
    }

    @Test
    fun `el veredicto cita el contexto con el que se evaluo`() {
        val veredicto = Compatibilidad.evaluar(llama3b, s25Ultra, 32768)
        assertTrue("Debe citar el contexto: ${veredicto.razon}", veredicto.razon.contains("contexto 32768"))
    }

    @Test
    fun `el veredicto cita el contexto recortado al entrenado`() {
        val veredicto = Compatibilidad.evaluar(qwen(3.0, 36), s25Ultra, 999_999)
        assertTrue("Debe citar el contexto real: ${veredicto.razon}", veredicto.razon.contains("contexto 32768"))
    }

    @Test
    fun `el veredicto explica el porque con cifras`() {
        val veredicto = Compatibilidad.evaluar(qwen(3.0, 36), s25Ultra, 4096)
        assertTrue("Debe citar el uso y la RAM: ${veredicto.razon}", veredicto.razon.contains("7.0 GB"))
        assertTrue("Debe citar la velocidad: ${veredicto.razon}", veredicto.razon.contains("tok/s"))
    }

    @Test
    fun `una cuantizacion desconocida usa el peor caso en vez de fallar`() {
        val raro = qwen(3.0, 36).copy(cuantizacion = "IQ3_XXS_RARO")
        val huella = Huella.calcular(raro, contexto = 4096)
        assertTrue("Debe asumir algo, no cero", huella.pesosGb > 0)
    }

    @Test
    fun `sin dato de parametros la huella se estima desde el tamano del archivo`() {
        val sinParams = ModelSpec(
            nombre = "misterio.gguf",
            parametrosMilesDeMillones = null,
            cuantizacion = "Q4_K_M",
            capas = 32,
            cabezasKv = 8,
            dimensionEmbedding = 4096,
            cabezasAtencion = 32,
            contextoEntrenado = 8192,
            tamanoArchivoBytes = 4 * GB,
        )
        val huella = Huella.calcular(sinParams, contexto = 4096)
        assertEquals(4.0, huella.pesosGb, 0.01)
    }

    @Test
    fun `el contexto pedido se recorta al que el modelo fue entrenado`() {
        val huella = Huella.calcular(qwen(3.0, 36), contexto = 999_999)
        val maximo = Huella.calcular(qwen(3.0, 36), contexto = 32768)
        assertEquals(maximo.kvCacheGb, huella.kvCacheGb, 0.001)
    }
}

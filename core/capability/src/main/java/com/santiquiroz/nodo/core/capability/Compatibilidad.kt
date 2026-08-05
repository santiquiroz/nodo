package com.santiquiroz.nodo.core.capability

import kotlin.math.min

private const val BYTES_POR_GB = 1_000_000_000.0

/** Lo que se sabe del dispositivo. Sin tipos de Android: así el motor es puro y testeable. */
data class DeviceProfile(
    val ramTotalBytes: Long,
    val ramDisponibleBytes: Long,
    val socModelo: String?,
    val socFabricante: String?,
    val nucleos: Int,
) {
    val ramDisponibleGb: Double get() = ramDisponibleBytes / BYTES_POR_GB
    val ramTotalGb: Double get() = ramTotalBytes / BYTES_POR_GB
}

/** Lo que se sabe del modelo, venga de la metadata del GGUF o de la API de Hugging Face. */
data class ModelSpec(
    val nombre: String,
    val parametrosMilesDeMillones: Double?,
    val cuantizacion: String,
    val capas: Int,
    val cabezasKv: Int,
    val dimensionEmbedding: Int,
    val cabezasAtencion: Int,
    val contextoEntrenado: Int,
    val tamanoArchivoBytes: Long? = null,
)

data class Huella(
    val pesosGb: Double,
    val kvCacheGb: Double,
) {
    val totalGb: Double get() = pesosGb + kvCacheGb + OVERHEAD_GB

    companion object {
        /** Runtime, buffers de cómputo y el propio proceso de Android. */
        const val OVERHEAD_GB = 1.2

        // Bytes por parámetro de cada cuantización de llama.cpp (incluye el overhead de bloque)
        private val BYTES_POR_PARAMETRO = mapOf(
            "F32" to 4.0, "F16" to 2.0, "BF16" to 2.0,
            "Q8_0" to 1.06, "Q6_K" to 0.82, "Q5_K_M" to 0.70, "Q5_0" to 0.69,
            "Q4_K_M" to 0.55, "Q4_K_S" to 0.52, "Q4_0" to 0.50,
            "Q3_K_M" to 0.43, "Q2_K" to 0.33,
        )

        // Peor caso conocido: preferimos sobrestimar antes que prometer que cabe y morir por OOM
        private const val BYTES_POR_PARAMETRO_DESCONOCIDO = 1.06

        fun calcular(modelo: ModelSpec, contexto: Int): Huella {
            val contextoReal = min(contexto, modelo.contextoEntrenado)
            return Huella(
                pesosGb = pesosGb(modelo),
                kvCacheGb = kvCacheGb(modelo, contextoReal),
            )
        }

        private fun pesosGb(modelo: ModelSpec): Double {
            modelo.tamanoArchivoBytes?.let { return it / BYTES_POR_GB }
            val params = modelo.parametrosMilesDeMillones ?: return 0.0
            val bytesPorParam = BYTES_POR_PARAMETRO[modelo.cuantizacion.uppercase()]
                ?: BYTES_POR_PARAMETRO_DESCONOCIDO
            return params * bytesPorParam
        }

        // 2 tensores (K y V) x capas x cabezas_kv x dim_cabeza x contexto x 2 bytes (f16)
        private fun kvCacheGb(modelo: ModelSpec, contexto: Int): Double {
            if (modelo.cabezasAtencion == 0) return 0.0
            val dimensionCabeza = modelo.dimensionEmbedding / modelo.cabezasAtencion
            val bytes = 2.0 * modelo.capas * modelo.cabezasKv * dimensionCabeza * contexto * 2
            return bytes / BYTES_POR_GB
        }
    }
}

enum class Semaforo { CORRE_BIEN, JUSTO, NO_CABE }

data class Veredicto(
    val semaforo: Semaforo,
    val huella: Huella,
    val tokensPorSegundoEstimados: Double,
    val razon: String,
)

object Compatibilidad {

    /** Debajo de esta fracción de la RAB disponible el sistema no empieza a matar procesos. */
    private const val MARGEN_COMODO = 0.6

    /**
     * tok/s ≈ CONSTANTE / parámetros. La constante sale de las mediciones propias en el
     * S25 Ultra (docs/MODELOS.md): 0.5B→60, 1.5B→22, 3B→10 dan 30, 33 y 30.
     */
    private const val RENDIMIENTO_FLAGSHIP = 30.0

    fun evaluar(modelo: ModelSpec, dispositivo: DeviceProfile, contexto: Int): Veredicto {
        val huella = Huella.calcular(modelo, contexto)
        val disponible = dispositivo.ramDisponibleGb
        val semaforo = when {
            huella.totalGb >= disponible -> Semaforo.NO_CABE
            huella.totalGb < disponible * MARGEN_COMODO -> Semaforo.CORRE_BIEN
            else -> Semaforo.JUSTO
        }
        val velocidad = velocidadEstimada(modelo, dispositivo)
        return Veredicto(
            semaforo = semaforo,
            huella = huella,
            tokensPorSegundoEstimados = velocidad,
            razon = explicar(semaforo, huella, disponible, velocidad),
        )
    }

    private fun velocidadEstimada(modelo: ModelSpec, dispositivo: DeviceProfile): Double {
        val params = modelo.parametrosMilesDeMillones
            ?: parametrosDesdeTamano(modelo)
            ?: return 0.0
        if (params <= 0) return 0.0
        return RENDIMIENTO_FLAGSHIP * factorDelDispositivo(dispositivo) / params
    }

    private fun parametrosDesdeTamano(modelo: ModelSpec): Double? =
        modelo.tamanoArchivoBytes?.let { it / BYTES_POR_GB / 0.55 }

    /**
     * Sin una tabla de SoCs que envejece cada seis meses: la RAM total es un proxy honesto
     * de la gama del teléfono, y los núcleos afinan el resto.
     */
    private fun factorDelDispositivo(dispositivo: DeviceProfile): Double = when {
        dispositivo.ramTotalGb >= 11 -> 1.0
        dispositivo.ramTotalGb >= 7 -> 0.7
        else -> 0.45
    } * if (dispositivo.nucleos >= 8) 1.0 else 0.8

    private fun explicar(
        semaforo: Semaforo,
        huella: Huella,
        disponibleGb: Double,
        velocidad: Double,
    ): String {
        val uso = "%.1f/%.1f GB".format(huella.totalGb, disponibleGb)
        val ritmo = "~%.0f tok/s".format(velocidad)
        return when (semaforo) {
            Semaforo.CORRE_BIEN -> "Cabe con holgura: $uso · $ritmo estimados"
            Semaforo.JUSTO -> "Cabe pero sin margen: $uso · $ritmo estimados. " +
                "Puede ir lento o cerrarse si abres otras apps"
            Semaforo.NO_CABE -> "No cabe en memoria: necesita $uso · $ritmo estimados"
        }
    }
}

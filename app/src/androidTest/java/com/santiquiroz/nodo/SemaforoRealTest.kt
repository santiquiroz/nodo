package com.santiquiroz.nodo

import android.app.ActivityManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.capability.Compatibilidad
import com.santiquiroz.nodo.core.capability.DeviceProfile
import com.santiquiroz.nodo.core.capability.GgufMetadata
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El motor de compatibilidad contra los GGUF reales del dispositivo: comprueba que el
 * parser lee metadata de archivos de verdad y que el veredicto es coherente con lo medido.
 */
@RunWith(AndroidJUnit4::class)
class SemaforoRealTest {

    @Test
    fun leeLaMetadataDeCadaGgufYEmiteUnVeredicto() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelos = ctx.getExternalFilesDir("models")
            ?.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.length() }
        assertTrue("No hay GGUF en el dispositivo", modelos.isNotEmpty())

        val am = ctx.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dispositivo = DeviceProfile(
            ramTotalBytes = info.totalMem,
            ramDisponibleBytes = info.availMem,
            socModelo = Build.SOC_MODEL,
            socFabricante = Build.SOC_MANUFACTURER,
            nucleos = Runtime.getRuntime().availableProcessors(),
        )
        Log.i(
            "NodoSemaforo",
            "dispositivo: ${"%.1f".format(dispositivo.ramDisponibleGb)}/${"%.1f".format(dispositivo.ramTotalGb)} GB " +
                "· ${dispositivo.socModelo} · ${dispositivo.nucleos} núcleos",
        )

        modelos.forEach { archivo ->
            val metadata = GgufMetadata.leer(archivo)
            assertNotNull("No se pudo leer la metadata de ${archivo.name}", metadata)
            val spec = GgufMetadata.aModelSpec(metadata!!, archivo.name, archivo.length())
            assertNotNull("No se pudo derivar el ModelSpec de ${archivo.name}", spec)
            val veredicto = Compatibilidad.evaluar(spec!!, dispositivo, contexto = 4096)
            Log.i(
                "NodoSemaforo",
                "${archivo.name} | ${spec.cuantizacion} | ${spec.capas} capas | " +
                    "ctx ${spec.contextoEntrenado} | ${veredicto.semaforo} | " +
                    "${"%.2f".format(veredicto.huella.totalGb)} GB | " +
                    "${"%.1f".format(veredicto.tokensPorSegundoEstimados)} tok/s est · ${veredicto.razon}",
            )
            assertTrue("Huella absurda en ${archivo.name}", veredicto.huella.totalGb > 0)
        }
    }
}

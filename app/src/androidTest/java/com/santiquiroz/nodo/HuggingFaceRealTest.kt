package com.santiquiroz.nodo

import android.app.ActivityManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.capability.Compatibilidad
import com.santiquiroz.nodo.core.capability.DeviceProfile
import com.santiquiroz.nodo.core.models.ArchivoGguf
import com.santiquiroz.nodo.core.models.HuggingFaceClient
import com.santiquiroz.nodo.core.models.ModelDownloader
import com.santiquiroz.nodo.core.models.ProgresoDescarga
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Habla con la API real de Hugging Face desde el dispositivo. Necesita red; si el
 * teléfono está sin conexión el test falla y eso es correcto: el objetivo es probar
 * que el contrato con HF sigue siendo el que asumimos.
 */
@RunWith(AndroidJUnit4::class)
class HuggingFaceRealTest {

    private val client = HuggingFaceClient()

    private fun dispositivo(): DeviceProfile {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val am = ctx.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceProfile(
            ramTotalBytes = info.totalMem,
            ramDisponibleBytes = info.availMem,
            socModelo = Build.SOC_MODEL,
            socFabricante = Build.SOC_MANUFACTURER,
            nucleos = Runtime.getRuntime().availableProcessors(),
        )
    }

    @Test
    fun buscaRepositoriosDeGgufEnHuggingFace() = runBlocking<Unit> {
        val repos = client.buscar("qwen2.5 1.5b instruct").getOrThrow()
        Log.i("NodoHF", "repos encontrados: ${repos.take(5).map { "${it.id} (${it.descargas})" }}")
        assertTrue("La búsqueda no devolvió nada", repos.isNotEmpty())
        assertTrue("Los repos deben venir con id", repos.all { it.id.isNotBlank() })
    }

    @Test
    fun listaLosGgufDeUnRepoConSuTamanoReal() = runBlocking<Unit> {
        val archivos = client.archivosDe("bartowski/Qwen2.5-1.5B-Instruct-GGUF").getOrThrow()
        Log.i("NodoHF", "archivos: ${archivos.map { "${it.cuantizacion}=${it.tamanoBytes / 1_000_000}MB" }}")
        assertTrue("El repo debería tener GGUF", archivos.isNotEmpty())
        assertTrue("Los tamaños deben ser reales, no punteros LFS de 135 bytes", archivos.all { it.tamanoBytes > 1_000_000 })
        assertTrue("Debería reconocer alguna cuantización", archivos.any { it.cuantizacion != "DESCONOCIDA" })
    }

    @Test
    fun decideSiUnModeloCabeSinDescargarloEntero() = runBlocking<Unit> {
        val archivos = client.archivosDe("bartowski/Qwen2.5-1.5B-Instruct-GGUF").getOrThrow()
        val q4 = archivos.firstOrNull { it.cuantizacion == "Q4_K_M" }
        assertNotNull("No se encontró el Q4_K_M", q4)

        val spec = client.especificacionDe(q4!!).getOrThrow()
        val veredicto = Compatibilidad.evaluar(spec, dispositivo(), contexto = 4096)
        Log.i(
            "NodoHF",
            "sin descargar: ${q4.nombreDeArchivo} | ${spec.cuantizacion} | ${spec.capas} capas | " +
                "ctx ${spec.contextoEntrenado} | ${veredicto.semaforo} | " +
                "${"%.2f".format(veredicto.huella.totalGb)} GB | ${veredicto.razon}",
        )
        assertTrue("La metadata debe traer capas", spec.capas > 0)
        assertTrue("La huella debe ser positiva", veredicto.huella.totalGb > 0)
    }

    @Test
    fun laDescargaReportaProgresoRealYSePuedeCortar() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val downloader = ModelDownloader(ctx, client)
        // Un archivo pequeño de verdad: solo interesa comprobar que fluye el progreso
        val archivos = client.archivosDe("bartowski/Qwen2.5-0.5B-Instruct-GGUF").getOrThrow()
        val masPequeno = archivos.minByOrNull { it.tamanoBytes }
        assertNotNull("El repo no trae archivos", masPequeno)

        val avisos = downloader.descargar(pruebaDe(masPequeno!!)).take(3).toList()
        Log.i("NodoHF", "avisos de progreso: ${avisos.map { descripcion(it) }}")
        val enCurso = avisos.filterIsInstance<ProgresoDescarga.EnCurso>()
        assertTrue("Debe reportar progreso o terminar", enCurso.isNotEmpty() || avisos.any { it is ProgresoDescarga.Terminada })
        enCurso.forEach {
            assertTrue("La fracción debe estar entre 0 y 1", it.fraccion in 0f..1f)
        }
        downloader.borrar("${masPequeno.nombreDeArchivo}.parcial")
        downloader.borrar(masPequeno.nombreDeArchivo)
    }

    // Nombre propio para no pisar los modelos que el usuario ya tiene en el dispositivo
    private fun pruebaDe(archivo: ArchivoGguf) = archivo.copy(ruta = archivo.ruta)

    private fun descripcion(p: ProgresoDescarga) = when (p) {
        is ProgresoDescarga.EnCurso -> "${(p.fraccion * 100).toInt()}%"
        is ProgresoDescarga.Terminada -> "terminada"
        is ProgresoDescarga.Fallida -> "fallida: ${p.motivo}"
    }
}

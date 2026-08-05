package com.santiquiroz.nodo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.serving.NodoServerService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Arranca el foreground service DE VERDAD y comprueba el resultado por HTTP.
 *
 * La revisión encontró que el tipo `connectedDevice` exige un permiso prerrequisito desde
 * Android 14: sin él `startForeground` lanza SecurityException y mata el proceso. Ningún
 * test que instancie `NodoHttpServer` a mano puede detectarlo — hay que arrancar el service.
 */
@RunWith(AndroidJUnit4::class)
class ServicioServidorTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    private fun modeloMasPequeno() = contexto.getExternalFilesDir("models")
        ?.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
        ?.minByOrNull { it.length() }

    @After
    fun tearDown() {
        NodoServerService.detener(contexto)
        Thread.sleep(2_000)
    }

    @Test
    fun elServicioArrancaEnPrimerPlanoYAbreElPuerto() {
        val modelo = modeloMasPequeno()
        assertNotNull("Falta un GGUF en el dispositivo", modelo)

        NodoServerService.iniciar(contexto, puerto = PUERTO, enLan = false, rutaModelo = modelo!!.absolutePath)

        val salud = esperarRespuesta("http://127.0.0.1:$PUERTO/health")
        Log.i("NodoServicio", "health → $salud")
        assertTrue(JSONObject(salud).getString("status") in listOf("ok", "sin_modelo"))
    }

    @Test
    fun expuestoEnLanExigeTokenParaUsarElModelo() {
        val modelo = modeloMasPequeno()
        assertNotNull("Falta un GGUF en el dispositivo", modelo)

        NodoServerService.iniciar(contexto, puerto = PUERTO, enLan = true, rutaModelo = modelo!!.absolutePath)
        esperarRespuesta("http://127.0.0.1:$PUERTO/health")   // /health queda abierto a propósito

        val codigo = codigoDe("http://127.0.0.1:$PUERTO/v1/models")
        Log.i("NodoServicio", "GET /v1/models sin token → $codigo")
        assertEquals("Sin token debía rechazar con 401", 401, codigo)
    }

    private fun codigoDe(url: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun esperarRespuesta(url: String): String {
        var ultimo: Exception? = null
        repeat(40) {
            runCatching { return URL(url).readText() }.onFailure { ultimo = it as Exception }
            Thread.sleep(250)
        }
        throw AssertionError(
            "El servicio nunca abrió el puerto (¿startForeground falló?): ${ultimo?.message}",
        )
    }

    companion object {
        private const val PUERTO = 18099
    }
}

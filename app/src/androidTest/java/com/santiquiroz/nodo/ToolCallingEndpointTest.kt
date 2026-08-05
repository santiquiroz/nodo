package com.santiquiroz.nodo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.LlamaCppEngine
import com.santiquiroz.nodo.core.serving.ChatCompletionsService
import com.santiquiroz.nodo.core.serving.NodoHttpServer
import com.santiquiroz.nodo.core.tools.DefinicionDeHerramienta
import com.santiquiroz.nodo.core.tools.Herramienta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tool calling de punta a punta contra el modelo real: un cliente manda `tools`, el
 * modelo pide la herramienta y el servidor devuelve `tool_calls`; y en el otro modo,
 * Nodo ejecuta la herramienta por su cuenta y responde con el dato ya incorporado.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingEndpointTest {

    private var engine: LlamaCppEngine? = null
    private var servidor: NodoHttpServer? = null
    private val puerto = 18111

    private val herramientaDeClima = object : Herramienta {
        var vecesEjecutada = 0
        override val definicion = DefinicionDeHerramienta(
            nombre = "obtener_clima",
            descripcion = "Devuelve la temperatura actual de una ciudad",
            parametros = Json.decodeFromString(
                JsonObject.serializer(),
                """{"type":"object","properties":{"ciudad":{"type":"string"}},"required":["ciudad"]}""",
            ),
        )

        override suspend fun ejecutar(argumentosJson: String): String {
            vecesEjecutada++
            return "Temperatura actual: 24 grados, parcialmente nublado."
        }
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // El 3B es el más pequeño que emite tool_calls fiables (ver docs/MODELOS.md)
        val modelo = ctx.getExternalFilesDir("models")
            ?.listFiles { f -> f.isFile && f.name.contains("3b") && f.name.endsWith(".gguf") }
            ?.firstOrNull()
        assertNotNull("Falta un modelo 3B en el dispositivo", modelo)

        val motor = LlamaCppEngine()
        engine = motor
        motor.load(modelo!!.absolutePath, EngineConfig(contextLength = 4096, threads = 6))
        assertTrue("El modelo no cargó: ${motor.state.value}", motor.state.value is EngineState.Ready)

        servidor = NodoHttpServer(
            service = ChatCompletionsService(motor, herramientasPropias = { listOf(herramientaDeClima) }),
            puerto = puerto,
            soloLocalhost = true,
        ).also { it.iniciar() }
        Thread.sleep(1_000)
    }

    @After
    fun tearDown() {
        runBlocking {
            servidor?.detener()
            engine?.unload()
        }
    }

    @Test
    fun elClienteRecibeToolCallsCuandoMandaSusPropiasHerramientas() {
        val cuerpo = """
            {"model":"local","max_tokens":200,"temperature":0.2,
             "messages":[{"role":"user","content":"¿Qué temperatura hace ahora mismo en Medellín?"}],
             "tools":[{"type":"function","function":{"name":"obtener_clima",
               "description":"Devuelve la temperatura actual de una ciudad",
               "parameters":{"type":"object","properties":{"ciudad":{"type":"string"}},"required":["ciudad"]}}}]}
        """.trimIndent()

        val (codigo, respuesta) = postear(cuerpo)
        Log.i("NodoTools", "respuesta con tools del cliente: ${respuesta.take(400)}")
        assertEquals(200, codigo)

        val eleccion = JSONObject(respuesta).getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", eleccion.getString("finish_reason"))
        val llamada = eleccion.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("obtener_clima", llamada.getJSONObject("function").getString("name"))
        // arguments viaja como string de JSON, tal como exige OpenAI
        val argumentos = JSONObject(llamada.getJSONObject("function").getString("arguments"))
        Log.i("NodoTools", "argumentos: $argumentos")
        assertTrue("Debía extraer la ciudad", argumentos.toString().contains("edell", ignoreCase = true))
        assertEquals("Nodo no debe ejecutar herramientas del cliente", 0, herramientaDeClima.vecesEjecutada)
    }

    @Test
    fun nodoEjecutaSusPropiasHerramientasYResponldeConElDato() {
        // Sin "tools" en la petición: el cliente no sabe de herramientas, Nodo pone las suyas
        val cuerpo = """
            {"model":"local","max_tokens":250,"temperature":0.2,
             "messages":[{"role":"user","content":"¿Qué temperatura hace ahora mismo en Medellín?"}]}
        """.trimIndent()

        val (codigo, respuesta) = postear(cuerpo)
        assertEquals(200, codigo)
        val mensaje = JSONObject(respuesta).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val contenido = mensaje.getString("content")
        Log.i("NodoTools", "modo agente → ejecuciones=${herramientaDeClima.vecesEjecutada} respuesta='$contenido'")

        assertEquals("Nodo debía ejecutar su herramienta", 1, herramientaDeClima.vecesEjecutada)
        assertTrue("La respuesta debía incorporar el dato: '$contenido'", contenido.contains("24"))
    }

    private fun postear(cuerpo: String): Pair<Int, String> {
        val conexion = (URL("http://127.0.0.1:$puerto/v1/chat/completions").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout = 120_000
                doOutput = true
            }
        conexion.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
        val codigo = conexion.responseCode
        val texto = if (codigo in 200..299) {
            conexion.inputStream.bufferedReader().readText()
        } else {
            conexion.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        return codigo to texto.trim()
    }
}

package com.santiquiroz.nodo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.LlamaCppEngine
import com.santiquiroz.nodo.core.serving.ChatCompletionsService
import com.santiquiroz.nodo.core.serving.NodoHttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
 * Gate de integración: habla con el servidor de Nodo exactamente como lo hace
 * `OpenAiCompatibleProvider` de RevScope (c:/personal/OBD2) — mismo cliente
 * HttpURLConnection, mismo cuerpo, mismos timeouts, mismo parseo de la respuesta.
 * Si este test pasa, RevScope funciona contra Nodo.
 */
@RunWith(AndroidJUnit4::class)
class IntegracionRevScopeTest {

    private var engine: LlamaCppEngine? = null
    private var servidor: NodoHttpServer? = null

    // Por encima de 1024: los puertos privilegiados no se pueden bindear sin root
    private val puerto = 18080

    // Copiados de OpenAiCompatibleProvider.kt:87-88
    private val connectTimeoutMs = 10_000
    private val readTimeoutMs = 20_000

    @Before
    fun setUp() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelo = ctx.getExternalFilesDir("models")
            ?.listFiles { f -> f.isFile && f.name.contains("1.5b") && f.name.endsWith(".gguf") }
            ?.firstOrNull()
        assertNotNull("Falta el modelo 1.5B en el dispositivo", modelo)

        val motor = LlamaCppEngine()
        engine = motor
        motor.load(modelo!!.absolutePath, EngineConfig(contextLength = 4096, threads = 6))
        assertTrue("El modelo no cargó: ${motor.state.value}", motor.state.value is EngineState.Ready)

        servidor = NodoHttpServer(ChatCompletionsService(motor), puerto = puerto, soloLocalhost = true)
            .also { it.iniciar() }
        Thread.sleep(1_000)
    }

    @After
    fun tearDown() {
        // Nullable a propósito: si el setUp falla, un tearDown con lateinit enmascara la causa real
        runBlocking {
            servidor?.detener()
            engine?.unload()
        }
    }

    /** Réplica exacta de OpenAiCompatibleProvider.complete() */
    private fun pedirComoRevScope(system: String?, user: String, maxTokens: Int): Pair<Int, String> {
        val conn = (URL("http://127.0.0.1:$puerto/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
        }
        val cuerpo = JSONObject().apply {
            put("model", "local-model")
            put(
                "messages",
                JSONArray().apply {
                    system?.let { put(JSONObject().apply { put("role", "system"); put("content", it) }) }
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                },
            )
            put("max_tokens", maxTokens)
        }.toString().toByteArray(Charsets.UTF_8)

        conn.outputStream.use { it.write(cuerpo) }
        val codigo = conn.responseCode
        val texto = if (codigo in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        return codigo to texto
    }

    /** Réplica de AiResponseParsers.parseOpenAiChatResponse */
    private fun parsearComoRevScope(body: String): String =
        JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")

    @Test
    fun revScopeObtieneRespuestaDelModeloLocal() {
        val (codigo, body) = pedirComoRevScope(
            system = "Eres un mecánico experto. Responde en dos frases.",
            user = "¿Qué significa el código P0301 en un carro?",
            maxTokens = 120,
        )
        assertEquals(200, codigo)
        val contenido = parsearComoRevScope(body)
        Log.i("NodoIntegracion", "DTC P0301 → '$contenido'")
        assertTrue("Respuesta vacía", contenido.isNotBlank())
    }

    @Test
    fun elLatidoEvitaElTimeoutDe20sEnRespuestasLargas() {
        val inicio = System.currentTimeMillis()
        val (codigo, body) = pedirComoRevScope(
            system = null,
            user = "Escribe un resumen largo y detallado de un viaje en carro por la montaña.",
            maxTokens = 600,
        )
        val transcurrido = System.currentTimeMillis() - inicio
        val contenido = parsearComoRevScope(body)
        Log.i("NodoIntegracion", "respuesta larga: ${transcurrido}ms, ${contenido.length} chars")
        assertEquals(200, codigo)
        assertTrue("Respuesta vacía", contenido.isNotBlank())
        assertTrue(
            "La generación duró ${transcurrido}ms: no superó el read timeout, el latido no quedó probado",
            transcurrido > readTimeoutMs,
        )
    }

    @Test
    fun healthYModelsRespondenLoQueEsperaUnClienteOpenAi() {
        val salud = URL("http://127.0.0.1:$puerto/health").readText()
        Log.i("NodoIntegracion", "health → $salud")
        assertEquals("ok", JSONObject(salud).getString("status"))

        val modelos = URL("http://127.0.0.1:$puerto/v1/models").readText()
        Log.i("NodoIntegracion", "models → $modelos")
        val lista = JSONObject(modelos).getJSONArray("data")
        assertTrue("El catálogo está vacío", lista.length() > 0)
        assertTrue(lista.getJSONObject(0).getString("id").endsWith(".gguf"))
    }

    @Test
    fun peticionesSolapadasRecibenRateLimitEnVezDeEncolarse() {
        val resultados = mutableListOf<Int>()
        val primera = Thread {
            resultados.add(pedirComoRevScope(null, "Cuenta una historia larga sobre carros.", 400).first)
        }
        primera.start()
        Thread.sleep(1_500)   // la primera ya tiene el motor tomado
        val segunda = pedirComoRevScope(null, "hola", 10).first
        primera.join()
        Log.i("NodoIntegracion", "concurrencia → primera=${resultados.firstOrNull()} segunda=$segunda")
        assertEquals("La segunda petición debía recibir 429", 429, segunda)
    }
}

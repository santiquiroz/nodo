package com.santiquiroz.nodo.core.serving

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class NodoHttpServerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cliente = HttpClient(CIO)
    private val engine = FakeInferenceEngine()
    private var servidor: NodoHttpServer? = null
    private var puerto = 0

    @After
    fun tearDown() {
        servidor?.detener()
        cliente.close()
    }

    private fun arrancar(
        motor: FakeInferenceEngine = engine,
        token: String? = null,
        margenStatusMs: Long = 3_000L,
        intervaloLatidoMs: Long = 5_000L,
    ): NodoHttpServer {
        puerto = ServerSocket(0).use { it.localPort }
        return NodoHttpServer(
            ChatCompletionsService(motor),
            puerto = puerto,
            soloLocalhost = true,
            token = token,
            margenStatusMs = margenStatusMs,
            intervaloLatidoMs = intervaloLatidoMs,
        ).also {
            it.iniciar()
            servidor = it
        }
    }

    private fun url(ruta: String) = "http://127.0.0.1:$puerto$ruta"

    private fun cuerpoDeChat(stream: Boolean = false, mensajes: String = """[{"role":"user","content":"hola"}]""") =
        """{"model":"nodo","stream":$stream,"messages":$mensajes}"""

    private suspend fun postJson(
        ruta: String,
        cuerpo: String,
        autorizacion: String? = null,
        host: String? = null,
    ): HttpResponse =
        cliente.post(url(ruta)) {
            contentType(ContentType.Application.Json)
            autorizacion?.let { header(HttpHeaders.Authorization, it) }
            host?.let { header(HttpHeaders.Host, it) }
            setBody(cuerpo)
        }

    private fun tipoDeError(texto: String) = json.decodeFromString(ErrorResponse.serializer(), texto).error.type

    private fun completionDe(texto: String) = json.decodeFromString(ChatCompletionResponse.serializer(), texto.trim())

    @Test
    fun `con token, una peticion sin Authorization recibe 401`() = runBlocking {
        arrancar(token = "secreto")
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat())
        assertEquals(HttpStatusCode.Unauthorized, respuesta.status)
        assertEquals("invalid_api_key", tipoDeError(respuesta.bodyAsText()))
    }

    @Test
    fun `con token, un token incorrecto recibe 401`() = runBlocking {
        arrancar(token = "secreto")
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat(), autorizacion = "Bearer otro")
        assertEquals(HttpStatusCode.Unauthorized, respuesta.status)
    }

    @Test
    fun `con token, Bearer con el token correcto recibe 200`() = runBlocking {
        arrancar(token = "secreto")
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat(), autorizacion = "Bearer secreto")
        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertEquals("Hola mundo", completionDe(respuesta.bodyAsText()).choices[0].message.content.texto)
    }

    @Test
    fun `con token, models tambien exige Authorization`() = runBlocking {
        arrancar(token = "secreto")
        assertEquals(HttpStatusCode.Unauthorized, cliente.get(url("/v1/models")).status)
        val autorizado = cliente.get(url("/v1/models")) { header(HttpHeaders.Authorization, "Bearer secreto") }
        assertEquals(HttpStatusCode.OK, autorizado.status)
    }

    @Test
    fun `health responde sin token aunque el servidor lo exija`() = runBlocking {
        arrancar(token = "secreto")
        val respuesta = cliente.get(url("/health"))
        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertEquals("ok", json.decodeFromString(HealthResponse.serializer(), respuesta.bodyAsText()).status)
    }

    @Test
    fun `sin modelo cargado responde 503 model_not_loaded`() = runBlocking {
        arrancar(motor = FakeInferenceEngine(modeloInicial = null))
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat())
        assertEquals(HttpStatusCode.ServiceUnavailable, respuesta.status)
        assertEquals("model_not_loaded", tipoDeError(respuesta.bodyAsText()))
    }

    @Test
    fun `un cuerpo que no es JSON responde 400`() = runBlocking {
        arrancar()
        val respuesta = postJson("/v1/chat/completions", "esto no es json")
        assertEquals(HttpStatusCode.BadRequest, respuesta.status)
        assertEquals("invalid_request_error", tipoDeError(respuesta.bodyAsText()))
    }

    @Test
    fun `messages vacio responde 400`() = runBlocking {
        arrancar()
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat(mensajes = "[]"))
        assertEquals(HttpStatusCode.BadRequest, respuesta.status)
        assertEquals("invalid_request_error", tipoDeError(respuesta.bodyAsText()))
    }

    @Test
    fun `una segunda peticion mientras la primera genera recibe 429`() = runBlocking {
        val compuerta = CompletableDeferred<Unit>()
        engine.antesDeGenerar = { compuerta.await() }
        val servidor = arrancar()
        val primera = async { postJson("/v1/chat/completions", cuerpoDeChat()) }
        esperarHasta { servidor.peticionesAtendidas == 1L }

        val segunda = postJson("/v1/chat/completions", cuerpoDeChat())
        compuerta.complete(Unit)

        assertEquals(HttpStatusCode.TooManyRequests, segunda.status)
        assertEquals("rate_limit_exceeded", tipoDeError(segunda.bodyAsText()))
        assertEquals(HttpStatusCode.OK, primera.await().status)
    }

    @Test
    fun `una generacion mas larga que el margen abre el cuerpo con espacios y cierra con JSON valido`() = runBlocking {
        engine.antesDeGenerar = { delay(600) }
        arrancar(margenStatusMs = 100, intervaloLatidoMs = 50)
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat())
        val texto = respuesta.bodyAsText()

        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertTrue("Se esperaba latido antes del JSON: '${texto.take(20)}'", texto.startsWith(" "))
        val completion = completionDe(texto)
        assertEquals("chat.completion", completion.`object`)
        assertEquals("Hola mundo", completion.choices[0].message.content.texto)
    }

    @Test
    fun `stream responde text event-stream y termina en data DONE`() = runBlocking {
        arrancar()
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat(stream = true))

        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertTrue(respuesta.contentType()?.match(ContentType.Text.EventStream) == true)
        val lineas = respuesta.bodyAsText().lines().filter { it.isNotBlank() }
        assertTrue(lineas.dropLast(1).all { it.startsWith("data: {") })
        assertEquals("data: [DONE]", lineas.last())
    }

    @Test
    fun `chat completions sin el prefijo v1 funciona igual`() = runBlocking {
        arrancar()
        val respuesta = postJson("/chat/completions", cuerpoDeChat())
        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertEquals("Hola mundo", completionDe(respuesta.bodyAsText()).choices[0].message.content.texto)
    }

    @Test
    fun `un Host ajeno en models recibe 403 forbidden_host`() = runBlocking {
        arrancar()
        val respuesta = cliente.get(url("/v1/models")) { header(HttpHeaders.Host, "evil.example:8080") }
        assertEquals(HttpStatusCode.Forbidden, respuesta.status)
        assertEquals("forbidden_host", tipoDeError(respuesta.bodyAsText()))
    }

    @Test
    fun `un Host ajeno en chat completions recibe 403 sin llegar a generar`() = runBlocking {
        val servidor = arrancar()
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat(), host = "evil.example:8080")
        assertEquals(HttpStatusCode.Forbidden, respuesta.status)
        assertEquals("forbidden_host", tipoDeError(respuesta.bodyAsText()))
        assertEquals(0L, servidor.peticionesAtendidas)
    }

    @Test
    fun `un Host ajeno recibe 403 aunque traiga el token correcto`() = runBlocking {
        arrancar(token = "secreto")
        val respuesta = postJson(
            "/v1/chat/completions",
            cuerpoDeChat(),
            autorizacion = "Bearer secreto",
            host = "evil.example",
        )
        assertEquals(HttpStatusCode.Forbidden, respuesta.status)
    }

    @Test
    fun `Host 127 0 0 1 con puerto responde normal`() = runBlocking {
        arrancar()
        val respuesta = cliente.get(url("/v1/models")) { header(HttpHeaders.Host, "127.0.0.1:$puerto") }
        assertEquals(HttpStatusCode.OK, respuesta.status)
    }

    @Test
    fun `Host localhost con puerto responde normal en chat completions`() = runBlocking {
        arrancar()
        val respuesta = postJson("/v1/chat/completions", cuerpoDeChat(), host = "localhost:$puerto")
        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertEquals("Hola mundo", completionDe(respuesta.bodyAsText()).choices[0].message.content.texto)
    }

    @Test
    fun `health sigue accesible con un Host valido y se cierra con uno ajeno`() = runBlocking {
        arrancar()
        val valido = cliente.get(url("/health")) { header(HttpHeaders.Host, "localhost:$puerto") }
        val ajeno = cliente.get(url("/health")) { header(HttpHeaders.Host, "evil.example") }
        assertEquals(HttpStatusCode.OK, valido.status)
        assertEquals(HttpStatusCode.Forbidden, ajeno.status)
    }

    private suspend fun esperarHasta(condicion: () -> Boolean) = withTimeout(5_000) {
        while (!condicion()) delay(10)
    }
}

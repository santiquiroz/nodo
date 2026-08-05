package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.FinishReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatCompletionsServiceTest {

    private lateinit var engine: FakeInferenceEngine
    private lateinit var service: ChatCompletionsService
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        engine = FakeInferenceEngine()
        service = ChatCompletionsService(engine, reloj = { 1_700_000_000_000 }, generarId = { "chatcmpl-fijo" })
    }

    private fun texto(t: String) = TextoDelMensaje(t)

    private fun peticion(
        mensajes: List<WireMessage> = listOf(WireMessage("user", texto("hola"))),
        maxTokens: Int? = null,
        temperature: Float? = null,
    ) = ChatCompletionRequest(model = "cualquiera", messages = mensajes, maxTokens = maxTokens, temperature = temperature)

    private suspend fun resultadoDe(p: ChatCompletionRequest): CompletionResult = service.generar(p).first()

    @Test
    fun `la respuesta trae la forma que exige un cliente OpenAI`() = runTest {
        val ok = resultadoDe(peticion()) as CompletionResult.Ok
        assertEquals("Hola mundo", ok.respuesta.choices[0].message.content.texto)
        assertEquals("assistant", ok.respuesta.choices[0].message.role)
        assertEquals("stop", ok.respuesta.choices[0].finishReason)
        assertEquals("modelo-de-prueba.gguf", ok.respuesta.model)
        assertEquals("chat.completion", ok.respuesta.`object`)
    }

    @Test
    fun `usage suma los tokens de prompt y de respuesta`() = runTest {
        val ok = resultadoDe(peticion()) as CompletionResult.Ok
        assertEquals(7, ok.respuesta.usage.promptTokens)
        assertEquals(2, ok.respuesta.usage.completionTokens)
        assertEquals(9, ok.respuesta.usage.totalTokens)
    }

    @Test
    fun `el contenido se serializa como string plano, no como objeto`() = runTest {
        val ok = resultadoDe(peticion()) as CompletionResult.Ok
        val texto = json.encodeToString(ChatCompletionResponse.serializer(), ok.respuesta)
        assertTrue("Se esperaba content como string: $texto", texto.contains("\"content\":\"Hola mundo\""))
    }

    @Test
    fun `los roles del wire se traducen al dominio y developer cuenta como system`() = runTest {
        resultadoDe(
            peticion(
                listOf(
                    WireMessage("developer", texto("eres un mecánico")),
                    WireMessage("user", texto("P0301")),
                    WireMessage("assistant", texto("respuesta previa")),
                ),
            ),
        )
        assertEquals(
            listOf(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT),
            engine.ultimosMensajes.map { it.role },
        )
    }

    @Test
    fun `un rol desconocido se descarta en vez de reventar`() = runTest {
        resultadoDe(peticion(listOf(WireMessage("tool", texto("ignorado")), WireMessage("user", texto("hola")))))
        assertEquals(1, engine.ultimosMensajes.size)
        assertEquals(ChatMessage.Role.USER, engine.ultimosMensajes[0].role)
    }

    @Test
    fun `max_tokens y temperature del cliente llegan al motor`() = runTest {
        resultadoDe(peticion(maxTokens = 350, temperature = 0.2f))
        assertEquals(350, engine.ultimosParams?.maxTokens)
        assertEquals(0.2f, engine.ultimosParams?.temperature)
    }

    @Test
    fun `sin modelo cargado se rechaza antes de generar`() = runTest {
        val vacio = FakeInferenceEngine(modeloInicial = null)
        val sinModelo = ChatCompletionsService(vacio)
        assertEquals(RechazoPrevio.SIN_MODELO, sinModelo.revisarAntesDeResponder(peticion()))
        assertNull(vacio.ultimosParams)
    }

    @Test
    fun `messages vacio se rechaza antes de generar`() = runTest {
        assertEquals(RechazoPrevio.PETICION_INVALIDA, service.revisarAntesDeResponder(peticion(emptyList())))
    }

    @Test
    fun `una peticion valida no se rechaza`() = runTest {
        assertNull(service.revisarAntesDeResponder(peticion()))
    }

    @Test
    fun `un fallo del motor no se disfraza de respuesta exitosa`() = runTest {
        engine.falloSimulado = "Contexto agotado"
        val fallo = resultadoDe(peticion()) as CompletionResult.Fallo
        assertEquals("Contexto agotado", fallo.mensaje)
        assertEquals("engine_error", fallo.tipo)
    }

    @Test
    fun `el corte por limite de tokens se reporta como finish_reason length`() = runTest {
        engine.razonDeCorte = FinishReason.LIMITE_TOKENS
        val ok = resultadoDe(peticion()) as CompletionResult.Ok
        assertEquals("length", ok.respuesta.choices[0].finishReason)
    }

    @Test
    fun `el stream emite un chunk por token, el cierre y el fin`() = runTest {
        engine.piezas = listOf("uno", "dos")
        val eventos = service.generarStream(peticion()).toList()
        val chunks = eventos.filterIsInstance<StreamEvent.Chunk>()
        assertEquals("assistant", chunks[0].chunk.choices[0].delta.role)
        assertEquals("uno", chunks[0].chunk.choices[0].delta.content)
        assertNull(chunks[1].chunk.choices[0].delta.role)
        assertEquals("stop", chunks.last().chunk.choices[0].finishReason)
        assertTrue(eventos.last() is StreamEvent.Fin)
    }

    // --- Formas de `content` que emiten los SDK oficiales de OpenAI ---

    @Test
    fun `acepta content como array de partes de texto`() {
        val cuerpo = """
            {"model":"x","messages":[{"role":"user","content":[{"type":"text","text":"hola "},{"type":"text","text":"mundo"}]}]}
        """.trimIndent()
        val p = json.decodeFromString(ChatCompletionRequest.serializer(), cuerpo)
        assertEquals("hola mundo", p.messages[0].content.texto)
    }

    @Test
    fun `acepta content nulo en un turno del asistente`() {
        val cuerpo = """
            {"model":"x","messages":[{"role":"assistant","content":null},{"role":"user","content":"hola"}]}
        """.trimIndent()
        val p = json.decodeFromString(ChatCompletionRequest.serializer(), cuerpo)
        assertEquals("", p.messages[0].content.texto)
        assertEquals("hola", p.messages[1].content.texto)
    }

    @Test
    fun `un turno con contenido vacio no llega al motor`() = runTest {
        resultadoDe(peticion(listOf(WireMessage("assistant", texto("")), WireMessage("user", texto("hola")))))
        assertEquals(1, engine.ultimosMensajes.size)
    }
}

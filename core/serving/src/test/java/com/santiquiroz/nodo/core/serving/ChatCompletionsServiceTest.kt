package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.FinishReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatCompletionsServiceTest {

    private lateinit var engine: FakeInferenceEngine
    private lateinit var service: ChatCompletionsService

    @Before
    fun setUp() {
        engine = FakeInferenceEngine()
        service = ChatCompletionsService(engine, reloj = { 1_700_000_000_000 }, generarId = { "chatcmpl-fijo" })
    }

    private fun peticion(
        mensajes: List<WireMessage> = listOf(WireMessage("user", "hola")),
        maxTokens: Int? = null,
        temperature: Float? = null,
    ) = ChatCompletionRequest(model = "cualquiera", messages = mensajes, maxTokens = maxTokens, temperature = temperature)

    private suspend fun resultadoDe(p: ChatCompletionRequest): CompletionResult =
        (service.generarConLatido(p).toList().last() as RespuestaParcial.Final).resultado

    @Test
    fun `la respuesta trae la forma que exige un cliente OpenAI`() = runTest {
        val resultado = resultadoDe(peticion())
        val ok = resultado as CompletionResult.Ok
        assertEquals("Hola mundo", ok.respuesta.choices[0].message.content)
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
    fun `emite un latido por token para que el cliente no corte por timeout`() = runTest {
        engine.piezas = listOf("a", "b", "c", "d")
        val partes = service.generarConLatido(peticion()).toList()
        assertEquals(4, partes.count { it is RespuestaParcial.Latido })
        assertTrue(partes.last() is RespuestaParcial.Final)
    }

    @Test
    fun `los roles del wire se traducen al dominio y developer cuenta como system`() = runTest {
        resultadoDe(
            peticion(
                listOf(
                    WireMessage("developer", "eres un mecánico"),
                    WireMessage("user", "P0301"),
                    WireMessage("assistant", "respuesta previa"),
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
        resultadoDe(peticion(listOf(WireMessage("tool", "ignorado"), WireMessage("user", "hola"))))
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
    fun `sin modelo cargado responde fallo y no llama al motor`() = runTest {
        val vacio = FakeInferenceEngine(modeloInicial = null)
        val sinModelo = ChatCompletionsService(vacio)
        val resultado = (sinModelo.generarConLatido(peticion()).toList().last() as RespuestaParcial.Final).resultado
        assertEquals("model_not_loaded", (resultado as CompletionResult.Fallo).tipo)
        assertNull(vacio.ultimosParams)
    }

    @Test
    fun `messages vacio es error de peticion`() = runTest {
        val resultado = resultadoDe(peticion(mensajes = emptyList()))
        assertEquals("invalid_request_error", (resultado as CompletionResult.Fallo).tipo)
    }

    @Test
    fun `un fallo del motor no se disfraza de respuesta exitosa`() = runTest {
        engine.falloSimulado = "Contexto agotado"
        val resultado = resultadoDe(peticion())
        assertEquals("Contexto agotado", (resultado as CompletionResult.Fallo).mensaje)
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
}

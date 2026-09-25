package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.FinishReason
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject as JsonObj
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
        resultadoDe(peticion(listOf(WireMessage("inventado", texto("ignorado")), WireMessage("user", texto("hola")))))
        assertEquals(1, engine.ultimosMensajes.size)
        assertEquals(ChatMessage.Role.USER, engine.ultimosMensajes[0].role)
    }

    @Test
    fun `el rol tool viaja como turno de usuario, que es lo que espera la plantilla`() = runTest {
        resultadoDe(peticion(listOf(WireMessage("tool", texto("28 grados")), WireMessage("user", texto("¿y mañana?")))))
        assertEquals(2, engine.ultimosMensajes.size)
        assertEquals(ChatMessage.Role.USER, engine.ultimosMensajes[0].role)
        assertTrue(engine.ultimosMensajes[0].content.contains("<tool_response>"))
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

    // --- Tool calling ---

    private val herramientaFalsa = object : com.santiquiroz.nodo.core.tools.Herramienta {
        var vecesEjecutada = 0
        var respuesta = "28 grados y despejado"
        override val definicion = com.santiquiroz.nodo.core.tools.DefinicionDeHerramienta(
            nombre = "buscar_web",
            descripcion = "busca",
            parametros = json.decodeFromString(JsonObj.serializer(), """{"type":"object"}"""),
        )

        override suspend fun ejecutar(argumentosJson: String): String {
            vecesEjecutada++
            return respuesta
        }
    }

    private fun conHerramientaPropia() = ChatCompletionsService(
        engine,
        herramientasPropias = { listOf(herramientaFalsa) },
        reloj = { 1_700_000_000_000 },
        generarId = { "chatcmpl-fijo" },
    )

    private fun peticionConTools() = ChatCompletionRequest(
        model = "x",
        messages = listOf(WireMessage("user", texto("¿qué temperatura hace?"))),
        tools = listOf(
            ToolDto(
                function = FunctionDto(
                    name = "buscar_web",
                    description = "Busca en internet",
                    parameters = json.decodeFromString(JsonObj.serializer(), """{"type":"object"}"""),
                ),
            ),
        ),
    )

    @Test
    fun `las herramientas del cliente se inyectan en el mensaje de sistema`() = runTest {
        resultadoDe(peticionConTools())
        val sistema = engine.ultimosMensajes.first()
        assertEquals(ChatMessage.Role.SYSTEM, sistema.role)
        assertTrue("Falta el bloque de tools: ${sistema.content}", sistema.content.contains("<tools>"))
        assertTrue(sistema.content.contains("buscar_web"))
    }

    @Test
    fun `si el modelo pide una herramienta del cliente, se le devuelve tool_calls`() = runTest {
        engine.piezas = listOf("""<tool_call>{"name":"buscar_web","arguments":{"consulta":"clima"}}</tool_call>""")
        val ok = resultadoDe(peticionConTools()) as CompletionResult.Ok
        val eleccion = ok.respuesta.choices[0]
        assertEquals("tool_calls", eleccion.finishReason)
        assertEquals("buscar_web", eleccion.message.toolCalls.single().function.name)
        assertTrue(eleccion.message.toolCalls.single().function.arguments.contains("clima"))
    }

    @Test
    fun `Nodo no ejecuta las herramientas del cliente, se las devuelve`() = runTest {
        engine.piezas = listOf("""<tool_call>{"name":"buscar_web","arguments":{}}</tool_call>""")
        val servicio = conHerramientaPropia()
        servicio.generar(peticionConTools()).first()
        assertEquals("No debía ejecutarla: es del cliente", 0, herramientaFalsa.vecesEjecutada)
    }

    @Test
    fun `con herramienta propia Nodo la ejecuta y sigue la conversacion solo`() = runTest {
        val servicio = conHerramientaPropia()
        engine.respuestasPorTurno = listOf(
            """<tool_call>{"name":"buscar_web","arguments":{"consulta":"clima"}}</tool_call>""",
            "Hacen 28 grados y está despejado.",
        )
        val resultado = servicio.generar(
            ChatCompletionRequest(model = "x", messages = listOf(WireMessage("user", texto("¿qué temperatura hace?")))),
        ).first()
        val ok = resultado as CompletionResult.Ok
        assertEquals(1, herramientaFalsa.vecesEjecutada)
        assertEquals("Hacen 28 grados y está despejado.", ok.respuesta.choices[0].message.content.texto)
        assertEquals("stop", ok.respuesta.choices[0].finishReason)
    }

    @Test
    fun `el resultado de la herramienta llega al modelo envuelto en tool_response`() = runTest {
        val servicio = conHerramientaPropia()
        engine.respuestasPorTurno = listOf(
            """<tool_call>{"name":"buscar_web","arguments":{}}</tool_call>""",
            "listo",
        )
        servicio.generar(
            ChatCompletionRequest(model = "x", messages = listOf(WireMessage("user", texto("hola")))),
        ).first()
        val ultimo = engine.ultimosMensajes.last()
        assertTrue("Se esperaba tool_response: ${ultimo.content}", ultimo.content.contains("<tool_response>"))
        assertTrue(ultimo.content.contains("28 grados"))
    }

    @Test
    fun `una herramienta inexistente se le explica al modelo en vez de reventar`() = runTest {
        val servicio = conHerramientaPropia()
        engine.respuestasPorTurno = listOf(
            """<tool_call>{"name":"no_existe","arguments":{}}</tool_call>""",
            "no pude",
        )
        val resultado = servicio.generar(
            ChatCompletionRequest(model = "x", messages = listOf(WireMessage("user", texto("hola")))),
        ).first()
        assertTrue(resultado is CompletionResult.Ok)
        assertTrue(engine.ultimosMensajes.last().content.contains("no existe"))
    }

    @Test
    fun `un turno del historial con tool_calls se re-emite en el formato del modelo`() = runTest {
        resultadoDe(
            ChatCompletionRequest(
                model = "x",
                messages = listOf(
                    WireMessage("user", texto("¿clima?")),
                    WireMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCallDto(id = "c1", function = FunctionCallDto("buscar_web", """{"consulta":"clima"}"""))),
                    ),
                    WireMessage(role = "tool", content = texto("28 grados"), toolCallId = "c1"),
                ),
            ),
        )
        val roles = engine.ultimosMensajes.map { it.role }
        assertTrue(roles.contains(ChatMessage.Role.ASSISTANT))
        assertTrue(engine.ultimosMensajes.any { it.content.contains("<tool_call>") })
        assertTrue(engine.ultimosMensajes.any { it.content.contains("<tool_response>") })
    }

    @Test
    fun `sin herramientas el prompt no se ensucia`() = runTest {
        resultadoDe(peticion())
        assertTrue(engine.ultimosMensajes.none { it.content.contains("<tools>") })
    }

    // --- Tool calling en streaming y tool_choice ---

    private val llamadaCruda = """<tool_call>{"name":"buscar_web","arguments":{"consulta":"clima"}}</tool_call>"""

    private fun deltasDe(eventos: List<StreamEvent>) =
        eventos.filterIsInstance<StreamEvent.Chunk>().map { it.chunk.choices.single() }

    @Test
    fun `en stream una llamada del modelo llega como delta tool_calls y cierra con tool_calls`() = runTest {
        engine.piezas = listOf(llamadaCruda)
        val eventos = service.generarStream(peticionConTools().copy(stream = true)).toList()
        val elecciones = deltasDe(eventos)

        val llamada = elecciones.firstNotNullOf { it.delta.toolCalls }.single()
        assertEquals(0, llamada.index)
        assertEquals("buscar_web", llamada.function.name)
        assertTrue(llamada.function.arguments.contains("clima"))
        assertEquals("tool_calls", elecciones.last().finishReason)
        assertTrue(elecciones.none { it.delta.content.orEmpty().contains("<tool_call>") })
        assertTrue(eventos.last() is StreamEvent.Fin)
    }

    @Test
    fun `en stream con tools pero sin llamada se emite el texto y stop`() = runTest {
        engine.piezas = listOf("Hacen ", "28 grados")
        val elecciones = deltasDe(service.generarStream(peticionConTools().copy(stream = true)).toList())

        assertEquals("Hacen 28 grados", elecciones.joinToString("") { it.delta.content.orEmpty() })
        assertTrue(elecciones.all { it.delta.toolCalls == null })
        assertEquals("stop", elecciones.last().finishReason)
    }

    @Test
    fun `tool_choice none no inyecta las herramientas del cliente`() = runTest {
        resultadoDe(peticionConTools().copy(toolChoice = JsonPrimitive("none")))
        assertTrue(engine.ultimosMensajes.none { it.content.contains("<tools>") })
    }

    @Test
    fun `tool_choice none tampoco inyecta herramientas en stream`() = runTest {
        service.generarStream(peticionConTools().copy(stream = true, toolChoice = JsonPrimitive("none"))).toList()
        assertTrue(engine.ultimosMensajes.none { it.content.contains("<tools>") })
    }

    @Test
    fun `tool_choice none tampoco expone las herramientas propias de Nodo`() = runTest {
        conHerramientaPropia().generar(peticion().copy(toolChoice = JsonPrimitive("none"))).first()
        assertTrue(engine.ultimosMensajes.none { it.content.contains("<tools>") })
    }

    @Test
    fun `dos respuestas consecutivas con llamadas no repiten ids`() = runTest {
        engine.piezas = listOf(llamadaCruda)
        val primera = resultadoDe(peticionConTools()) as CompletionResult.Ok
        val segunda = resultadoDe(peticionConTools()) as CompletionResult.Ok

        val idPrimera = primera.respuesta.choices[0].message.toolCalls.single().id
        val idSegunda = segunda.respuesta.choices[0].message.toolCalls.single().id
        assertTrue("Ids repetidos: $idPrimera", idPrimera != idSegunda)
    }
}

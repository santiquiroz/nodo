package com.santiquiroz.nodo.core.tools

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.inference.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgenteConHerramientasTest {

    private class MotorFalso : InferenceEngine {
        override val state = MutableStateFlow<EngineState>(
            EngineState.Ready(ModelInfo("m.gguf", "/m.gguf", 1)),
        )
        var respuestasPorTurno: List<String> = listOf("respuesta simple")
        var mensajesPorTurno = mutableListOf<List<ChatMessage>>()
        private var turno = 0

        override suspend fun load(modelPath: String, config: EngineConfig) = Unit
        override suspend fun unload() = Unit

        override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
            mensajesPorTurno.add(messages)
            val salida = respuestasPorTurno.getOrElse(turno) { "sin más respuestas" }
            turno++
            emit(GenerationEvent.Token(salida))
            emit(GenerationEvent.Done(GenerationStats(5, 1, 10, 20)))
        }
    }

    private class HerramientaFalsa(
        override val definicion: DefinicionDeHerramienta,
        private val salida: String = "24 grados",
    ) : Herramienta {
        var ejecuciones = 0
        var ultimosArgumentos: String? = null

        override suspend fun ejecutar(argumentosJson: String): String {
            ejecuciones++
            ultimosArgumentos = argumentosJson
            return salida
        }
    }

    private lateinit var motor: MotorFalso
    private lateinit var agente: AgenteConHerramientas
    private lateinit var buscar: HerramientaFalsa

    private val pregunta = listOf(ChatMessage(ChatMessage.Role.USER, "¿qué clima hace?"))

    @Before
    fun setUp() {
        motor = MotorFalso()
        agente = AgenteConHerramientas(motor)
        buscar = HerramientaFalsa(
            DefinicionDeHerramienta(
                nombre = "buscar_web",
                descripcion = "busca",
                parametros = Json.decodeFromString(JsonObject.serializer(), """{"type":"object"}"""),
            ),
        )
    }

    @Test
    fun `sin herramientas emite los tokens tal cual`() = runTest {
        val eventos = agente.conversar(pregunta, emptyList()).toList()
        val texto = eventos.filterIsInstance<EventoDeAgente.Token>().joinToString("") { it.texto }
        assertEquals("respuesta simple", texto)
        assertTrue(eventos.last() is EventoDeAgente.Fin)
    }

    @Test
    fun `sin herramientas no se ensucia el prompt con el bloque de tools`() = runTest {
        agente.conversar(pregunta, emptyList()).toList()
        assertTrue(motor.mensajesPorTurno.first().none { it.content.contains("<tools>") })
    }

    @Test
    fun `con herramientas se inyecta el bloque en el mensaje de sistema`() = runTest {
        agente.conversar(pregunta, listOf(buscar)).toList()
        val sistema = motor.mensajesPorTurno.first().first()
        assertEquals(ChatMessage.Role.SYSTEM, sistema.role)
        assertTrue(sistema.content.contains("buscar_web"))
    }

    @Test
    fun `ejecuta la herramienta y devuelve el resultado al modelo`() = runTest {
        motor.respuestasPorTurno = listOf(
            """<tool_call>{"name":"buscar_web","arguments":{"consulta":"clima Medellín"}}</tool_call>""",
            "Hacen 24 grados.",
        )
        val eventos = agente.conversar(pregunta, listOf(buscar)).toList()

        assertEquals(1, buscar.ejecuciones)
        assertTrue(buscar.ultimosArgumentos!!.contains("Medellín"))
        val segundoTurno = motor.mensajesPorTurno[1]
        assertTrue("Falta el tool_response", segundoTurno.any { it.content.contains("<tool_response>") })
        assertTrue(segundoTurno.any { it.content.contains("24 grados") })
        val texto = eventos.filterIsInstance<EventoDeAgente.Token>().joinToString("") { it.texto }
        assertEquals("Hacen 24 grados.", texto)
    }

    @Test
    fun `avisa que esta usando la herramienta antes de ejecutarla`() = runTest {
        motor.respuestasPorTurno = listOf(
            """<tool_call>{"name":"buscar_web","arguments":{"consulta":"pico y placa"}}</tool_call>""",
            "listo",
        )
        val eventos = agente.conversar(pregunta, listOf(buscar)).toList()
        val aviso = eventos.filterIsInstance<EventoDeAgente.UsandoHerramienta>().single()
        assertEquals("buscar_web", aviso.nombre)
        assertEquals("pico y placa", aviso.detalle)
        // El aviso llega antes de que la herramienta termine
        assertTrue(eventos.indexOf(aviso) < eventos.indexOfFirst { it is EventoDeAgente.HerramientaLista })
    }

    @Test
    fun `las llamadas no se filtran al texto que ve el usuario`() = runTest {
        motor.respuestasPorTurno = listOf(
            """Déjame ver.<tool_call>{"name":"buscar_web","arguments":{}}</tool_call>""",
            "Son 24 grados.",
        )
        val eventos = agente.conversar(pregunta, listOf(buscar)).toList()
        val texto = eventos.filterIsInstance<EventoDeAgente.Token>().joinToString("") { it.texto }
        assertTrue("No debe aparecer la etiqueta: $texto", !texto.contains("tool_call"))
    }

    @Test
    fun `una herramienta inexistente se le explica al modelo`() = runTest {
        motor.respuestasPorTurno = listOf(
            """<tool_call>{"name":"volar","arguments":{}}</tool_call>""",
            "no puedo",
        )
        agente.conversar(pregunta, listOf(buscar)).toList()
        assertEquals(0, buscar.ejecuciones)
        assertTrue(motor.mensajesPorTurno[1].any { it.content.contains("no existe") })
    }

    @Test
    fun `un fallo del motor corta el bucle`() = runTest {
        val roto = object : InferenceEngine {
            override val state = MutableStateFlow<EngineState>(EngineState.Ready(ModelInfo("m", "/m", 1)))
            override suspend fun load(modelPath: String, config: EngineConfig) = Unit
            override suspend fun unload() = Unit
            override fun generate(messages: List<ChatMessage>, params: GenerationParams) = flow {
                emit(GenerationEvent.Failure("contexto agotado"))
            }
        }
        val eventos = AgenteConHerramientas(roto).conversar(pregunta, listOf(buscar)).toList()
        assertEquals("contexto agotado", (eventos.single() as EventoDeAgente.Fallo).mensaje)
    }

    @Test
    fun `el bucle tiene tope de vueltas`() = runTest {
        // El modelo pide herramienta indefinidamente
        motor.respuestasPorTurno = List(10) { """<tool_call>{"name":"buscar_web","arguments":{}}</tool_call>""" }
        val eventos = AgenteConHerramientas(motor, maximoDeVueltas = 3).conversar(pregunta, listOf(buscar)).toList()
        assertEquals(3, buscar.ejecuciones)
        assertTrue(eventos.last() is EventoDeAgente.Fin)
    }

    @Test
    fun `las estadisticas suman todas las vueltas`() = runTest {
        motor.respuestasPorTurno = listOf(
            """<tool_call>{"name":"buscar_web","arguments":{}}</tool_call>""",
            "listo",
        )
        val fin = agente.conversar(pregunta, listOf(buscar)).toList().last() as EventoDeAgente.Fin
        assertEquals(10, fin.stats.promptTokens)   // 5 por turno, dos turnos
    }
}

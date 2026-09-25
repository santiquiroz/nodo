package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.tools.DefinicionDeHerramienta
import com.santiquiroz.nodo.core.tools.Herramienta
import com.santiquiroz.nodo.core.tools.LlamadaDeHerramienta
import com.santiquiroz.nodo.core.tools.ProtocoloDeHerramientas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

sealed interface CompletionResult {
    data class Ok(val respuesta: ChatCompletionResponse) : CompletionResult
    data class Fallo(val mensaje: String, val tipo: String) : CompletionResult
}

enum class RechazoPrevio(val tipo: String) {
    SIN_MODELO("model_not_loaded"),
    PETICION_INVALIDA("invalid_request_error"),
}

/** Herramientas que Nodo puede ejecutar por su cuenta, si el usuario lo habilitó. */
fun interface ProveedorDeHerramientas {
    suspend fun disponibles(): List<Herramienta>
}

class ChatCompletionsService(
    private val engine: InferenceEngine,
    private val herramientasPropias: ProveedorDeHerramientas = ProveedorDeHerramientas { emptyList() },
    private val reloj: () -> Long = { System.currentTimeMillis() },
    private val generarId: (Long) -> String = { "chatcmpl-$it" },
    private val generarIdDeLlamada: () -> String = { "call_${UUID.randomUUID().toString().replace("-", "")}" },
) {

    fun modeloActivo(): String? = (engine.state.value as? EngineState.Ready)?.model?.name

    fun revisarAntesDeResponder(peticion: ChatCompletionRequest): RechazoPrevio? = when {
        modeloActivo() == null -> RechazoPrevio.SIN_MODELO
        aMensajesDeDominio(peticion.messages, emptyList()).isEmpty() -> RechazoPrevio.PETICION_INVALIDA
        else -> null
    }

    fun mensajeDeRechazo(rechazo: RechazoPrevio): String = when (rechazo) {
        RechazoPrevio.SIN_MODELO -> "No hay modelo cargado en Nodo"
        RechazoPrevio.PETICION_INVALIDA -> "El campo messages no trae ningún mensaje utilizable"
    }

    fun generar(peticion: ChatCompletionRequest): Flow<CompletionResult> = flow {
        val modelo = modeloActivo()
        if (modelo == null) {
            emit(CompletionResult.Fallo(mensajeDeRechazo(RechazoPrevio.SIN_MODELO), RechazoPrevio.SIN_MODELO.tipo))
            return@flow
        }

        val delCliente = herramientasDelCliente(peticion)
        val propias = herramientasPropiasPara(peticion, delCliente)
        val expuestas = delCliente + propias.map { it.definicion }

        var mensajes = aMensajesDeDominio(peticion.messages, expuestas)
        if (mensajes.isEmpty()) {
            emit(
                CompletionResult.Fallo(
                    mensajeDeRechazo(RechazoPrevio.PETICION_INVALIDA),
                    RechazoPrevio.PETICION_INVALIDA.tipo,
                ),
            )
            return@flow
        }

        var acumulado = GenerationStats(0, 0, 0, 0)
        repeat(MAXIMO_DE_VUELTAS) { vuelta ->
            val turno = generarUnTurno(mensajes, peticion)
            turno.fallo?.let {
                emit(CompletionResult.Fallo(it, "engine_error"))
                return@flow
            }
            acumulado = sumar(acumulado, turno.stats)
            val llamadas = if (expuestas.isEmpty()) emptyList() else extraerLlamadas(turno.texto)

            // Sin llamadas, o las pidió el cliente: se responde y que decida él
            if (llamadas.isEmpty()) {
                emit(CompletionResult.Ok(respuestaDeTexto(modelo, turno.texto, acumulado)))
                return@flow
            }
            if (delCliente.isNotEmpty()) {
                emit(CompletionResult.Ok(respuestaConLlamadas(modelo, turno.texto, llamadas, acumulado)))
                return@flow
            }

            // Nodo tiene las herramientas: las ejecuta y sigue la conversación solo
            val resultados = ejecutar(llamadas, propias)
            mensajes = mensajes +
                ChatMessage(ChatMessage.Role.ASSISTANT, turno.texto) +
                resultados.map { ChatMessage(ChatMessage.Role.USER, ProtocoloDeHerramientas.comoRespuestaDeHerramienta(it)) }

            if (vuelta == MAXIMO_DE_VUELTAS - 1) {
                emit(
                    CompletionResult.Ok(
                        respuestaDeTexto(
                            modelo,
                            "No pude terminar: el modelo siguió pidiendo herramientas más de $MAXIMO_DE_VUELTAS veces.",
                            acumulado,
                        ),
                    ),
                )
            }
        }
    }

    fun generarStream(peticion: ChatCompletionRequest): Flow<StreamEvent> = flow {
        val modelo = modeloActivo()
        if (modelo == null) {
            emit(StreamEvent.Error(mensajeDeRechazo(RechazoPrevio.SIN_MODELO), RechazoPrevio.SIN_MODELO.tipo))
            return@flow
        }
        val delCliente = herramientasDelCliente(peticion)
        val mensajes = aMensajesDeDominio(peticion.messages, delCliente)
        if (mensajes.isEmpty()) {
            emit(
                StreamEvent.Error(
                    mensajeDeRechazo(RechazoPrevio.PETICION_INVALIDA),
                    RechazoPrevio.PETICION_INVALIDA.tipo,
                ),
            )
            return@flow
        }
        val encabezado = EncabezadoDeStream(id = generarId(reloj()), creado = reloj() / 1000, modelo = modelo)
        if (delCliente.isEmpty()) {
            emitAll(streamDeTokens(mensajes, peticion, encabezado))
        } else {
            emitAll(streamConHerramientas(mensajes, peticion, encabezado))
        }
    }

    private class EncabezadoDeStream(val id: String, val creado: Long, val modelo: String) {
        fun chunk(delta: Delta, finishReason: String? = null) = StreamEvent.Chunk(
            ChatCompletionChunk(
                id = id,
                created = creado,
                model = modelo,
                choices = listOf(ChunkChoice(delta = delta, finishReason = finishReason)),
            ),
        )
    }

    private fun streamDeTokens(
        mensajes: List<ChatMessage>,
        peticion: ChatCompletionRequest,
        encabezado: EncabezadoDeStream,
    ): Flow<StreamEvent> = flow {
        var primero = true
        generarHastaLaParada(mensajes, peticion).collect { evento ->
            when (evento) {
                is GenerationEvent.Token -> {
                    emit(encabezado.chunk(Delta(role = if (primero) "assistant" else null, content = evento.text)))
                    primero = false
                }
                is GenerationEvent.Done -> {
                    emit(encabezado.chunk(Delta(), aMotivo(evento.stats.finishReason)))
                    emit(StreamEvent.Fin)
                }
                is GenerationEvent.Failure -> emit(StreamEvent.Error(evento.message, "engine_error"))
            }
        }
    }

    // Una llamada no se sabe completa hasta cerrar el turno: se bufferiza como en generar()
    private fun streamConHerramientas(
        mensajes: List<ChatMessage>,
        peticion: ChatCompletionRequest,
        encabezado: EncabezadoDeStream,
    ): Flow<StreamEvent> = flow {
        emit(encabezado.chunk(Delta(role = "assistant")))
        val turno = generarUnTurno(mensajes, peticion)
        turno.fallo?.let {
            emit(StreamEvent.Error(it, "engine_error"))
            return@flow
        }
        val llamadas = extraerLlamadas(turno.texto)
        if (llamadas.isEmpty()) {
            emit(encabezado.chunk(Delta(content = turno.texto)))
            emit(encabezado.chunk(Delta(), aMotivo(turno.stats.finishReason)))
        } else {
            emit(encabezado.chunk(deltaConLlamadas(turno.texto, llamadas)))
            emit(encabezado.chunk(Delta(), "tool_calls"))
        }
        emit(StreamEvent.Fin)
    }

    private fun deltaConLlamadas(texto: String, llamadas: List<LlamadaDeHerramienta>) = Delta(
        content = ProtocoloDeHerramientas.textoSinLlamadas(texto).ifBlank { null },
        toolCalls = llamadas.mapIndexed { indice, llamada ->
            ToolCallDeltaDto(
                index = indice,
                id = llamada.id,
                function = FunctionCallDto(llamada.nombre, llamada.argumentosJson),
            )
        },
    )

    private fun herramientasDelCliente(peticion: ChatCompletionRequest): List<DefinicionDeHerramienta> =
        if (rechazaHerramientas(peticion)) emptyList() else peticion.tools.map { aDefinicion(it) }

    // Si el cliente trae sus propias herramientas, manda él: es su agente, no el nuestro
    private suspend fun herramientasPropiasPara(
        peticion: ChatCompletionRequest,
        delCliente: List<DefinicionDeHerramienta>,
    ): List<Herramienta> =
        if (delCliente.isEmpty() && !rechazaHerramientas(peticion)) herramientasPropias.disponibles() else emptyList()

    private fun rechazaHerramientas(peticion: ChatCompletionRequest): Boolean =
        (peticion.toolChoice as? JsonPrimitive)?.contentOrNull == "none"

    private fun extraerLlamadas(texto: String): List<LlamadaDeHerramienta> =
        ProtocoloDeHerramientas.extraerLlamadas(texto) { generarIdDeLlamada() }

    private class Turno(val texto: String, val stats: GenerationStats, val fallo: String?)

    private suspend fun generarUnTurno(mensajes: List<ChatMessage>, peticion: ChatCompletionRequest): Turno {
        val texto = StringBuilder()
        var stats: GenerationStats? = null
        var fallo: String? = null
        generarHastaLaParada(mensajes, peticion).collect { evento ->
            when (evento) {
                is GenerationEvent.Token -> texto.append(evento.text)
                is GenerationEvent.Done -> stats = evento.stats
                is GenerationEvent.Failure -> fallo = evento.message
            }
        }
        return Turno(
            texto = texto.toString(),
            stats = stats ?: GenerationStats(0, 0, 0, 0),
            fallo = fallo ?: if (stats == null) "El motor terminó sin estadísticas" else null,
        )
    }

    private fun generarHastaLaParada(mensajes: List<ChatMessage>, peticion: ChatCompletionRequest) =
        engine.generate(mensajes, aParametros(peticion)).cortandoEn(peticion.stop)

    private suspend fun ejecutar(llamadas: List<LlamadaDeHerramienta>, propias: List<Herramienta>): List<String> =
        llamadas.map { llamada ->
            val herramienta = propias.firstOrNull { it.definicion.nombre == llamada.nombre }
                ?: return@map "La herramienta \"${llamada.nombre}\" no existe en Nodo."
            runCatching { herramienta.ejecutar(llamada.argumentosJson) }
                .getOrElse { "La herramienta falló: ${it.message}" }
        }

    private fun respuestaDeTexto(modelo: String, texto: String, stats: GenerationStats) = ChatCompletionResponse(
        id = generarId(reloj()),
        created = reloj() / 1000,
        model = modelo,
        choices = listOf(
            Choice(
                message = WireMessage("assistant", TextoDelMensaje(texto)),
                finishReason = aMotivo(stats.finishReason),
            ),
        ),
        usage = usoDe(stats),
    )

    private fun respuestaConLlamadas(
        modelo: String,
        texto: String,
        llamadas: List<LlamadaDeHerramienta>,
        stats: GenerationStats,
    ) = ChatCompletionResponse(
        id = generarId(reloj()),
        created = reloj() / 1000,
        model = modelo,
        choices = listOf(
            Choice(
                message = WireMessage(
                    role = "assistant",
                    content = TextoDelMensaje(ProtocoloDeHerramientas.textoSinLlamadas(texto)),
                    toolCalls = llamadas.map {
                        ToolCallDto(id = it.id, function = FunctionCallDto(it.nombre, it.argumentosJson))
                    },
                ),
                finishReason = "tool_calls",
            ),
        ),
        usage = usoDe(stats),
    )

    private fun usoDe(stats: GenerationStats) = Usage(
        promptTokens = stats.promptTokens,
        completionTokens = stats.generatedTokens,
        totalTokens = stats.promptTokens + stats.generatedTokens,
    )

    private fun sumar(a: GenerationStats, b: GenerationStats) = GenerationStats(
        promptTokens = a.promptTokens + b.promptTokens,
        generatedTokens = a.generatedTokens + b.generatedTokens,
        timeToFirstTokenMs = if (a.timeToFirstTokenMs > 0) a.timeToFirstTokenMs else b.timeToFirstTokenMs,
        totalTimeMs = a.totalTimeMs + b.totalTimeMs,
        finishReason = b.finishReason,
    )

    private fun aMotivo(razon: FinishReason) =
        if (razon == FinishReason.LIMITE_TOKENS) "length" else "stop"

    private fun aDefinicion(dto: ToolDto) = DefinicionDeHerramienta(
        nombre = dto.function.name,
        descripcion = dto.function.description,
        parametros = dto.function.parameters,
    )

    private fun aParametros(peticion: ChatCompletionRequest): GenerationParams {
        val base = GenerationParams()
        return GenerationParams(
            temperature = peticion.temperature ?: base.temperature,
            minP = base.minP,
            maxTokens = peticion.maxTokens ?: peticion.maxCompletionTokens ?: base.maxTokens,
        )
    }

    /**
     * Las herramientas se inyectan en el mensaje de sistema, y los turnos con rol `tool`
     * se convierten en turnos de usuario envueltos en `<tool_response>`: es exactamente
     * lo que hace la plantilla de Qwen, y el puente JNI no acepta herramientas aparte.
     */
    private fun aMensajesDeDominio(
        mensajes: List<WireMessage>,
        herramientas: List<DefinicionDeHerramienta>,
    ): List<ChatMessage> {
        val convertidos = mensajes.mapNotNull { wire -> aMensaje(wire) }
        if (herramientas.isEmpty()) return convertidos
        val bloque = ProtocoloDeHerramientas.bloqueDeSistema(herramientas)
        val yaHaySistema = convertidos.firstOrNull()?.role == ChatMessage.Role.SYSTEM
        return if (yaHaySistema) {
            listOf(convertidos.first().let { it.copy(content = it.content + bloque) }) + convertidos.drop(1)
        } else {
            listOf(ChatMessage(ChatMessage.Role.SYSTEM, "Eres un asistente útil.$bloque")) + convertidos
        }
    }

    private fun aMensaje(wire: WireMessage): ChatMessage? {
        if (wire.role.lowercase() == "tool") {
            return ChatMessage(
                ChatMessage.Role.USER,
                ProtocoloDeHerramientas.comoRespuestaDeHerramienta(wire.content.texto),
            )
        }
        val rol = aRol(wire.role) ?: return null
        // Un turno del asistente que solo pidió herramientas se re-emite en el formato del modelo
        val texto = if (wire.toolCalls.isNotEmpty()) {
            wire.content.texto + wire.toolCalls.joinToString("") { llamada ->
                "\n<tool_call>\n{\"name\": \"${llamada.function.name}\", " +
                    "\"arguments\": ${llamada.function.arguments}}\n</tool_call>"
            }
        } else {
            wire.content.texto
        }
        return texto.takeIf { it.isNotBlank() }?.let { ChatMessage(rol, it) }
    }

    private fun aRol(rol: String): ChatMessage.Role? = when (rol.lowercase()) {
        "system", "developer" -> ChatMessage.Role.SYSTEM
        "user" -> ChatMessage.Role.USER
        "assistant" -> ChatMessage.Role.ASSISTANT
        else -> null
    }

    companion object {
        /** Tope de idas y vueltas cuando Nodo ejecuta las herramientas por su cuenta. */
        const val MAXIMO_DE_VUELTAS = 4
    }
}

sealed interface StreamEvent {
    data class Chunk(val chunk: ChatCompletionChunk) : StreamEvent
    data class Error(val mensaje: String, val tipo: String) : StreamEvent
    data object Fin : StreamEvent
}

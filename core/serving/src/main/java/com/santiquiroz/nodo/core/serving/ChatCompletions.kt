package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Resultado de una generación completa, ya en forma de respuesta OpenAI. */
sealed interface CompletionResult {
    data class Ok(val respuesta: ChatCompletionResponse) : CompletionResult
    data class Fallo(val mensaje: String, val tipo: String) : CompletionResult
}

class ChatCompletionsService(
    private val engine: InferenceEngine,
    private val reloj: () -> Long = { System.currentTimeMillis() },
    private val generarId: (Long) -> String = { "chatcmpl-$it" },
) {

    fun modeloActivo(): String? = (engine.state.value as? EngineState.Ready)?.model?.name

    /**
     * Genera la respuesta completa emitiendo latidos mientras tanto.
     *
     * El latido existe porque clientes como RevScope usan `HttpURLConnection` con un
     * read timeout de 20 s y no piden streaming: sin bytes intermedios cortan la conexión
     * antes de que un modelo 3B termine. Los espacios son JSON válido antes del `{`.
     */
    fun generarConLatido(peticion: ChatCompletionRequest): Flow<RespuestaParcial> = flow {
        val modelo = modeloActivo()
        if (modelo == null) {
            emit(RespuestaParcial.Final(CompletionResult.Fallo("No hay modelo cargado en Nodo", "model_not_loaded")))
            return@flow
        }
        val mensajes = aMensajesDeDominio(peticion.messages)
        if (mensajes.isEmpty()) {
            emit(RespuestaParcial.Final(CompletionResult.Fallo("El campo messages está vacío", "invalid_request_error")))
            return@flow
        }

        val texto = StringBuilder()
        var stats: GenerationStats? = null
        var fallo: String? = null

        engine.generate(mensajes, aParametros(peticion)).collect { evento ->
            when (evento) {
                is GenerationEvent.Token -> {
                    texto.append(evento.text)
                    emit(RespuestaParcial.Latido)
                }
                is GenerationEvent.Done -> stats = evento.stats
                is GenerationEvent.Failure -> fallo = evento.message
            }
        }

        val motivo = fallo
        val cierre = stats
        val resultado = when {
            motivo != null -> CompletionResult.Fallo(motivo, "engine_error")
            cierre == null -> CompletionResult.Fallo("El motor terminó sin estadísticas", "engine_error")
            else -> CompletionResult.Ok(aRespuesta(modelo, texto.toString(), cierre))
        }
        emit(RespuestaParcial.Final(resultado))
    }

    /** Stream SSE estándar: un chunk por token y `data: [DONE]` al final. */
    fun generarStream(peticion: ChatCompletionRequest): Flow<StreamEvent> = flow {
        val modelo = modeloActivo()
        if (modelo == null) {
            emit(StreamEvent.Error("No hay modelo cargado en Nodo", "model_not_loaded"))
            return@flow
        }
        val mensajes = aMensajesDeDominio(peticion.messages)
        if (mensajes.isEmpty()) {
            emit(StreamEvent.Error("El campo messages está vacío", "invalid_request_error"))
            return@flow
        }
        val id = generarId(reloj())
        val creado = reloj() / 1000
        var primero = true

        engine.generate(mensajes, aParametros(peticion)).collect { evento ->
            when (evento) {
                is GenerationEvent.Token -> {
                    emit(
                        StreamEvent.Chunk(
                            ChatCompletionChunk(
                                id = id,
                                created = creado,
                                model = modelo,
                                choices = listOf(
                                    ChunkChoice(
                                        delta = Delta(
                                            role = if (primero) "assistant" else null,
                                            content = evento.text,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                    primero = false
                }
                is GenerationEvent.Done -> {
                    emit(
                        StreamEvent.Chunk(
                            ChatCompletionChunk(
                                id = id,
                                created = creado,
                                model = modelo,
                                choices = listOf(
                                    ChunkChoice(delta = Delta(), finishReason = aMotivo(evento.stats.finishReason)),
                                ),
                            ),
                        ),
                    )
                    emit(StreamEvent.Fin)
                }
                is GenerationEvent.Failure -> emit(StreamEvent.Error(evento.message, "engine_error"))
            }
        }
    }

    private fun aRespuesta(modelo: String, texto: String, stats: GenerationStats) = ChatCompletionResponse(
        id = generarId(reloj()),
        created = reloj() / 1000,
        model = modelo,
        choices = listOf(
            Choice(
                message = WireMessage("assistant", texto),
                finishReason = aMotivo(stats.finishReason),
            ),
        ),
        usage = Usage(
            promptTokens = stats.promptTokens,
            completionTokens = stats.generatedTokens,
            totalTokens = stats.promptTokens + stats.generatedTokens,
        ),
    )

    private fun aMotivo(razon: FinishReason) =
        if (razon == FinishReason.LIMITE_TOKENS) "length" else "stop"

    private fun aParametros(peticion: ChatCompletionRequest): GenerationParams {
        val base = GenerationParams()
        return GenerationParams(
            temperature = peticion.temperature ?: base.temperature,
            minP = base.minP,
            maxTokens = peticion.maxTokens ?: base.maxTokens,
        )
    }

    private fun aMensajesDeDominio(mensajes: List<WireMessage>): List<ChatMessage> =
        mensajes.mapNotNull { wire ->
            aRol(wire.role)?.let { ChatMessage(it, wire.content) }
        }

    private fun aRol(rol: String): ChatMessage.Role? = when (rol.lowercase()) {
        "system", "developer" -> ChatMessage.Role.SYSTEM
        "user" -> ChatMessage.Role.USER
        "assistant" -> ChatMessage.Role.ASSISTANT
        else -> null
    }
}

sealed interface RespuestaParcial {
    data object Latido : RespuestaParcial
    data class Final(val resultado: CompletionResult) : RespuestaParcial
}

sealed interface StreamEvent {
    data class Chunk(val chunk: ChatCompletionChunk) : StreamEvent
    data class Error(val mensaje: String, val tipo: String) : StreamEvent
    data object Fin : StreamEvent
}

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

sealed interface CompletionResult {
    data class Ok(val respuesta: ChatCompletionResponse) : CompletionResult
    data class Fallo(val mensaje: String, val tipo: String) : CompletionResult
}

/** Motivos detectables ANTES de comprometer el status HTTP de la respuesta. */
enum class RechazoPrevio(val tipo: String) {
    SIN_MODELO("model_not_loaded"),
    PETICION_INVALIDA("invalid_request_error"),
}

class ChatCompletionsService(
    private val engine: InferenceEngine,
    private val reloj: () -> Long = { System.currentTimeMillis() },
    private val generarId: (Long) -> String = { "chatcmpl-$it" },
) {

    fun modeloActivo(): String? = (engine.state.value as? EngineState.Ready)?.model?.name

    /** Se comprueba antes de escribir cabeceras, para poder devolver 503/400 de verdad. */
    fun revisarAntesDeResponder(peticion: ChatCompletionRequest): RechazoPrevio? = when {
        modeloActivo() == null -> RechazoPrevio.SIN_MODELO
        aMensajesDeDominio(peticion.messages).isEmpty() -> RechazoPrevio.PETICION_INVALIDA
        else -> null
    }

    fun mensajeDeRechazo(rechazo: RechazoPrevio): String = when (rechazo) {
        RechazoPrevio.SIN_MODELO -> "No hay modelo cargado en Nodo"
        RechazoPrevio.PETICION_INVALIDA -> "El campo messages no trae ningún mensaje utilizable"
    }

    /**
     * Genera la respuesta completa. El keep-alive NO vive aquí: el hueco peligroso es el
     * prefill del prompt, donde el motor no emite nada, así que el latido lo pone el
     * servidor con un temporizador propio (ver NodoHttpServer.responderCompleto).
     */
    fun generar(peticion: ChatCompletionRequest): Flow<CompletionResult> = flow {
        val modelo = modeloActivo()
        if (modelo == null) {
            emit(CompletionResult.Fallo(mensajeDeRechazo(RechazoPrevio.SIN_MODELO), RechazoPrevio.SIN_MODELO.tipo))
            return@flow
        }
        val mensajes = aMensajesDeDominio(peticion.messages)
        if (mensajes.isEmpty()) {
            emit(
                CompletionResult.Fallo(
                    mensajeDeRechazo(RechazoPrevio.PETICION_INVALIDA),
                    RechazoPrevio.PETICION_INVALIDA.tipo,
                ),
            )
            return@flow
        }

        val texto = StringBuilder()
        var stats: GenerationStats? = null
        var fallo: String? = null

        engine.generate(mensajes, aParametros(peticion)).collect { evento ->
            when (evento) {
                is GenerationEvent.Token -> texto.append(evento.text)
                is GenerationEvent.Done -> stats = evento.stats
                is GenerationEvent.Failure -> fallo = evento.message
            }
        }

        val motivo = fallo
        val cierre = stats
        emit(
            when {
                motivo != null -> CompletionResult.Fallo(motivo, "engine_error")
                cierre == null -> CompletionResult.Fallo("El motor terminó sin estadísticas", "engine_error")
                else -> CompletionResult.Ok(aRespuesta(modelo, texto.toString(), cierre))
            },
        )
    }

    /** Stream SSE estándar: un chunk por token y `data: [DONE]` al final. */
    fun generarStream(peticion: ChatCompletionRequest): Flow<StreamEvent> = flow {
        val modelo = modeloActivo()
        if (modelo == null) {
            emit(StreamEvent.Error(mensajeDeRechazo(RechazoPrevio.SIN_MODELO), RechazoPrevio.SIN_MODELO.tipo))
            return@flow
        }
        val mensajes = aMensajesDeDominio(peticion.messages)
        if (mensajes.isEmpty()) {
            emit(
                StreamEvent.Error(
                    mensajeDeRechazo(RechazoPrevio.PETICION_INVALIDA),
                    RechazoPrevio.PETICION_INVALIDA.tipo,
                ),
            )
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
                message = WireMessage("assistant", TextoDelMensaje(texto)),
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
            val texto = wire.content.texto
            aRol(wire.role)?.takeIf { texto.isNotBlank() }?.let { ChatMessage(it, texto) }
        }

    private fun aRol(rol: String): ChatMessage.Role? = when (rol.lowercase()) {
        "system", "developer" -> ChatMessage.Role.SYSTEM
        "user" -> ChatMessage.Role.USER
        "assistant" -> ChatMessage.Role.ASSISTANT
        else -> null
    }
}

sealed interface StreamEvent {
    data class Chunk(val chunk: ChatCompletionChunk) : StreamEvent
    data class Error(val mensaje: String, val tipo: String) : StreamEvent
    data object Fin : StreamEvent
}

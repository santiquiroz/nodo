package com.santiquiroz.nodo.feature.chat

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.inference.ModelInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

class FakeInferenceEngine : InferenceEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Idle)
    var respuesta: List<String> = listOf("Hola", " mundo")
    var ultimosMensajes: List<ChatMessage> = emptyList()
    var delayEntreTokensMs: Long = 0
    var fallaTrasTokens: Int? = null
    var razonDeCorte: FinishReason = FinishReason.FIN_NATURAL
    var tokensEmitidos = 0

    override suspend fun load(modelPath: String, config: EngineConfig) {
        state.value = EngineState.Ready(ModelInfo(modelPath.substringAfterLast('/'), modelPath, 1000L))
    }

    override suspend fun unload() {
        state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        ultimosMensajes = messages
        tokensEmitidos = 0
        respuesta.forEachIndexed { indice, pieza ->
            if (delayEntreTokensMs > 0) delay(delayEntreTokensMs)
            if (fallaTrasTokens == indice) {
                emit(GenerationEvent.Failure("fallo simulado del motor"))
                return@flow
            }
            emit(GenerationEvent.Token(pieza))
            tokensEmitidos++
        }
        emit(GenerationEvent.Done(GenerationStats(10, respuesta.size, 50, 200, razonDeCorte)))
    }
}

package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.inference.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

class FakeInferenceEngine(modeloInicial: String? = "modelo-de-prueba.gguf") : InferenceEngine {
    override val state = MutableStateFlow<EngineState>(
        modeloInicial?.let { EngineState.Ready(ModelInfo(it, "/models/$it", 1000L)) } ?: EngineState.Idle,
    )
    var piezas: List<String> = listOf("Hola", " mundo")
    var falloSimulado: String? = null
    var razonDeCorte: FinishReason = FinishReason.FIN_NATURAL
    var ultimosParams: GenerationParams? = null
    var ultimosMensajes: List<ChatMessage> = emptyList()

    override suspend fun load(modelPath: String, config: EngineConfig) {
        state.value = EngineState.Ready(ModelInfo(modelPath.substringAfterLast('/'), modelPath, 1000L))
    }

    override suspend fun unload() {
        state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        ultimosMensajes = messages
        ultimosParams = params
        falloSimulado?.let {
            emit(GenerationEvent.Failure(it))
            return@flow
        }
        piezas.forEach { emit(GenerationEvent.Token(it)) }
        emit(GenerationEvent.Done(GenerationStats(7, piezas.size, 40, 150, razonDeCorte)))
    }
}

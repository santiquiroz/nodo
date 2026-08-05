package com.santiquiroz.nodo.feature.chat

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

class FakeInferenceEngine : InferenceEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Idle)
    var respuesta: List<String> = listOf("Hola", " mundo")
    var ultimosMensajes: List<ChatMessage> = emptyList()

    override suspend fun load(modelPath: String, config: EngineConfig) {
        state.value = EngineState.Ready(ModelInfo(modelPath.substringAfterLast('/'), modelPath, 1000L))
    }

    override suspend fun unload() {
        state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        ultimosMensajes = messages
        respuesta.forEach { emit(GenerationEvent.Token(it)) }
        emit(GenerationEvent.Done(GenerationStats(10, respuesta.size, 50, 200)))
    }
}

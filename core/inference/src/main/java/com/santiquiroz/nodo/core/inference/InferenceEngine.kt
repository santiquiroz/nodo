package com.santiquiroz.nodo.core.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {
    val state: StateFlow<EngineState>
    suspend fun load(modelPath: String, config: EngineConfig = EngineConfig())
    suspend fun unload()
    fun generate(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent>
}

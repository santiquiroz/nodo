package com.santiquiroz.nodo.core.inference

data class EngineConfig(
    val contextLength: Int = 4096,
    val threads: Int = 6,
)

data class GenerationParams(
    val temperature: Float = 0.8f,
    val minP: Float = 0.05f,
    val maxTokens: Int = 1024,
)

data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class ModelInfo(val name: String, val path: String, val sizeBytes: Long)

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val timeToFirstTokenMs: Long,
    val totalTimeMs: Long,
) {
    val tokensPerSecond: Double
        get() = if (totalTimeMs > timeToFirstTokenMs && generatedTokens > 1)
            (generatedTokens - 1) * 1000.0 / (totalTimeMs - timeToFirstTokenMs) else 0.0
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val modelName: String) : EngineState
    data class Ready(val model: ModelInfo) : EngineState
    data class Error(val message: String) : EngineState
}

sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Done(val stats: GenerationStats) : GenerationEvent
    data class Failure(val message: String) : GenerationEvent
}

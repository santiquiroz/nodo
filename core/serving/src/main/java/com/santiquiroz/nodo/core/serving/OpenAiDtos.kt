package com.santiquiroz.nodo.core.serving

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<WireMessage> = emptyList(),
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val stream: Boolean = false,
)

@Serializable
data class WireMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: WireMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

// Formato del stream SSE: choices[].delta en vez de choices[].message
@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class ModelsResponse(val `object`: String = "list", val data: List<ModelCard>)

@Serializable
data class ModelCard(
    val id: String,
    val `object`: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "nodo",
)

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class ErrorBody(val message: String, val type: String, val code: String? = null)

@Serializable
data class HealthResponse(
    val status: String,
    val model: String? = null,
    @SerialName("requests_served") val requestsServed: Long = 0,
)

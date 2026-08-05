package com.santiquiroz.nodo.core.serving

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * El campo `content` de OpenAI admite tres formas: string, null (turnos con tool_calls)
 * y array de content-parts. Aceptarlas todas evita rechazar payloads legítimos de los
 * SDK oficiales con un 400 opaco.
 */
@Serializable(with = TextoDelMensajeSerializer::class)
data class TextoDelMensaje(val texto: String)

object TextoDelMensajeSerializer : KSerializer<TextoDelMensaje> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TextoDelMensaje")

    override fun deserialize(decoder: Decoder): TextoDelMensaje {
        val json = decoder as? JsonDecoder ?: return TextoDelMensaje(decoder.decodeString())
        return TextoDelMensaje(
            when (val elemento = json.decodeJsonElement()) {
                is JsonNull -> ""
                is JsonPrimitive -> elemento.content
                is JsonArray -> elemento.filterIsInstance<JsonObject>()
                    .mapNotNull { it["text"]?.jsonPrimitive?.content }
                    .joinToString("")
                is JsonObject -> elemento["text"]?.jsonPrimitive?.content.orEmpty()
            },
        )
    }

    override fun serialize(encoder: Encoder, value: TextoDelMensaje) {
        val json = encoder as? JsonEncoder
        if (json == null) encoder.encodeString(value.texto)
        else json.encodeJsonElement(JsonPrimitive(value.texto))
    }
}

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<WireMessage> = emptyList(),
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val stream: Boolean = false,
    val tools: List<ToolDto> = emptyList(),
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
)

@Serializable
data class ToolDto(val type: String = "function", val function: FunctionDto)

@Serializable
data class FunctionDto(
    val name: String,
    val description: String = "",
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

/** `arguments` viaja como STRING de JSON, no como objeto: así lo define OpenAI. */
@Serializable
data class FunctionCallDto(val name: String, val arguments: String)

@Serializable
data class WireMessage(
    val role: String,
    val content: TextoDelMensaje = TextoDelMensaje(""),
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

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
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
)

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

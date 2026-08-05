package com.santiquiroz.nodo.core.inference

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaCppEngine @Inject constructor() : InferenceEngine {

    // llama_context NO es thread-safe: todas las llamadas JNI confinadas a un solo hilo
    private val llamaDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nodo-llama") }.asCoroutineDispatcher()
    private val singleFlight = Mutex()

    private var handle = 0L
    private var config = EngineConfig()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state = _state.asStateFlow()

    override suspend fun load(modelPath: String, config: EngineConfig) = withContext(llamaDispatcher) {
        val file = File(modelPath)
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
        _state.value = EngineState.Loading(file.name)
        this@LlamaCppEngine.config = config
        val params = GenerationParams()
        val h = LlamaNative.load(modelPath, config.contextLength, config.threads, params.temperature, params.minP)
        _state.value = if (h == 0L) {
            EngineState.Error("No se pudo cargar ${file.name}")
        } else {
            handle = h
            EngineState.Ready(ModelInfo(file.name, modelPath, file.length()))
        }
    }

    override suspend fun unload() = withContext(llamaDispatcher) {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
        _state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        singleFlight.withLock {
            if (handle == 0L) {
                emit(GenerationEvent.Failure("No hay modelo cargado"))
                return@withLock
            }
            val prompt = formatearPrompt(messages)
            val inicio = System.nanoTime()
            val promptTokens = LlamaNative.start(handle, prompt)
            if (promptTokens < 0) {
                emit(GenerationEvent.Failure("Fallo al procesar el prompt"))
                return@withLock
            }
            var generados = 0
            var primerTokenMs = 0L
            while (generados < params.maxTokens) {
                val pieza = LlamaNative.next(handle) ?: break
                generados++
                if (primerTokenMs == 0L) primerTokenMs = (System.nanoTime() - inicio) / 1_000_000
                if (pieza.isNotEmpty()) emit(GenerationEvent.Token(pieza))
            }
            val totalMs = (System.nanoTime() - inicio) / 1_000_000
            emit(GenerationEvent.Done(GenerationStats(promptTokens, generados, primerTokenMs, totalMs)))
        }
    }.flowOn(llamaDispatcher)

    private fun formatearPrompt(messages: List<ChatMessage>): String {
        val roles = messages.map { it.role.wire }.toTypedArray()
        val texts = messages.map { it.content }.toTypedArray()
        return LlamaNative.formatChat(handle, roles, texts) ?: fallbackChatMl(messages)
    }

    // Modelos sin tokenizer.chat_template: formato ChatML genérico
    private fun fallbackChatMl(messages: List<ChatMessage>): String = buildString {
        messages.forEach { append("<|im_start|>${it.role.wire}\n${it.content}<|im_end|>\n") }
        append("<|im_start|>assistant\n")
    }
}

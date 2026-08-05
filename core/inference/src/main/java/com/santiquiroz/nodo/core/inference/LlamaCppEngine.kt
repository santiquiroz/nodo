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

    // Serializa generate() contra load()/unload(): sin él, un free() se cuela entre dos next()
    private val singleFlight = Mutex()

    // Pide al bucle de generación que corte ya, para no esperar hasta maxTokens por el candado
    @Volatile
    private var cancelacionPedida = false

    private var handle = 0L

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state = _state.asStateFlow()

    override suspend fun load(modelPath: String, config: EngineConfig) {
        val file = File(modelPath)
        _state.value = EngineState.Loading(file.name)
        conSesionExclusiva {
            liberarSesion()
            val h = LlamaNative.load(modelPath, config.contextLength, config.threads)
            _state.value = if (h == 0L) {
                EngineState.Error("No se pudo cargar ${file.name}")
            } else {
                handle = h
                EngineState.Ready(ModelInfo(file.name, modelPath, file.length()))
            }
        }
    }

    override suspend fun unload() = conSesionExclusiva {
        liberarSesion()
        _state.value = EngineState.Idle
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<GenerationEvent> = flow {
        singleFlight.withLock {
            cancelacionPedida = false
            if (handle == 0L) {
                emit(GenerationEvent.Failure("No hay modelo cargado"))
                return@withLock
            }
            LlamaNative.setSampling(handle, params.temperature, params.minP)
            val prompt = formatearPrompt(messages)
            val inicio = System.nanoTime()
            val promptTokens = LlamaNative.start(handle, prompt)
            if (promptTokens < 0) {
                emit(GenerationEvent.Failure(mensajeDeError(LlamaNative.lastStatus(handle))))
                return@withLock
            }
            var generados = 0
            var primerTokenMs = 0L
            var cortadaPorCancelacion = false
            while (generados < params.maxTokens) {
                if (cancelacionPedida) {
                    cortadaPorCancelacion = true
                    break
                }
                val pieza = LlamaNative.next(handle) ?: break
                generados++
                if (primerTokenMs == 0L) primerTokenMs = (System.nanoTime() - inicio) / 1_000_000
                if (pieza.isNotEmpty()) emit(GenerationEvent.Token(pieza))
            }
            val totalMs = (System.nanoTime() - inicio) / 1_000_000
            val estado = LlamaNative.lastStatus(handle)
            when {
                cortadaPorCancelacion -> emit(GenerationEvent.Failure("Generación cancelada"))
                generados >= params.maxTokens ->
                    emit(GenerationEvent.Done(estadisticas(promptTokens, generados, primerTokenMs, totalMs, FinishReason.LIMITE_TOKENS)))
                estado == NodoStatus.FIN_NATURAL ->
                    emit(GenerationEvent.Done(estadisticas(promptTokens, generados, primerTokenMs, totalMs, FinishReason.FIN_NATURAL)))
                else -> emit(GenerationEvent.Failure(mensajeDeError(estado)))
            }
        }
    }.flowOn(llamaDispatcher)

    // load/unload piden el corte ANTES de esperar el candado: si no, quedan encolados hasta maxTokens
    private suspend fun conSesionExclusiva(bloque: () -> Unit) {
        cancelacionPedida = true
        singleFlight.withLock {
            withContext(llamaDispatcher) { bloque() }
        }
        cancelacionPedida = false
    }

    private fun liberarSesion() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }

    private fun estadisticas(
        promptTokens: Int,
        generados: Int,
        primerTokenMs: Long,
        totalMs: Long,
        razon: FinishReason,
    ) = GenerationStats(promptTokens, generados, primerTokenMs, totalMs, razon)

    private fun mensajeDeError(estado: Int): String = when (estado) {
        NodoStatus.ERROR_CONTEXTO -> "Contexto agotado: la conversación no cabe en la ventana del modelo"
        NodoStatus.ERROR_DECODE -> "Fallo de decodificación del modelo"
        NodoStatus.ERROR_PROMPT -> "No se pudo tokenizar el prompt"
        NodoStatus.ERROR_SESION -> "El modelo se descargó durante la generación"
        else -> "Fallo desconocido del motor (estado $estado)"
    }

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

package com.santiquiroz.nodo.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.settings.Preferencias
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val availableModels: List<ModelFile> = emptyList(),
    val engineState: EngineState = EngineState.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isGenerating: Boolean = false,
    val lastStats: GenerationStats? = null,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: InferenceEngine,
    private val modelFiles: ModelFilesRepository,
    private val preferencias: Preferencias,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private var genJob: Job? = null

    init {
        onRefreshModels()
        viewModelScope.launch {
            engine.state.collect { estado ->
                _uiState.update { it.copy(engineState = estado) }
            }
        }
    }

    fun onRefreshModels() {
        when (val resultado = modelFiles.listar()) {
            is ModelosLocales.Ok -> _uiState.update {
                it.copy(availableModels = resultado.modelos, error = null)
            }
            is ModelosLocales.NoDisponible -> _uiState.update {
                it.copy(availableModels = emptyList(), error = resultado.razon)
            }
        }
    }

    fun onInputChange(texto: String) {
        _uiState.update { it.copy(input = texto) }
    }

    fun onSelectModel(modelo: ModelFile) {
        genJob?.cancel()
        genJob = null
        _uiState.update {
            it.copy(messages = emptyList(), lastStats = null, error = null, isGenerating = false)
        }
        viewModelScope.launch {
            val ajustes = preferencias.actuales()
            engine.load(modelo.path, EngineConfig(contextLength = ajustes.contexto, threads = ajustes.hilos))
        }
    }

    fun onSend() {
        val texto = _uiState.value.input.trim()
        if (texto.isEmpty() || _uiState.value.isGenerating) return
        if (_uiState.value.engineState !is EngineState.Ready) return

        val historial = _uiState.value.messages + ChatMessage(ChatMessage.Role.USER, texto)
        // La burbuja del asistente se crea vacía y se va reemplazando: así los updates
        // parten del estado actual y no de una lista capturada que puede estar obsoleta.
        _uiState.update {
            it.copy(
                messages = historial + ChatMessage(ChatMessage.Role.ASSISTANT, ""),
                input = "",
                isGenerating = true,
                error = null,
            )
        }

        genJob = viewModelScope.launch {
            val acumulado = StringBuilder()
            engine.generate(historial).collect { evento ->
                when (evento) {
                    is GenerationEvent.Token -> {
                        acumulado.append(evento.text)
                        reemplazarRespuesta(acumulado.toString())
                    }
                    is GenerationEvent.Done -> _uiState.update {
                        it.copy(isGenerating = false, lastStats = evento.stats, error = avisoDeCorte(evento.stats))
                    }
                    is GenerationEvent.Failure -> _uiState.update {
                        it.copy(isGenerating = false, error = evento.message)
                    }
                }
            }
        }
    }

    private fun reemplazarRespuesta(texto: String) {
        _uiState.update { estado ->
            val mensajes = estado.messages
            if (mensajes.lastOrNull()?.role != ChatMessage.Role.ASSISTANT) return@update estado
            estado.copy(
                messages = mensajes.dropLast(1) + ChatMessage(ChatMessage.Role.ASSISTANT, texto),
            )
        }
    }

    private fun avisoDeCorte(stats: GenerationStats): String? =
        if (stats.finishReason == FinishReason.LIMITE_TOKENS)
            "Respuesta truncada: se alcanzó el límite de tokens" else null
}

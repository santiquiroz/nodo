package com.santiquiroz.nodo.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    init {
        onRefreshModels()
        viewModelScope.launch {
            engine.state.collect { estado ->
                _uiState.update { it.copy(engineState = estado) }
            }
        }
    }

    fun onRefreshModels() {
        _uiState.update { it.copy(availableModels = modelFiles.listar()) }
    }

    fun onInputChange(texto: String) {
        _uiState.update { it.copy(input = texto) }
    }

    fun onSelectModel(modelo: ModelFile) {
        viewModelScope.launch {
            _uiState.update { it.copy(messages = emptyList(), lastStats = null, error = null) }
            engine.load(modelo.path)
        }
    }

    fun onSend() {
        val texto = _uiState.value.input.trim()
        if (texto.isEmpty() || _uiState.value.isGenerating) return
        if (_uiState.value.engineState !is EngineState.Ready) return

        val historial = _uiState.value.messages + ChatMessage(ChatMessage.Role.USER, texto)
        _uiState.update { it.copy(messages = historial, input = "", isGenerating = true, error = null) }

        viewModelScope.launch {
            val acumulado = StringBuilder()
            engine.generate(historial).collect { evento ->
                when (evento) {
                    is GenerationEvent.Token -> {
                        acumulado.append(evento.text)
                        _uiState.update {
                            it.copy(messages = historial + ChatMessage(ChatMessage.Role.ASSISTANT, acumulado.toString()))
                        }
                    }
                    is GenerationEvent.Done -> _uiState.update {
                        it.copy(isGenerating = false, lastStats = evento.stats)
                    }
                    is GenerationEvent.Failure -> _uiState.update {
                        it.copy(isGenerating = false, error = evento.message)
                    }
                }
            }
        }
    }
}

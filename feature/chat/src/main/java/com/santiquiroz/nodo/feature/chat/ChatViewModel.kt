package com.santiquiroz.nodo.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.settings.Preferencias
import com.santiquiroz.nodo.core.tools.AgenteConHerramientas
import com.santiquiroz.nodo.core.tools.EventoDeAgente
import com.santiquiroz.nodo.core.tools.RegistroDeHerramientas
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
    /** Qué está haciendo el modelo ahora mismo, para no dejar la pantalla muda. */
    val actividad: String? = null,
    val herramientasActivas: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: InferenceEngine,
    private val modelFiles: ModelFilesRepository,
    private val preferencias: Preferencias,
    private val herramientas: RegistroDeHerramientas,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private val agente = AgenteConHerramientas(engine)
    private var genJob: Job? = null

    init {
        onRefreshModels()
        viewModelScope.launch {
            engine.state.collect { estado ->
                _uiState.update { it.copy(engineState = estado) }
            }
        }
        viewModelScope.launch {
            preferencias.ajustes.collect { ajustes ->
                _uiState.update { it.copy(herramientasActivas = ajustes.busquedaWebActiva) }
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
            it.copy(
                messages = emptyList(),
                lastStats = null,
                error = null,
                isGenerating = false,
                actividad = null,
            )
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
        _uiState.update {
            it.copy(
                messages = historial + ChatMessage(ChatMessage.Role.ASSISTANT, ""),
                input = "",
                isGenerating = true,
                error = null,
                actividad = null,
            )
        }

        genJob = viewModelScope.launch {
            val disponibles = herramientas.disponibles()
            val acumulado = StringBuilder()
            agente.conversar(historial, disponibles).collect { evento ->
                when (evento) {
                    is EventoDeAgente.Token -> {
                        acumulado.append(evento.texto)
                        _uiState.update { it.copy(actividad = null) }
                        reemplazarRespuesta(acumulado.toString())
                    }
                    is EventoDeAgente.UsandoHerramienta -> _uiState.update {
                        it.copy(actividad = descripcionDe(evento))
                    }
                    is EventoDeAgente.HerramientaLista -> _uiState.update {
                        it.copy(actividad = "Leyendo lo que encontró…")
                    }
                    is EventoDeAgente.Fin -> _uiState.update {
                        it.copy(
                            isGenerating = false,
                            actividad = null,
                            lastStats = evento.stats,
                            error = avisoDeCorte(evento.stats),
                        )
                    }
                    is EventoDeAgente.Fallo -> _uiState.update {
                        it.copy(isGenerating = false, actividad = null, error = evento.mensaje)
                    }
                }
            }
        }
    }

    private fun descripcionDe(evento: EventoDeAgente.UsandoHerramienta): String =
        if (evento.detalle.isBlank()) "Usando ${evento.nombre}…"
        else "Buscando: ${evento.detalle}"

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

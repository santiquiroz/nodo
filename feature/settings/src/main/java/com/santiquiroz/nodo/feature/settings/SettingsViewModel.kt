package com.santiquiroz.nodo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.settings.Ajustes
import com.santiquiroz.nodo.core.settings.BuscadorConfigurado
import com.santiquiroz.nodo.core.settings.Preferencias
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val ajustes: Ajustes = Ajustes(),
    val puertoEnEdicion: String = Ajustes.PUERTO_POR_DEFECTO.toString(),
    val errorDePuerto: String? = null,
    val tokenGuardado: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencias: Preferencias,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencias.ajustes.collect { ajustes ->
                _uiState.update {
                    it.copy(
                        ajustes = ajustes,
                        // No pisar lo que el usuario está tecleando ahora mismo
                        puertoEnEdicion = if (it.errorDePuerto == null) ajustes.puerto.toString() else it.puertoEnEdicion,
                    )
                }
            }
        }
    }

    fun onTokenChange(token: String) {
        _uiState.update { it.copy(ajustes = it.ajustes.copy(tokenHuggingFace = token), tokenGuardado = false) }
    }

    fun guardarToken() {
        viewModelScope.launch {
            preferencias.guardarToken(_uiState.value.ajustes.tokenHuggingFace)
            _uiState.update { it.copy(tokenGuardado = true) }
        }
    }

    fun borrarToken() {
        viewModelScope.launch {
            preferencias.guardarToken("")
            _uiState.update { it.copy(tokenGuardado = false) }
        }
    }

    fun onPuertoChange(texto: String) {
        val puerto = texto.toIntOrNull()
        val error = when {
            texto.isBlank() -> "Escribe un puerto"
            puerto == null -> "Solo números"
            puerto !in Ajustes.PUERTOS_VALIDOS -> "Debe estar entre 1024 y 65535"
            else -> null
        }
        _uiState.update { it.copy(puertoEnEdicion = texto, errorDePuerto = error) }
        if (error == null && puerto != null) {
            viewModelScope.launch { preferencias.guardarPuerto(puerto) }
        }
    }

    fun onContextoChange(contexto: Int) {
        viewModelScope.launch { preferencias.guardarContexto(contexto) }
    }

    fun onHilosChange(hilos: Int) {
        viewModelScope.launch { preferencias.guardarHilos(hilos) }
    }

    fun onBusquedaWebChange(activa: Boolean) {
        viewModelScope.launch { preferencias.guardarBusquedaWebActiva(activa) }
    }

    fun onBuscadorChange(buscador: BuscadorConfigurado) {
        viewModelScope.launch { preferencias.guardarBuscador(buscador) }
    }

    fun onSearxngUrlChange(url: String) {
        _uiState.update { it.copy(ajustes = it.ajustes.copy(searxngUrl = url)) }
        viewModelScope.launch { preferencias.guardarSearxngUrl(url) }
    }

    fun onBraveKeyChange(clave: String) {
        _uiState.update { it.copy(ajustes = it.ajustes.copy(braveApiKey = clave)) }
        viewModelScope.launch { preferencias.guardarBraveApiKey(clave) }
    }
}

package com.santiquiroz.nodo.feature.server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.serving.NodoHttpServer
import com.santiquiroz.nodo.core.serving.NodoServerService
import com.santiquiroz.nodo.core.serving.ServerStateHolder
import com.santiquiroz.nodo.core.settings.Preferencias
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class ServerUiState(
    val corriendo: Boolean = false,
    val puerto: Int = NodoHttpServer.PUERTO_DEFECTO,
    val exponerEnLan: Boolean = false,
    val modeloCargado: String? = null,
    val urlLocal: String = "",
    val urlLan: String? = null,
    val modelosDisponibles: List<File> = emptyList(),
    val modeloElegido: File? = null,
    val token: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ServerViewModel @Inject constructor(
    application: Application,
    private val engine: InferenceEngine,
    private val estadoServidor: ServerStateHolder,
    private val preferencias: Preferencias,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refrescarModelos()
        viewModelScope.launch {
            preferencias.ajustes.collect { ajustes ->
                // Mientras el servidor corre manda el puerto real con el que arrancó
                if (!_uiState.value.corriendo) {
                    _uiState.update {
                        it.copy(
                            puerto = ajustes.puerto,
                            exponerEnLan = ajustes.exponerEnLan,
                            urlLocal = "http://127.0.0.1:${ajustes.puerto}/v1",
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            estadoServidor.estado.collect { estado ->
                _uiState.update {
                    it.copy(
                        corriendo = estado.corriendo,
                        puerto = estado.puerto,
                        exponerEnLan = estado.expuestoEnLan,
                        token = estado.token,
                        error = estado.error,
                        urlLocal = "http://127.0.0.1:${estado.puerto}/v1",
                        urlLan = if (estado.expuestoEnLan) ipDeLan()?.let { ip -> "http://$ip:${estado.puerto}/v1" } else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            engine.state.collect { estado ->
                _uiState.update {
                    it.copy(modeloCargado = (estado as? EngineState.Ready)?.model?.name)
                }
            }
        }
    }

    fun refrescarModelos() {
        val dir = getApplication<Application>().getExternalFilesDir("models")
        val archivos = dir?.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().sortedBy { it.name }
        _uiState.update { it.copy(modelosDisponibles = archivos, modeloElegido = it.modeloElegido ?: archivos.firstOrNull()) }
    }

    fun elegirModelo(modelo: File) {
        _uiState.update { it.copy(modeloElegido = modelo) }
    }

    fun alternarLan(activo: Boolean) {
        _uiState.update { it.copy(exponerEnLan = activo) }
        viewModelScope.launch { preferencias.guardarExponerEnLan(activo) }
    }

    fun alternarServidor() {
        val estado = _uiState.value
        val contexto = getApplication<Application>()
        if (estado.corriendo) {
            NodoServerService.detener(contexto)
        } else {
            NodoServerService.iniciar(
                context = contexto,
                puerto = estado.puerto,
                enLan = estado.exponerEnLan,
                rutaModelo = estado.modeloElegido?.absolutePath,
            )
        }
    }

    // NetworkInterface en vez de WifiManager: no necesita permisos, no revienta si falta
    // ACCESS_WIFI_STATE, y funciona cuando el teléfono es el punto de acceso o hay tethering.
    private fun ipDeLan(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

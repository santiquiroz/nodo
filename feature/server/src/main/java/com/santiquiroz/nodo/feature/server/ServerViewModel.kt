package com.santiquiroz.nodo.feature.server

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.serving.NodoHttpServer
import com.santiquiroz.nodo.core.serving.NodoServerService
import com.santiquiroz.nodo.core.serving.ServerStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
)

@HiltViewModel
class ServerViewModel @Inject constructor(
    application: Application,
    private val engine: InferenceEngine,
    private val estadoServidor: ServerStateHolder,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refrescarModelos()
        viewModelScope.launch {
            estadoServidor.estado.collect { estado ->
                _uiState.update {
                    it.copy(
                        corriendo = estado.corriendo,
                        puerto = estado.puerto,
                        exponerEnLan = estado.expuestoEnLan,
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

    private fun ipDeLan(): String? {
        val wifi = getApplication<Application>().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }
}

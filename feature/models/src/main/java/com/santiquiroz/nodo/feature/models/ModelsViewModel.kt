package com.santiquiroz.nodo.feature.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.capability.Compatibilidad
import com.santiquiroz.nodo.core.capability.DeviceProfile
import com.santiquiroz.nodo.core.capability.DeviceProfileReader
import com.santiquiroz.nodo.core.capability.GgufMetadata
import com.santiquiroz.nodo.core.capability.ModelSpec
import com.santiquiroz.nodo.core.capability.Veredicto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val CONTEXTO_POR_DEFECTO = 4096

data class ModeloLocal(
    val archivo: File,
    val spec: ModelSpec?,
    val veredicto: Veredicto?,
    val plantillaDeChat: Boolean,
)

data class ModelsUiState(
    val cargando: Boolean = true,
    val dispositivo: DeviceProfile? = null,
    val modelos: List<ModeloLocal> = emptyList(),
    val carpeta: String = "",
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    application: Application,
    private val lectorDeDispositivo: DeviceProfileReader,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refrescar()
    }

    fun refrescar() {
        viewModelScope.launch {
            _uiState.update { it.copy(cargando = true) }
            val dispositivo = lectorDeDispositivo.leer()
            val carpeta = getApplication<Application>().getExternalFilesDir("models")
            val modelos = withContext(Dispatchers.IO) { analizar(carpeta, dispositivo) }
            _uiState.value = ModelsUiState(
                cargando = false,
                dispositivo = dispositivo,
                modelos = modelos,
                carpeta = carpeta?.absolutePath.orEmpty(),
            )
        }
    }

    private fun analizar(carpeta: File?, dispositivo: DeviceProfile): List<ModeloLocal> {
        val archivos = carpeta?.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.name }
        return archivos.map { archivo ->
            val metadata = GgufMetadata.leer(archivo)
            val spec = metadata?.let { GgufMetadata.aModelSpec(it, archivo.name, archivo.length()) }
            ModeloLocal(
                archivo = archivo,
                spec = spec,
                veredicto = spec?.let { Compatibilidad.evaluar(it, dispositivo, CONTEXTO_POR_DEFECTO) },
                plantillaDeChat = metadata?.let { GgufMetadata.plantillaDeChat(it) } != null,
            )
        }
    }
}

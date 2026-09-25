package com.santiquiroz.nodo.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santiquiroz.nodo.core.capability.Compatibilidad
import com.santiquiroz.nodo.core.capability.DeviceProfileReader
import com.santiquiroz.nodo.core.capability.Veredicto
import com.santiquiroz.nodo.core.models.ArchivoGguf
import com.santiquiroz.nodo.core.models.HuggingFaceClient
import com.santiquiroz.nodo.core.models.ModelDownloader
import com.santiquiroz.nodo.core.models.ProgresoDescarga
import com.santiquiroz.nodo.core.models.RepoDeModelos
import com.santiquiroz.nodo.core.settings.Preferencias
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArchivoConVeredicto(
    val archivo: ArchivoGguf,
    val veredicto: Veredicto? = null,
    val evaluando: Boolean = false,
    val yaDescargado: Boolean = false,
    val progreso: Float? = null,
    val error: String? = null,
)

data class ExploreUiState(
    val consulta: String = "",
    val buscando: Boolean = false,
    val repos: List<RepoDeModelos> = emptyList(),
    val repoAbierto: String? = null,
    val archivos: List<ArchivoConVeredicto> = emptyList(),
    val cargandoArchivos: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val client: HuggingFaceClient,
    private val downloader: ModelDownloader,
    private val lectorDeDispositivo: DeviceProfileReader,
    private val preferencias: Preferencias,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState = _uiState.asStateFlow()

    private val descargas = mutableMapOf<String, Job>()

    init {
        // Un veredicto calculado con otro contexto ya no dice la verdad: se vuelve a pedir
        viewModelScope.launch {
            preferencias.ajustes.map { it.contexto }.distinctUntilChanged().drop(1).collect { _ ->
                _uiState.update { it.copy(archivos = it.archivos.map(::sinVeredicto)) }
            }
        }
    }

    fun onConsultaChange(texto: String) {
        _uiState.update { it.copy(consulta = texto) }
    }

    fun buscar() {
        val consulta = _uiState.value.consulta.trim()
        if (consulta.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(buscando = true, error = null, repoAbierto = null, archivos = emptyList()) }
            client.buscar(consulta)
                .onSuccess { repos ->
                    _uiState.update {
                        it.copy(
                            buscando = false,
                            repos = repos,
                            error = if (repos.isEmpty()) "Sin resultados para \"$consulta\"" else null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(buscando = false, error = error.message) }
                }
        }
    }

    fun abrirRepo(repo: RepoDeModelos) {
        if (_uiState.value.repoAbierto == repo.id) {
            _uiState.update { it.copy(repoAbierto = null, archivos = emptyList()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(repoAbierto = repo.id, cargandoArchivos = true, archivos = emptyList()) }
            client.archivosDe(repo.id)
                .onSuccess { archivos ->
                    _uiState.update {
                        it.copy(
                            cargandoArchivos = false,
                            archivos = archivos.map { archivo ->
                                ArchivoConVeredicto(
                                    archivo = archivo,
                                    yaDescargado = downloader.yaDescargado(archivo),
                                )
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(cargandoArchivos = false, error = error.message) }
                }
        }
    }

    /** Baja solo la cabecera para poder decir si cabe ANTES de comprometer varios GB. */
    fun evaluarCompatibilidad(archivo: ArchivoGguf) {
        viewModelScope.launch {
            actualizarArchivo(archivo.ruta) { it.copy(evaluando = true, error = null) }
            val dispositivo = lectorDeDispositivo.leer()
            val contexto = preferencias.actuales().contexto
            client.especificacionDe(archivo)
                .onSuccess { spec ->
                    val veredicto = Compatibilidad.evaluar(spec, dispositivo, contexto)
                    actualizarArchivo(archivo.ruta) { it.copy(evaluando = false, veredicto = veredicto) }
                }
                .onFailure { error ->
                    actualizarArchivo(archivo.ruta) { it.copy(evaluando = false, error = error.message) }
                }
        }
    }

    fun descargar(archivo: ArchivoGguf) {
        if (descargas[archivo.ruta]?.isActive == true) return
        descargas[archivo.ruta] = viewModelScope.launch {
            actualizarArchivo(archivo.ruta) { it.copy(progreso = 0f, error = null) }
            downloader.descargar(archivo).collect { progreso ->
                when (progreso) {
                    is ProgresoDescarga.EnCurso ->
                        actualizarArchivo(archivo.ruta) { it.copy(progreso = progreso.fraccion) }
                    is ProgresoDescarga.Terminada ->
                        actualizarArchivo(archivo.ruta) { it.copy(progreso = null, yaDescargado = true) }
                    is ProgresoDescarga.Fallida ->
                        actualizarArchivo(archivo.ruta) { it.copy(progreso = null, error = progreso.motivo) }
                }
            }
        }
    }

    fun cancelarDescarga(archivo: ArchivoGguf) {
        descargas.remove(archivo.ruta)?.cancel()
        actualizarArchivo(archivo.ruta) { it.copy(progreso = null) }
    }

    private fun sinVeredicto(item: ArchivoConVeredicto) = item.copy(veredicto = null)

    private fun actualizarArchivo(ruta: String, cambio: (ArchivoConVeredicto) -> ArchivoConVeredicto) {
        _uiState.update { estado ->
            estado.copy(
                archivos = estado.archivos.map { if (it.archivo.ruta == ruta) cambio(it) else it },
            )
        }
    }
}

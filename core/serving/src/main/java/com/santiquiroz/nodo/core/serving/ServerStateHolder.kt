package com.santiquiroz.nodo.core.serving

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ServerState(
    val corriendo: Boolean = false,
    val puerto: Int = NodoHttpServer.PUERTO_DEFECTO,
    val expuestoEnLan: Boolean = false,
    val token: String? = null,
    val error: String? = null,
)

/** Puente entre el service (que vive fuera de la UI) y la pantalla de Servidor. */
@Singleton
class ServerStateHolder @Inject constructor() {
    private val _estado = MutableStateFlow(ServerState())
    val estado = _estado.asStateFlow()

    fun marcarIniciado(puerto: Int, enLan: Boolean, token: String?) {
        _estado.value = ServerState(corriendo = true, puerto = puerto, expuestoEnLan = enLan, token = token)
    }

    fun marcarDetenido() {
        _estado.value = _estado.value.copy(corriendo = false, token = null, error = null)
    }

    fun marcarFallo(razon: String) {
        _estado.value = _estado.value.copy(corriendo = false, token = null, error = razon)
    }
}

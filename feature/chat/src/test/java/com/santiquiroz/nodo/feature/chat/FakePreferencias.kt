package com.santiquiroz.nodo.feature.chat

import com.santiquiroz.nodo.core.settings.Ajustes
import com.santiquiroz.nodo.core.settings.Preferencias
import kotlinx.coroutines.flow.MutableStateFlow

class FakePreferencias(inicial: Ajustes = Ajustes()) : Preferencias {
    private val estado = MutableStateFlow(inicial)
    override val ajustes = estado

    override suspend fun actuales(): Ajustes = estado.value
    override suspend fun guardarToken(token: String) {
        estado.value = estado.value.copy(tokenHuggingFace = token)
    }

    override suspend fun guardarPuerto(puerto: Int) {
        estado.value = estado.value.copy(puerto = puerto)
    }

    override suspend fun guardarExponerEnLan(activo: Boolean) {
        estado.value = estado.value.copy(exponerEnLan = activo)
    }

    override suspend fun guardarContexto(contexto: Int) {
        estado.value = estado.value.copy(contexto = contexto)
    }

    override suspend fun guardarHilos(hilos: Int) {
        estado.value = estado.value.copy(hilos = hilos)
    }
}

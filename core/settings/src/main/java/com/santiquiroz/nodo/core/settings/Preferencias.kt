package com.santiquiroz.nodo.core.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de los ajustes. Existe para que los ViewModels no dependan de DataStore
 * y se puedan probar con un doble en memoria.
 */
interface Preferencias {
    val ajustes: Flow<Ajustes>
    suspend fun actuales(): Ajustes
    suspend fun guardarToken(token: String)
    suspend fun guardarPuerto(puerto: Int)
    suspend fun guardarExponerEnLan(activo: Boolean)
    suspend fun guardarContexto(contexto: Int)
    suspend fun guardarHilos(hilos: Int)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferenciasModule {
    @Binds
    abstract fun bindPreferencias(impl: NodoPreferences): Preferencias
}

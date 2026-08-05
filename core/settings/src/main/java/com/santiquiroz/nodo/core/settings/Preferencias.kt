package com.santiquiroz.nodo.core.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

/** De dónde saca Nodo los resultados cuando la búsqueda web está activa. */
enum class BuscadorConfigurado { SEARXNG, BRAVE }

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
    suspend fun guardarBusquedaWebActiva(activa: Boolean)
    suspend fun guardarBuscador(buscador: BuscadorConfigurado)
    suspend fun guardarSearxngUrl(url: String)
    suspend fun guardarBraveApiKey(clave: String)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferenciasModule {
    @Binds
    abstract fun bindPreferencias(impl: NodoPreferences): Preferencias
}

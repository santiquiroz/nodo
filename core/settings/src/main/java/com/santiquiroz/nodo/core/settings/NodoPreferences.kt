package com.santiquiroz.nodo.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nodo_ajustes")

data class Ajustes(
    val tokenHuggingFace: String = "",
    val puerto: Int = PUERTO_POR_DEFECTO,
    val exponerEnLan: Boolean = false,
    val contexto: Int = CONTEXTO_POR_DEFECTO,
    val hilos: Int = HILOS_POR_DEFECTO,
    val busquedaWebActiva: Boolean = false,
    val buscador: BuscadorConfigurado = BuscadorConfigurado.SERPER,
    val searxngUrl: String = "",
    val braveApiKey: String = "",
    val serperApiKey: String = "",
    val geminiApiKey: String = "",
) {
    companion object {
        const val PUERTO_POR_DEFECTO = 8080
        const val CONTEXTO_POR_DEFECTO = 4096
        const val HILOS_POR_DEFECTO = 6

        val PUERTOS_VALIDOS = 1024..65535
        val CONTEXTOS_OFRECIDOS = listOf(2048, 4096, 8192, 16384, 32768)
        val HILOS_VALIDOS = 1..16
    }
}

/**
 * Ajustes persistentes de Nodo.
 *
 * El token de Hugging Face se guarda sin cifrar en el almacenamiento privado de la app:
 * es un token de solo lectura y cifrarlo exigiría una clave que viviría en el mismo
 * dispositivo. Si se llegara a guardar algo con permisos de escritura, habría que revisarlo.
 */
@Singleton
class NodoPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : Preferencias {
    private val clavesToken = stringPreferencesKey("token_huggingface")
    private val clavePuerto = intPreferencesKey("puerto")
    private val claveLan = booleanPreferencesKey("exponer_en_lan")
    private val claveContexto = intPreferencesKey("contexto")
    private val claveHilos = intPreferencesKey("hilos")
    private val claveBusqueda = booleanPreferencesKey("busqueda_web_activa")
    private val claveBuscador = stringPreferencesKey("buscador")
    private val claveSearxng = stringPreferencesKey("searxng_url")
    private val claveBrave = stringPreferencesKey("brave_api_key")
    private val claveSerper = stringPreferencesKey("serper_api_key")
    private val claveGemini = stringPreferencesKey("gemini_api_key")

    override val ajustes: Flow<Ajustes> = context.dataStore.data
        .catch { error ->
            // Un archivo corrupto no debe impedir abrir la app: se cae a los valores por defecto
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            Ajustes(
                tokenHuggingFace = prefs[clavesToken].orEmpty(),
                puerto = prefs[clavePuerto]?.takeIf { it in Ajustes.PUERTOS_VALIDOS } ?: Ajustes.PUERTO_POR_DEFECTO,
                exponerEnLan = prefs[claveLan] ?: false,
                contexto = prefs[claveContexto]?.takeIf { it > 0 } ?: Ajustes.CONTEXTO_POR_DEFECTO,
                hilos = prefs[claveHilos]?.takeIf { it in Ajustes.HILOS_VALIDOS } ?: Ajustes.HILOS_POR_DEFECTO,
                busquedaWebActiva = prefs[claveBusqueda] ?: false,
                buscador = prefs[claveBuscador]?.let { nombre ->
                    BuscadorConfigurado.entries.firstOrNull { it.name == nombre }
                } ?: BuscadorConfigurado.SEARXNG,
                searxngUrl = prefs[claveSearxng].orEmpty(),
                braveApiKey = prefs[claveBrave].orEmpty(),
                serperApiKey = prefs[claveSerper].orEmpty(),
                geminiApiKey = prefs[claveGemini].orEmpty(),
            )
        }

    override suspend fun actuales(): Ajustes = ajustes.first()

    override suspend fun guardarToken(token: String) = editar { it[clavesToken] = token.trim() }

    override suspend fun guardarPuerto(puerto: Int) = editar {
        if (puerto in Ajustes.PUERTOS_VALIDOS) it[clavePuerto] = puerto
    }

    override suspend fun guardarExponerEnLan(activo: Boolean) = editar { it[claveLan] = activo }

    override suspend fun guardarContexto(contexto: Int) = editar {
        if (contexto > 0) it[claveContexto] = contexto
    }

    override suspend fun guardarHilos(hilos: Int) = editar {
        if (hilos in Ajustes.HILOS_VALIDOS) it[claveHilos] = hilos
    }

    override suspend fun guardarBusquedaWebActiva(activa: Boolean) = editar { it[claveBusqueda] = activa }

    override suspend fun guardarBuscador(buscador: BuscadorConfigurado) = editar {
        it[claveBuscador] = buscador.name
    }

    override suspend fun guardarSearxngUrl(url: String) = editar { it[claveSearxng] = url.trim() }

    override suspend fun guardarBraveApiKey(clave: String) = editar { it[claveBrave] = clave.trim() }

    override suspend fun guardarSerperApiKey(clave: String) = editar { it[claveSerper] = clave.trim() }

    override suspend fun guardarGeminiApiKey(clave: String) = editar { it[claveGemini] = clave.trim() }

    private suspend fun editar(cambio: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(cambio)
    }
}

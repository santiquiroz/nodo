package com.santiquiroz.nodo.feature.chat

import android.content.Context
import android.os.Environment
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

data class ModelFile(val name: String, val path: String, val sizeBytes: Long)

// Carpeta vacía y carpeta ilegible son cosas distintas: la UI debe poder decirlo
sealed interface ModelosLocales {
    data class Ok(val modelos: List<ModelFile>) : ModelosLocales
    data class NoDisponible(val razon: String) : ModelosLocales
}

interface ModelFilesRepository {
    fun listar(): ModelosLocales
}

private const val TAG = "NodoModelos"

class ModelFilesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelFilesRepository {

    override fun listar(): ModelosLocales {
        val estadoAlmacenamiento = Environment.getExternalStorageState()
        val dir = context.getExternalFilesDir("models")
        if (dir == null) {
            Log.w(TAG, "getExternalFilesDir devolvió null (estado=$estadoAlmacenamiento)")
            return ModelosLocales.NoDisponible("Almacenamiento no disponible ($estadoAlmacenamiento)")
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "no se pudo crear ${dir.absolutePath}")
            return ModelosLocales.NoDisponible("No se pudo crear la carpeta de modelos")
        }
        val archivos = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
        if (archivos == null) {
            Log.w(TAG, "listFiles devolvió null en ${dir.absolutePath}")
            return ModelosLocales.NoDisponible("No se pudo leer la carpeta de modelos")
        }
        return ModelosLocales.Ok(
            archivos.sortedBy { it.name }.map { ModelFile(it.name, it.absolutePath, it.length()) },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {
    @Binds
    abstract fun bindModelFilesRepository(impl: ModelFilesRepositoryImpl): ModelFilesRepository
}

package com.santiquiroz.nodo.feature.chat

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

data class ModelFile(val name: String, val path: String, val sizeBytes: Long)

interface ModelFilesRepository {
    fun listar(): List<ModelFile>
}

class ModelFilesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelFilesRepository {
    override fun listar(): List<ModelFile> {
        val dir = context.getExternalFilesDir("models") ?: return emptyList()
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.name }
            .map { ModelFile(it.name, it.absolutePath, it.length()) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {
    @Binds
    abstract fun bindModelFilesRepository(impl: ModelFilesRepositoryImpl): ModelFilesRepository
}

package com.santiquiroz.nodo.core.inference

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceModule {
    @Binds
    abstract fun bindInferenceEngine(impl: LlamaCppEngine): InferenceEngine
}

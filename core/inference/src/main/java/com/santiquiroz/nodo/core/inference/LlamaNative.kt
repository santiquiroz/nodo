package com.santiquiroz.nodo.core.inference

// Debe coincidir con el enum NodoStatus de nodo_llama.cpp
internal object NodoStatus {
    const val GENERANDO = 0
    const val FIN_NATURAL = 1
    const val ERROR_SESION = 2
    const val ERROR_DECODE = 3
    const val ERROR_CONTEXTO = 4
    const val ERROR_PROMPT = 5
}

internal object LlamaNative {
    init {
        System.loadLibrary("nodo_llama")
    }

    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    external fun free(handle: Long)
    external fun lastStatus(handle: Long): Int
    external fun contextLength(handle: Long): Int
    external fun setSampling(handle: Long, temp: Float, minP: Float)
    external fun formatChat(handle: Long, roles: Array<String>, texts: Array<String>): String?
    external fun start(handle: Long, prompt: String): Int
    external fun next(handle: Long): String?
}

package com.santiquiroz.nodo.core.inference

internal object LlamaNative {
    init {
        System.loadLibrary("nodo_llama")
    }

    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int, temp: Float, minP: Float): Long
    external fun free(handle: Long)
    external fun formatChat(handle: Long, roles: Array<String>, texts: Array<String>): String?
    external fun start(handle: Long, prompt: String): Int
    external fun next(handle: Long): String?
}

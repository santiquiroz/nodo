package com.santiquiroz.nodo.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepoDto(
    @SerialName("modelId") val modelId: String? = null,
    val id: String? = null,
    val downloads: Int = 0,
    val likes: Int = 0,
    val tags: List<String> = emptyList(),
) {
    val identificador: String? get() = modelId ?: id
}

@Serializable
data class ArchivoDto(
    val type: String = "file",
    val path: String = "",
    val size: Long = 0,
    val lfs: LfsDto? = null,
) {
    /** Los .gguf grandes van por LFS y ahí el tamaño real vive en el bloque lfs. */
    val tamanoReal: Long get() = lfs?.size ?: size
}

// En el bloque lfs, oid es el SHA-256 del contenido real (el oid de fuera es el del puntero git)
@Serializable
data class LfsDto(val oid: String? = null, val size: Long = 0)

/** Un repo de Hugging Face que publica GGUF. */
data class RepoDeModelos(
    val id: String,
    val descargas: Int,
    val meGusta: Int,
    val licencia: String?,
    val esGated: Boolean,
)

/** Un archivo .gguf concreto dentro de un repo. */
data class ArchivoGguf(
    val repoId: String,
    val ruta: String,
    val tamanoBytes: Long,
    val cuantizacion: String,
    val sha256: String? = null,
) {
    val nombreDeArchivo: String get() = ruta.substringAfterLast('/')
    val urlDeDescarga: String get() = "https://huggingface.co/$repoId/resolve/main/$ruta"
}

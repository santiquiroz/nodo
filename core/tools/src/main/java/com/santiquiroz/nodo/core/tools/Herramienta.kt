package com.santiquiroz.nodo.core.tools

import kotlinx.serialization.json.JsonObject

/** Una llamada a herramienta tal como la pidió el modelo. */
data class LlamadaDeHerramienta(
    val id: String,
    val nombre: String,
    val argumentosJson: String,
)

/** Lo que se le devuelve al modelo tras ejecutar. */
data class ResultadoDeHerramienta(
    val idDeLlamada: String,
    val nombre: String,
    val contenido: String,
)

/**
 * Definición de una herramienta en el formato que entienden los modelos: nombre,
 * descripción y JSON Schema de parámetros. Es el mismo shape que usa OpenAI, así que
 * sirve tanto para las herramientas propias de Nodo como para las que envía un cliente.
 */
data class DefinicionDeHerramienta(
    val nombre: String,
    val descripcion: String,
    val parametros: JsonObject,
)

/** Herramienta que Nodo sabe ejecutar por su cuenta. */
interface Herramienta {
    val definicion: DefinicionDeHerramienta

    /** Nunca lanza: un fallo se devuelve como texto para que el modelo pueda explicarlo. */
    suspend fun ejecutar(argumentosJson: String): String
}

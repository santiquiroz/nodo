package com.santiquiroz.nodo.core.tools

import com.santiquiroz.nodo.core.settings.BuscadorConfigurado
import com.santiquiroz.nodo.core.settings.Preferencias
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qué herramientas puede ejecutar Nodo por su cuenta ahora mismo.
 *
 * Está vacío salvo que el usuario habilite la búsqueda en Ajustes: ejecutar una
 * herramienta que sale a internet es lo único que contradice el "los datos nunca
 * salen del teléfono", así que nunca es el comportamiento por defecto.
 */
@Singleton
class RegistroDeHerramientas @Inject constructor(
    private val preferencias: Preferencias,
) {

    suspend fun disponibles(): List<Herramienta> {
        val ajustes = preferencias.actuales()
        if (!ajustes.busquedaWebActiva) return emptyList()
        val buscador = construirBuscador(ajustes.buscador, ajustes.searxngUrl, ajustes.braveApiKey)
            ?: return emptyList()
        return listOf(HerramientaDeBusqueda(buscador))
    }

    suspend fun buscar(nombre: String): Herramienta? = disponibles().firstOrNull { it.definicion.nombre == nombre }

    private fun construirBuscador(cual: BuscadorConfigurado, searxngUrl: String, braveKey: String): BuscadorWeb? =
        when (cual) {
            BuscadorConfigurado.SEARXNG -> searxngUrl.takeIf { it.isNotBlank() }?.let { BuscadorSearxng(it) }
            BuscadorConfigurado.BRAVE -> braveKey.takeIf { it.isNotBlank() }?.let { BuscadorBrave(it) }
        }
}

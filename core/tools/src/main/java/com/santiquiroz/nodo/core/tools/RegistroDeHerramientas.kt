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
        val buscador = construirBuscador(ajustes)
            ?: return emptyList()
        return listOf(HerramientaDeBusqueda(buscador))
    }

    suspend fun buscar(nombre: String): Herramienta? = disponibles().firstOrNull { it.definicion.nombre == nombre }

    private fun construirBuscador(ajustes: com.santiquiroz.nodo.core.settings.Ajustes): BuscadorWeb? =
        when (ajustes.buscador) {
            BuscadorConfigurado.SERPER -> ajustes.serperApiKey.sinoNulo()?.let { BuscadorSerper(it) }
            BuscadorConfigurado.GEMINI -> ajustes.geminiApiKey.sinoNulo()?.let { BuscadorGemini(it) }
            BuscadorConfigurado.SEARXNG -> ajustes.searxngUrl.sinoNulo()?.let { BuscadorSearxng(it) }
            BuscadorConfigurado.BRAVE -> ajustes.braveApiKey.sinoNulo()?.let { BuscadorBrave(it) }
        }

    private fun String.sinoNulo(): String? = takeIf { it.isNotBlank() }
}

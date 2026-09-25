package com.santiquiroz.nodo.core.serving

import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

internal class CorteDeParada(private val secuencias: List<String>) {
    data class Avance(val texto: String, val cortado: Boolean)

    var cortado = false
        private set

    private val texto = StringBuilder()
    private var entregadoHasta = 0
    private val largoMaximo = secuencias.maxOf { it.length }

    fun recibir(pieza: String): Avance {
        val desde = entregadoHasta
        texto.append(pieza)
        val parada = primeraParada(desde) ?: return entregarHasta(inicioRetenido(desde), cortado = false)
        return entregarHasta(parada, cortado = true)
    }

    fun restante(): String = entregarHasta(texto.length, cortado = false).texto

    private fun entregarHasta(fin: Int, cortado: Boolean): Avance {
        val avance = Avance(texto.substring(entregadoHasta, fin), cortado)
        entregadoHasta = fin
        this.cortado = cortado
        return avance
    }

    private fun primeraParada(desde: Int): Int? =
        secuencias.mapNotNull { secuencia -> texto.indexOf(secuencia, desde).takeIf { it >= 0 } }.minOrNull()

    // Un sufijo que aún podría completar una secuencia se retiene: el siguiente token lo decide
    private fun inicioRetenido(desde: Int): Int =
        (maxOf(desde, texto.length - largoMaximo + 1) until texto.length)
            .firstOrNull { inicio -> empiezaAlgunaSecuencia(texto.substring(inicio)) }
            ?: texto.length

    private fun empiezaAlgunaSecuencia(sufijo: String): Boolean =
        secuencias.any { it.length > sufijo.length && it.startsWith(sufijo) }
}

// Al cortar se deja de recolectar: así el motor no sigue generando tokens que nadie va a leer
internal fun Flow<GenerationEvent>.cortandoEn(parada: SecuenciasDeParada): Flow<GenerationEvent> {
    val secuencias = parada.secuencias.filter { it.isNotEmpty() }
    if (secuencias.isEmpty()) return this
    val origen = this
    return flow {
        val corte = CorteDeParada(secuencias)
        var piezas = 0
        emitAll(
            origen.transformWhile { evento ->
                if (evento is GenerationEvent.Token) piezas++
                traducir(evento, corte, piezas).forEach { emit(it) }
                !corte.cortado
            },
        )
    }
}

private fun traducir(evento: GenerationEvent, corte: CorteDeParada, piezas: Int): List<GenerationEvent> = when (evento) {
    is GenerationEvent.Token -> traducirToken(corte.recibir(evento.text), piezas)
    is GenerationEvent.Done -> listOfNotNull(tokenSiHayTexto(corte.restante()), evento)
    is GenerationEvent.Failure -> listOf(evento)
}

private fun traducirToken(avance: CorteDeParada.Avance, piezas: Int): List<GenerationEvent> =
    listOfNotNull(
        tokenSiHayTexto(avance.texto),
        if (avance.cortado) GenerationEvent.Done(estadisticasDeCorte(piezas)) else null,
    )

private fun tokenSiHayTexto(texto: String): GenerationEvent? =
    texto.takeIf { it.isNotEmpty() }?.let { GenerationEvent.Token(it) }

// El motor solo informa los tokens del prompt al terminar; cortado antes, no se conocen
private fun estadisticasDeCorte(piezas: Int) =
    GenerationStats(promptTokens = 0, generatedTokens = piezas, timeToFirstTokenMs = 0, totalTimeMs = 0)

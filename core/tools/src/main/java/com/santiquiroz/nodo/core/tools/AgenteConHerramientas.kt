package com.santiquiroz.nodo.core.tools

import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Lo que ve quien consume el agente: texto, avisos de herramienta y el cierre. */
sealed interface EventoDeAgente {
    data class Token(val texto: String) : EventoDeAgente

    /** Se emite ANTES de ejecutar: la espera de una búsqueda es larga y hay que explicarla. */
    data class UsandoHerramienta(val nombre: String, val detalle: String) : EventoDeAgente

    data class HerramientaLista(val nombre: String) : EventoDeAgente
    data class Fin(val stats: GenerationStats) : EventoDeAgente
    data class Fallo(val mensaje: String) : EventoDeAgente
}

/**
 * Conversación con herramientas: si el modelo pide una y la tenemos, se ejecuta y se le
 * devuelve el resultado para que siga solo. Lo usan tanto el chat de la app como el
 * servidor cuando el cliente no trae sus propias herramientas.
 */
class AgenteConHerramientas(
    private val engine: InferenceEngine,
    private val maximoDeVueltas: Int = MAXIMO_DE_VUELTAS,
) {

    fun conversar(
        mensajes: List<ChatMessage>,
        herramientas: List<Herramienta>,
        params: GenerationParams = GenerationParams(),
    ): Flow<EventoDeAgente> = flow {
        var historia = conBloqueDeHerramientas(mensajes, herramientas)
        var acumulado = GenerationStats(0, 0, 0, 0)

        repeat(maximoDeVueltas) { vuelta ->
            val turno = generarTurno(historia, params, emitirTokens = herramientas.isEmpty())
            turno.fallo?.let {
                emit(EventoDeAgente.Fallo(it))
                return@flow
            }
            acumulado = sumar(acumulado, turno.stats)

            val llamadas = if (herramientas.isEmpty()) emptyList()
            else ProtocoloDeHerramientas.extraerLlamadas(turno.texto)

            if (llamadas.isEmpty()) {
                // Con herramientas el texto no se emitió token a token: se manda entero al final
                if (herramientas.isNotEmpty()) {
                    emit(EventoDeAgente.Token(ProtocoloDeHerramientas.textoSinLlamadas(turno.texto)))
                }
                emit(EventoDeAgente.Fin(acumulado))
                return@flow
            }

            val resultados = llamadas.map { llamada ->
                emit(EventoDeAgente.UsandoHerramienta(llamada.nombre, resumirArgumentos(llamada.argumentosJson)))
                val herramienta = herramientas.firstOrNull { it.definicion.nombre == llamada.nombre }
                val salida = if (herramienta == null) {
                    "La herramienta \"${llamada.nombre}\" no existe."
                } else {
                    runCatching { herramienta.ejecutar(llamada.argumentosJson) }
                        .getOrElse { "La herramienta falló: ${it.message}" }
                }
                emit(EventoDeAgente.HerramientaLista(llamada.nombre))
                salida
            }

            historia = historia +
                ChatMessage(ChatMessage.Role.ASSISTANT, turno.texto) +
                resultados.map {
                    ChatMessage(ChatMessage.Role.USER, ProtocoloDeHerramientas.comoRespuestaDeHerramienta(it))
                }

            if (vuelta == maximoDeVueltas - 1) {
                emit(EventoDeAgente.Token("No pude terminar: el modelo siguió pidiendo herramientas."))
                emit(EventoDeAgente.Fin(acumulado))
            }
        }
    }

    private class Turno(val texto: String, val stats: GenerationStats, val fallo: String?)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<EventoDeAgente>.generarTurno(
        mensajes: List<ChatMessage>,
        params: GenerationParams,
        emitirTokens: Boolean,
    ): Turno {
        val texto = StringBuilder()
        var stats: GenerationStats? = null
        var fallo: String? = null
        engine.generate(mensajes, params).collect { evento ->
            when (evento) {
                is GenerationEvent.Token -> {
                    texto.append(evento.text)
                    if (emitirTokens) emit(EventoDeAgente.Token(evento.text))
                }
                is GenerationEvent.Done -> stats = evento.stats
                is GenerationEvent.Failure -> fallo = evento.message
            }
        }
        return Turno(
            texto = texto.toString(),
            stats = stats ?: GenerationStats(0, 0, 0, 0),
            fallo = fallo ?: if (stats == null) "El motor terminó sin estadísticas" else null,
        )
    }

    private fun conBloqueDeHerramientas(
        mensajes: List<ChatMessage>,
        herramientas: List<Herramienta>,
    ): List<ChatMessage> {
        if (herramientas.isEmpty()) return mensajes
        val bloque = ProtocoloDeHerramientas.bloqueDeSistema(herramientas.map { it.definicion })
        val primero = mensajes.firstOrNull()
        return if (primero?.role == ChatMessage.Role.SYSTEM) {
            listOf(primero.copy(content = primero.content + bloque)) + mensajes.drop(1)
        } else {
            listOf(ChatMessage(ChatMessage.Role.SYSTEM, "Eres un asistente útil.$bloque")) + mensajes
        }
    }

    /** Para la UI: "buscar_web(pico y placa Medellín)" lee mejor que un JSON crudo. */
    private fun resumirArgumentos(argumentosJson: String): String =
        Regex("\"[^\"]+\"\\s*:\\s*\"([^\"]{0,80})\"")
            .findAll(argumentosJson)
            .map { it.groupValues[1] }
            .firstOrNull()
            .orEmpty()

    private fun sumar(a: GenerationStats, b: GenerationStats) = GenerationStats(
        promptTokens = a.promptTokens + b.promptTokens,
        generatedTokens = a.generatedTokens + b.generatedTokens,
        timeToFirstTokenMs = if (a.timeToFirstTokenMs > 0) a.timeToFirstTokenMs else b.timeToFirstTokenMs,
        totalTimeMs = a.totalTimeMs + b.totalTimeMs,
        finishReason = b.finishReason,
    )

    companion object {
        const val MAXIMO_DE_VUELTAS = 4
    }
}

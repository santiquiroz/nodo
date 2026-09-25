package com.santiquiroz.nodo.core.serving

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val PUERTO_POR_DEFECTO = 8080

/**
 * Un modelo cargado = una generación a la vez. La liberación va atada al token de quien
 * tomó el turno: liberar "si está tomado" permitiría que un finally tardío soltara el
 * turno de otra petición.
 */
private class ControlDeAcceso {
    private val dueno = AtomicReference<Any?>(null)

    fun intentarTomar(token: Any): Boolean = dueno.compareAndSet(null, token)

    fun soltar(token: Any) {
        dueno.compareAndSet(token, null)
    }
}

class NodoHttpServer(
    private val service: ChatCompletionsService,
    private val puerto: Int = PUERTO_POR_DEFECTO,
    private val soloLocalhost: Boolean = true,
    private val token: String? = null,
    private val margenStatusMs: Long = MARGEN_STATUS_MS,
    private val intervaloLatidoMs: Long = INTERVALO_LATIDO_MS,
) {
    private var servidor: EmbeddedServer<*, *>? = null
    private val acceso = ControlDeAcceso()
    private val atendidas = AtomicLong(0)

    val peticionesAtendidas: Long get() = atendidas.get()
    val estaCorriendo: Boolean get() = servidor != null

    /** Lanza la excepción de bind: quien llama debe reflejar el fallo, no fingir que arrancó. */
    fun iniciar() {
        if (servidor != null) return
        val host = if (soloLocalhost) "127.0.0.1" else "0.0.0.0"
        val nuevo = embeddedServer(CIO, port = puerto, host = host) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            routing {
                get("/health") { call.respond(salud()) }
                get("/v1/models") { if (autorizado(call)) call.respond(catalogo()) }
                post("/v1/chat/completions") { atenderCompletions(call) }
                post("/chat/completions") { atenderCompletions(call) }   // base URLs sin /v1
            }
        }
        nuevo.start(wait = false)
        servidor = nuevo
    }

    fun detener() {
        servidor?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        servidor = null
    }

    private suspend fun autorizado(call: ApplicationCall): Boolean {
        val esperado = token ?: return true
        val recibido = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        if (recibido != null && sonIguales(recibido, esperado)) return true
        call.respond(HttpStatusCode.Unauthorized, errorDe("Token inválido o ausente", "invalid_api_key"))
        return false
    }

    // Comparación de digest: el tiempo no depende de cuántos caracteres coinciden
    private fun sonIguales(a: String, b: String): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(sha.digest(a.toByteArray()), sha.digest(b.toByteArray()))
    }

    private fun salud() = HealthResponse(
        status = if (service.modeloActivo() != null) "ok" else "sin_modelo",
        model = service.modeloActivo(),
        requestsServed = atendidas.get(),
    )

    private fun catalogo(): ModelsResponse {
        val activo = service.modeloActivo() ?: return ModelsResponse(data = emptyList())
        return ModelsResponse(data = listOf(ModelCard(id = activo, created = System.currentTimeMillis() / 1000)))
    }

    private suspend fun atenderCompletions(call: ApplicationCall) {
        if (!autorizado(call)) return
        val peticion = runCatching { call.receive<ChatCompletionRequest>() }
        val cuerpo = peticion.getOrNull()
        if (cuerpo == null) {
            val detalle = peticion.exceptionOrNull()?.message?.take(200) ?: "cuerpo ilegible"
            call.respond(HttpStatusCode.BadRequest, errorDe("Cuerpo JSON inválido: $detalle", "invalid_request_error"))
            return
        }
        // Antes de comprometer el status: así un 503/400 sale como tal y no como 200 con cuerpo de error
        service.revisarAntesDeResponder(cuerpo)?.let { rechazo ->
            call.respond(estadoDe(rechazo), errorDe(service.mensajeDeRechazo(rechazo), rechazo.tipo))
            return
        }
        val turno = Any()
        if (!acceso.intentarTomar(turno)) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                errorDe("Nodo ya está generando otra respuesta", "rate_limit_exceeded"),
            )
            return
        }
        try {
            atendidas.incrementAndGet()
            if (cuerpo.stream) responderStream(call, cuerpo) else responderCompleto(call, cuerpo)
        } finally {
            acceso.soltar(turno)
        }
    }

    private fun estadoDe(rechazo: RechazoPrevio) = when (rechazo) {
        RechazoPrevio.SIN_MODELO -> HttpStatusCode.ServiceUnavailable
        RechazoPrevio.PETICION_INVALIDA -> HttpStatusCode.BadRequest
    }

    /**
     * Espera un poco el resultado antes de comprometer el 200: un fallo temprano (prompt que
     * no cabe, error de decodificación) sale con status real. Si la generación se alarga,
     * abre el cuerpo y mantiene la conexión viva con espacios — que son JSON válido antes
     * del `{` — porque el cliente objetivo corta a los 20 s de silencio y no pide streaming.
     */
    private suspend fun responderCompleto(call: ApplicationCall, peticion: ChatCompletionRequest) = coroutineScope {
        val resultados = service.generar(peticion).produceIn(this)
        val temprano = withTimeoutOrNull(margenStatusMs) { resultados.receive() }

        if (temprano is CompletionResult.Fallo) {
            call.respond(HttpStatusCode.InternalServerError, errorDe(temprano.mensaje, temprano.tipo))
            return@coroutineScope
        }
        if (temprano is CompletionResult.Ok) {
            call.respond(temprano.respuesta)
            return@coroutineScope
        }

        call.respondTextWriter(ContentType.Application.Json, HttpStatusCode.OK) {
            val latidos = launch {
                while (true) {
                    write(" ")
                    flush()
                    delay(intervaloLatidoMs)
                }
            }
            val resultado = try {
                resultados.receive()
            } finally {
                latidos.cancel()
            }
            write(
                when (resultado) {
                    is CompletionResult.Ok -> JSON.encodeToString(ChatCompletionResponse.serializer(), resultado.respuesta)
                    is CompletionResult.Fallo -> JSON.encodeToString(ErrorResponse.serializer(), errorDe(resultado.mensaje, resultado.tipo))
                },
            )
            flush()
        }
    }

    private suspend fun responderStream(call: ApplicationCall, peticion: ChatCompletionRequest) {
        val eventos = service.generarStream(peticion)
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            eventos.collect { evento ->
                when (evento) {
                    is StreamEvent.Chunk -> {
                        write("data: ${JSON.encodeToString(ChatCompletionChunk.serializer(), evento.chunk)}\n\n")
                        flush()
                    }
                    is StreamEvent.Error -> {
                        write("data: ${JSON.encodeToString(ErrorResponse.serializer(), errorDe(evento.mensaje, evento.tipo))}\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                    }
                    StreamEvent.Fin -> {
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }
    }

    private fun errorDe(mensaje: String, tipo: String) = ErrorResponse(ErrorBody(mensaje, tipo))

    companion object {
        const val PUERTO_DEFECTO = PUERTO_POR_DEFECTO
        private const val INTERVALO_LATIDO_MS = 5_000L
        private const val MARGEN_STATUS_MS = 3_000L
        private val JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

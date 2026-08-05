package com.santiquiroz.nodo.core.serving

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

private const val PUERTO_POR_DEFECTO = 8080

// Un modelo cargado = una generación a la vez. Peticiones solapadas reciben 429 en vez de encolarse
// hasta que el cliente agote su timeout.
private class ControlDeAcceso {
    private val enUso = Mutex()
    fun intentarTomar(): Boolean = enUso.tryLock()
    fun soltar() {
        if (enUso.isLocked) enUso.unlock()
    }
}

class NodoHttpServer(
    private val service: ChatCompletionsService,
    private val puerto: Int = PUERTO_POR_DEFECTO,
    private val soloLocalhost: Boolean = true,
) {
    private var servidor: EmbeddedServer<*, *>? = null
    private val acceso = ControlDeAcceso()
    private val atendidas = AtomicLong(0)

    val peticionesAtendidas: Long get() = atendidas.get()
    val estaCorriendo: Boolean get() = servidor != null

    fun iniciar() {
        if (servidor != null) return
        val host = if (soloLocalhost) "127.0.0.1" else "0.0.0.0"
        servidor = embeddedServer(CIO, port = puerto, host = host) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            routing {
                get("/health") { call.respond(salud()) }
                get("/v1/models") { call.respond(catalogo()) }
                post("/v1/chat/completions") { atenderCompletions(call) }
                post("/chat/completions") { atenderCompletions(call) }   // base URLs sin /v1
            }
        }.also { it.start(wait = false) }
    }

    fun detener() {
        servidor?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        servidor = null
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

    private suspend fun atenderCompletions(call: io.ktor.server.application.ApplicationCall) {
        val peticion = runCatching { call.receive<ChatCompletionRequest>() }.getOrNull()
        if (peticion == null) {
            call.respond(HttpStatusCode.BadRequest, errorDe("Cuerpo JSON inválido", "invalid_request_error"))
            return
        }
        if (!acceso.intentarTomar()) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                errorDe("Nodo ya está generando otra respuesta", "rate_limit_exceeded"),
            )
            return
        }
        try {
            atendidas.incrementAndGet()
            if (peticion.stream) responderStream(call, peticion) else responderCompleto(call, peticion)
        } finally {
            acceso.soltar()
        }
    }

    private suspend fun responderCompleto(
        call: io.ktor.server.application.ApplicationCall,
        peticion: ChatCompletionRequest,
    ) {
        // respondTextWriter fuerza chunked: cada latido resetea el read timeout del cliente
        call.respondTextWriter(ContentType.Application.Json, HttpStatusCode.OK) {
            var ultimoLatido = System.currentTimeMillis()
            service.generarConLatido(peticion).collect { parcial ->
                when (parcial) {
                    is RespuestaParcial.Latido -> {
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimoLatido >= INTERVALO_LATIDO_MS) {
                            write(" ")
                            flush()
                            ultimoLatido = ahora
                        }
                    }
                    is RespuestaParcial.Final -> {
                        val cuerpo = when (val r = parcial.resultado) {
                            is CompletionResult.Ok -> JSON.encodeToString(ChatCompletionResponse.serializer(), r.respuesta)
                            is CompletionResult.Fallo ->
                                JSON.encodeToString(ErrorResponse.serializer(), errorDe(r.mensaje, r.tipo))
                        }
                        write(cuerpo)
                        flush()
                    }
                }
            }
        }
    }

    private suspend fun responderStream(
        call: io.ktor.server.application.ApplicationCall,
        peticion: ChatCompletionRequest,
    ) {
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            service.generarStream(peticion).collect { evento ->
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
        private val JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

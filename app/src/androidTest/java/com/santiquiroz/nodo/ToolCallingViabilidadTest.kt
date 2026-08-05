package com.santiquiroz.nodo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.LlamaCppEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Viabilidad de tool calling ANTES de invertir en enlazar common/chat.cpp:
 * inyecta la definición de herramienta en el mensaje de sistema con el formato
 * que la plantilla de Qwen2.5 genera, y mide si el modelo emite <tool_call>.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingViabilidadTest {

    private val definicionHerramienta = """
        # Tools

        You may call one or more functions to assist with the user query.

        You are provided with function signatures within <tools></tools> XML tags:
        <tools>
        {"type": "function", "function": {"name": "buscar_web", "description": "Busca información actual en internet", "parameters": {"type": "object", "properties": {"consulta": {"type": "string", "description": "Términos de búsqueda"}}, "required": ["consulta"]}}}
        </tools>

        For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
        <tool_call>
        {"name": <function-name>, "arguments": <args-json-object>}
        </tool_call>
    """.trimIndent()

    @Test
    fun cadaModelo3bEmiteLlamadaDeHerramienta() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelos = ctx.getExternalFilesDir("models")
            ?.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.name.contains("3b") }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("No hay modelos 3B en el dispositivo", modelos.isNotEmpty())

        val resultados = mutableListOf<Pair<String, Boolean>>()
        modelos.forEach { modelo ->
            val engine = LlamaCppEngine()
            engine.load(modelo.absolutePath, EngineConfig(contextLength = 4096, threads = 6))
            if (engine.state.value !is EngineState.Ready) {
                Log.e("NodoTools", "no cargó ${modelo.name}: ${engine.state.value}")
                return@forEach
            }
            val salida = StringBuilder()
            var tokPorSeg = 0.0
            engine.generate(
                listOf(
                    ChatMessage(ChatMessage.Role.SYSTEM, definicionHerramienta),
                    ChatMessage(ChatMessage.Role.USER, "¿Qué temperatura hace hoy en Medellín?"),
                ),
                GenerationParams(temperature = 0.3f, maxTokens = 200),
            ).collect { ev ->
                when (ev) {
                    is GenerationEvent.Token -> salida.append(ev.text)
                    is GenerationEvent.Done -> tokPorSeg = ev.stats.tokensPerSecond
                    is GenerationEvent.Failure -> Log.e("NodoTools", "fallo ${modelo.name}: ${ev.message}")
                }
            }
            val emitioLlamada = salida.contains("tool_call") && salida.contains("buscar_web")
            resultados.add(modelo.name to emitioLlamada)
            Log.i(
                "NodoTools",
                "modelo=${modelo.name} tokPorSeg=${"%.2f".format(tokPorSeg)} " +
                    "emitioToolCall=$emitioLlamada salida='${salida.toString().take(300)}'",
            )
            engine.unload()
        }
        Log.i("NodoTools", "resumen tool calling: $resultados")
        assertTrue("Ningún modelo 3B emitió una llamada de herramienta", resultados.any { it.second })
    }
}

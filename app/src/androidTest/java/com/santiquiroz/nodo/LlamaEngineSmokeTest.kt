package com.santiquiroz.nodo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.GenerationEvent
import com.santiquiroz.nodo.core.inference.GenerationParams
import com.santiquiroz.nodo.core.inference.GenerationStats
import com.santiquiroz.nodo.core.inference.LlamaCppEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaEngineSmokeTest {

    @Test
    fun generaConCadaModeloYMideTokS() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir("models")
        assertNotNull("No existe el dir de modelos", dir)
        val modelos = dir!!.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.length() }
        assertTrue("No hay GGUF en ${dir.absolutePath}", modelos.isNotEmpty())

        val engine = LlamaCppEngine()
        modelos.forEach { modelo ->
            engine.load(modelo.absolutePath)
            val estado = engine.state.value
            assertTrue("Carga falló para ${modelo.name}: $estado", estado is EngineState.Ready)

            val respuesta = StringBuilder()
            var stats: GenerationStats? = null
            engine.generate(
                listOf(ChatMessage(ChatMessage.Role.USER, "Responde en una frase: ¿qué es un teléfono?")),
                GenerationParams(maxTokens = 128),
            ).collect { ev ->
                when (ev) {
                    is GenerationEvent.Token -> respuesta.append(ev.text)
                    is GenerationEvent.Done -> stats = ev.stats
                    is GenerationEvent.Failure -> fail("Generación falló para ${modelo.name}: ${ev.message}")
                }
            }
            Log.i(
                "NodoBench",
                "modelo=${modelo.name} tamano=${modelo.length() / 1_000_000}MB " +
                    "promptTokens=${stats?.promptTokens} genTokens=${stats?.generatedTokens} " +
                    "primerTokenMs=${stats?.timeToFirstTokenMs} totalMs=${stats?.totalTimeMs} " +
                    "tokPorSeg=${"%.2f".format(stats?.tokensPerSecond ?: 0.0)} " +
                    "respuesta='${respuesta.toString().take(200)}'",
            )
            assertTrue("Respuesta vacía para ${modelo.name}", respuesta.isNotEmpty())
            assertNotNull("Sin stats para ${modelo.name}", stats)
        }
        engine.unload()
    }
}

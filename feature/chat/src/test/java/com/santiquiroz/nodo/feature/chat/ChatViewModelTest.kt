package com.santiquiroz.nodo.feature.chat

import app.cash.turbine.test
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState
import com.santiquiroz.nodo.core.inference.FinishReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var repo: ModelFilesRepository

    private val modeloA = ModelFile("tiny.gguf", "/models/tiny.gguf", 500L)
    private val modeloB = ModelFile("otro.gguf", "/models/otro.gguf", 900L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeInferenceEngine()
        repo = object : ModelFilesRepository {
            override fun listar() = ModelosLocales.Ok(listOf(modeloA))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun crearVm() = ChatViewModel(engine, repo)

    private fun vmConModeloCargado(): ChatViewModel {
        val vm = crearVm()
        vm.onSelectModel(modeloA)
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `al iniciar lista los modelos disponibles`() = runTest {
        val vm = crearVm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("tiny.gguf"), vm.uiState.value.availableModels.map { it.name })
    }

    @Test
    fun `seleccionar modelo lo carga en el motor`() = runTest {
        val vm = vmConModeloCargado()
        assertTrue(vm.uiState.value.engineState is EngineState.Ready)
    }

    @Test
    fun `enviar agrega mensaje del usuario y acumula tokens del asistente`() = runTest {
        val vm = vmConModeloCargado()
        vm.onInputChange("hola")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()

        val mensajes = vm.uiState.value.messages
        assertEquals(2, mensajes.size)
        assertEquals(ChatMessage.Role.USER, mensajes[0].role)
        assertEquals("hola", mensajes[0].content)
        assertEquals(ChatMessage.Role.ASSISTANT, mensajes[1].role)
        assertEquals("Hola mundo", mensajes[1].content)
        assertFalse(vm.uiState.value.isGenerating)
        assertNotNull(vm.uiState.value.lastStats)
        assertEquals("", vm.uiState.value.input)
    }

    @Test
    fun `el historial completo viaja al motor en cada turno`() = runTest {
        val vm = vmConModeloCargado()
        vm.onInputChange("primera")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onInputChange("segunda")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()

        // user(primera) + assistant + user(segunda)
        assertEquals(3, engine.ultimosMensajes.size)
    }

    @Test
    fun `no envia si esta generando o input vacio`() = runTest {
        val vm = vmConModeloCargado()
        vm.onSend()   // input vacío
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.messages.size)
    }

    @Test
    fun `isGenerating es true durante el stream`() = runTest {
        val vm = vmConModeloCargado()
        vm.uiState.test {
            vm.onInputChange("hola")
            vm.onSend()
            var vioGenerando = false
            while (true) {
                val estado = awaitItem()
                if (estado.isGenerating) vioGenerando = true
                if (!estado.isGenerating && estado.messages.size == 2) break
            }
            assertTrue(vioGenerando)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cambiar de modelo a mitad de la generacion cancela el stream y no resucita la conversacion`() = runTest {
        engine.delayEntreTokensMs = 100
        engine.respuesta = List(10) { "tok$it " }
        val vm = vmConModeloCargado()
        vm.onInputChange("hola")
        vm.onSend()
        dispatcher.scheduler.advanceTimeBy(250)   // ya llegaron un par de tokens

        vm.onSelectModel(modeloB)
        dispatcher.scheduler.advanceUntilIdle()   // el resto del stream NO debe reaparecer

        assertEquals(emptyList<ChatMessage>(), vm.uiState.value.messages)
        assertFalse(vm.uiState.value.isGenerating)
        assertNull(vm.uiState.value.lastStats)
    }

    @Test
    fun `un fallo del motor a mitad del stream deja error y conserva lo generado`() = runTest {
        engine.respuesta = listOf("parcial", " mas")
        engine.fallaTrasTokens = 1
        val vm = vmConModeloCargado()
        vm.onInputChange("hola")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("fallo simulado del motor", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isGenerating)
        assertEquals("parcial", vm.uiState.value.messages.last().content)
        assertNull(vm.uiState.value.lastStats)
    }

    @Test
    fun `corte por limite de tokens avisa al usuario`() = runTest {
        engine.razonDeCorte = FinishReason.LIMITE_TOKENS
        val vm = vmConModeloCargado()
        vm.onInputChange("hola")
        vm.onSend()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(FinishReason.LIMITE_TOKENS, vm.uiState.value.lastStats?.finishReason)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `carpeta de modelos ilegible se reporta como error y no como lista vacia`() = runTest {
        val repoRoto = object : ModelFilesRepository {
            override fun listar() = ModelosLocales.NoDisponible("No se pudo leer la carpeta de modelos")
        }
        val vm = ChatViewModel(engine, repoRoto)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<ModelFile>(), vm.uiState.value.availableModels)
        assertEquals("No se pudo leer la carpeta de modelos", vm.uiState.value.error)
    }
}

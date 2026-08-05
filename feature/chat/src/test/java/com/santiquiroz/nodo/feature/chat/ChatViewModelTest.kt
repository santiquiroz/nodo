package com.santiquiroz.nodo.feature.chat

import app.cash.turbine.test
import com.santiquiroz.nodo.core.inference.ChatMessage
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var repo: ModelFilesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeInferenceEngine()
        repo = object : ModelFilesRepository {
            override fun listar() = listOf(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun crearVm() = ChatViewModel(engine, repo)

    @Test
    fun `al iniciar lista los modelos disponibles`() = runTest {
        val vm = crearVm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("tiny.gguf"), vm.uiState.value.availableModels.map { it.name })
    }

    @Test
    fun `seleccionar modelo lo carga en el motor`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.engineState is com.santiquiroz.nodo.core.inference.EngineState.Ready)
    }

    @Test
    fun `enviar agrega mensaje del usuario y acumula tokens del asistente`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
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
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
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
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onSend()   // input vacío
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.messages.size)
    }

    @Test
    fun `isGenerating es true durante el stream`() = runTest {
        val vm = crearVm()
        vm.onSelectModel(ModelFile("tiny.gguf", "/models/tiny.gguf", 500L))
        dispatcher.scheduler.advanceUntilIdle()
        vm.uiState.test {
            vm.onInputChange("hola")
            vm.onSend()
            // Se ve al menos un estado intermedio con isGenerating=true antes del final
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
}

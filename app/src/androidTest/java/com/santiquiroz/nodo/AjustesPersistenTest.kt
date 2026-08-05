package com.santiquiroz.nodo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.santiquiroz.nodo.core.settings.Ajustes
import com.santiquiroz.nodo.core.settings.NodoPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Los ajustes tienen que sobrevivir a que se cierre la app, que es el punto de guardarlos. */
@RunWith(AndroidJUnit4::class)
class AjustesPersistenTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() = runBlocking<Unit> {
        val prefs = NodoPreferences(contexto)
        prefs.guardarToken("")
        prefs.guardarPuerto(Ajustes.PUERTO_POR_DEFECTO)
        prefs.guardarContexto(Ajustes.CONTEXTO_POR_DEFECTO)
        prefs.guardarHilos(Ajustes.HILOS_POR_DEFECTO)
    }

    @Test
    fun loGuardadoSeLeeDesdeOtraInstancia() = runBlocking<Unit> {
        NodoPreferences(contexto).apply {
            guardarToken("hf_token_de_prueba")
            guardarPuerto(9123)
            guardarContexto(8192)
            guardarHilos(4)
        }

        // Otra instancia simula el siguiente arranque de la app
        val leidos = NodoPreferences(contexto).actuales()
        Log.i("NodoAjustes", "leídos tras guardar: $leidos")
        assertEquals("hf_token_de_prueba", leidos.tokenHuggingFace)
        assertEquals(9123, leidos.puerto)
        assertEquals(8192, leidos.contexto)
        assertEquals(4, leidos.hilos)
    }

    @Test
    fun losValoresFueraDeRangoSeIgnoran() = runBlocking<Unit> {
        val prefs = NodoPreferences(contexto)
        prefs.guardarPuerto(80)        // privilegiado: no se puede bindear sin root
        prefs.guardarHilos(99)
        val leidos = prefs.actuales()
        Log.i("NodoAjustes", "tras valores inválidos: puerto=${leidos.puerto} hilos=${leidos.hilos}")
        assertEquals(Ajustes.PUERTO_POR_DEFECTO, leidos.puerto)
        assertEquals(Ajustes.HILOS_POR_DEFECTO, leidos.hilos)
    }
}

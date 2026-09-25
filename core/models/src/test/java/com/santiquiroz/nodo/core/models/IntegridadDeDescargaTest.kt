package com.santiquiroz.nodo.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection

class IntegridadDeDescargaTest {

    @get:Rule
    val carpeta = TemporaryFolder()

    // sha256("hola nodo\n"), calculado con sha256sum
    private val shaDeHolaNodo = "293aea122c097a5dc152e905843edafc51bf1296626ce06d4068612f4f70cb24"

    private fun archivoCon(contenido: String) = carpeta.newFile().apply { writeText(contenido) }

    @Test
    fun `el sha256 de un archivo coincide con el esperado`() {
        assertTrue(IntegridadDeDescarga.coincideConSha256(archivoCon("hola nodo\n"), shaDeHolaNodo))
    }

    @Test
    fun `la comparacion del sha256 ignora mayusculas`() {
        assertTrue(IntegridadDeDescarga.coincideConSha256(archivoCon("hola nodo\n"), shaDeHolaNodo.uppercase()))
    }

    @Test
    fun `un archivo con otro contenido no coincide con el sha256`() {
        assertFalse(IntegridadDeDescarga.coincideConSha256(archivoCon("hola nodo, pero cambiado\n"), shaDeHolaNodo))
    }

    @Test
    fun `el content-range que empieza en el offset pedido se acepta`() {
        assertTrue(IntegridadDeDescarga.rangoEmpiezaEn("bytes 100-199/200", 100))
    }

    @Test
    fun `el content-range que empieza en otro offset se rechaza`() {
        assertFalse(IntegridadDeDescarga.rangoEmpiezaEn("bytes 0-199/200", 100))
        assertFalse(IntegridadDeDescarga.rangoEmpiezaEn("bytes 150-199/200", 100))
    }

    @Test
    fun `un content-range ausente o mal formado se rechaza`() {
        assertFalse(IntegridadDeDescarga.rangoEmpiezaEn(null, 100))
        assertFalse(IntegridadDeDescarga.rangoEmpiezaEn("", 100))
        assertFalse(IntegridadDeDescarga.rangoEmpiezaEn("bytes */200", 100))
        assertFalse(IntegridadDeDescarga.rangoEmpiezaEn("items 100-199/200", 100))
    }

    @Test
    fun `el content-range admite total desconocido`() {
        assertTrue(IntegridadDeDescarga.rangoEmpiezaEn("bytes 100-199/*", 100))
    }

    @Test
    fun `un 200 escribe desde cero aunque se haya pedido un rango`() {
        assertEquals(0L, IntegridadDeDescarga.offsetDeEscritura(HttpURLConnection.HTTP_OK, null, 100))
    }

    @Test
    fun `un 206 con el rango pedido reanuda desde el offset`() {
        assertEquals(100L, IntegridadDeDescarga.offsetDeEscritura(HttpURLConnection.HTTP_PARTIAL, "bytes 100-199/200", 100))
    }

    @Test
    fun `un 206 con otro rango obliga a empezar de nuevo`() {
        assertNull(IntegridadDeDescarga.offsetDeEscritura(HttpURLConnection.HTTP_PARTIAL, "bytes 0-199/200", 100))
    }
}

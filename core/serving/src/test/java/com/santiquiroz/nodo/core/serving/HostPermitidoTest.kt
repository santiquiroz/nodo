package com.santiquiroz.nodo.core.serving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPermitidoTest {

    @Test
    fun `los nombres de loopback se aceptan con y sin puerto`() {
        listOf("127.0.0.1", "127.0.0.1:8080", "localhost", "localhost:8080", "LocalHost:1", "[::1]", "[::1]:8080")
            .forEach { assertTrue(it, HostPermitido.esValido(it, aceptarIpsPrivadas = false)) }
    }

    @Test
    fun `un dominio ajeno se rechaza en ambos modos`() {
        listOf("evil.example", "evil.example:8080", "localhost.evil.example", "127.0.0.1.nip.io:8080").forEach {
            assertFalse(it, HostPermitido.esValido(it, aceptarIpsPrivadas = false))
            assertFalse(it, HostPermitido.esValido(it, aceptarIpsPrivadas = true))
        }
    }

    @Test
    fun `sin cabecera Host o vacia se rechaza`() {
        assertFalse(HostPermitido.esValido(null, aceptarIpsPrivadas = true))
        assertFalse(HostPermitido.esValido("", aceptarIpsPrivadas = true))
        assertFalse(HostPermitido.esValido(":8080", aceptarIpsPrivadas = true))
        assertFalse(HostPermitido.esValido("[::1", aceptarIpsPrivadas = true))
    }

    @Test
    fun `una IP privada solo se acepta cuando se expone en LAN`() {
        assertFalse(HostPermitido.esValido("192.168.1.20:8080", aceptarIpsPrivadas = false))
        listOf("192.168.1.20:8080", "10.0.0.5", "172.16.0.1", "172.31.255.255", "169.254.3.4", "127.0.0.2")
            .forEach { assertTrue(it, HostPermitido.esValido(it, aceptarIpsPrivadas = true)) }
    }

    @Test
    fun `una IP publica o mal formada no cuenta como privada`() {
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "192.168.1.300", "192.168.1", "10.0.0.1.5")
            .forEach { assertFalse(it, HostPermitido.esValido(it, aceptarIpsPrivadas = true)) }
    }

    @Test
    fun `nombreSinPuerto separa el puerto de IPv4, nombres e IPv6`() {
        assertEquals("127.0.0.1", HostPermitido.nombreSinPuerto("127.0.0.1:8080"))
        assertEquals("localhost", HostPermitido.nombreSinPuerto(" LOCALHOST "))
        assertEquals("::1", HostPermitido.nombreSinPuerto("[::1]:8080"))
        assertNull(HostPermitido.nombreSinPuerto(""))
    }
}

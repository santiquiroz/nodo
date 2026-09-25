package com.santiquiroz.nodo.core.serving

// DNS rebinding: una página cuyo dominio re-resuelve a 127.0.0.1 llega con ese dominio en Host
internal object HostPermitido {
    private val NOMBRES_LOCALES = setOf("127.0.0.1", "localhost", "::1")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun esValido(cabecera: String?, aceptarIpsPrivadas: Boolean): Boolean {
        val nombre = cabecera?.let(::nombreSinPuerto) ?: return false
        if (nombre in NOMBRES_LOCALES) return true
        return aceptarIpsPrivadas && esIpv4Privada(nombre)
    }

    fun nombreSinPuerto(cabecera: String): String? {
        val limpia = cabecera.trim().lowercase()
        val nombre = if (limpia.startsWith("[")) ipv6EntreCorchetes(limpia) else limpia.substringBefore(":")
        return nombre.ifEmpty { null }
    }

    // Solo literales: resolver un nombre aquí sería justo lo que el atacante controla
    fun esIpv4Privada(nombre: String): Boolean {
        val octetos = octetosIpv4(nombre) ?: return false
        return esRangoPrivado(octetos[0], octetos[1])
    }

    private fun ipv6EntreCorchetes(cabecera: String): String =
        cabecera.substringAfter("[").substringBefore("]", missingDelimiterValue = "")

    private fun octetosIpv4(nombre: String): List<Int>? {
        if (!IPV4.matches(nombre)) return null
        val octetos = nombre.split(".").map { it.toInt() }
        return octetos.takeIf { lista -> lista.all { it in 0..255 } }
    }

    private fun esRangoPrivado(a: Int, b: Int): Boolean = when (a) {
        10, 127 -> true
        172 -> b in 16..31
        192 -> b == 168
        169 -> b == 254
        else -> false
    }
}

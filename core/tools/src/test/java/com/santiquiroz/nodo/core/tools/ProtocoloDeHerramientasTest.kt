package com.santiquiroz.nodo.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocoloDeHerramientasTest {

    private val esquemaBusqueda = Json.decodeFromString(
        JsonObject.serializer(),
        """{"type":"object","properties":{"consulta":{"type":"string"}},"required":["consulta"]}""",
    )

    private val buscar = DefinicionDeHerramienta(
        nombre = "buscar_web",
        descripcion = "Busca información actual en internet",
        parametros = esquemaBusqueda,
    )

    @Test
    fun `el bloque de sistema replica el formato de la plantilla de Qwen`() {
        val bloque = ProtocoloDeHerramientas.bloqueDeSistema(listOf(buscar))
        assertTrue(bloque.contains("# Tools"))
        assertTrue(bloque.contains("<tools>"))
        assertTrue(bloque.contains("</tools>"))
        assertTrue(bloque.contains("\"name\":\"buscar_web\""))
        assertTrue(bloque.contains("<tool_call>"))
    }

    @Test
    fun `sin herramientas no se ensucia el prompt`() {
        assertEquals("", ProtocoloDeHerramientas.bloqueDeSistema(emptyList()))
    }

    @Test
    fun `extrae una llamada entre etiquetas`() {
        val salida = """
            <tool_call>
            {"name": "buscar_web", "arguments": {"consulta": "temperatura Medellín"}}
            </tool_call>
        """.trimIndent()
        val llamadas = ProtocoloDeHerramientas.extraerLlamadas(salida)
        assertEquals(1, llamadas.size)
        assertEquals("buscar_web", llamadas[0].nombre)
        assertTrue(llamadas[0].argumentosJson.contains("Medellín"))
    }

    @Test
    fun `los argumentos salen como string de JSON, que es lo que exige OpenAI`() {
        val salida = """<tool_call>{"name":"x","arguments":{"a":1,"b":"dos"}}</tool_call>"""
        val llamada = ProtocoloDeHerramientas.extraerLlamadas(salida).single()
        val comoObjeto = Json.parseToJsonElement(llamada.argumentosJson)
        assertTrue("Debe ser JSON parseable: ${llamada.argumentosJson}", comoObjeto is JsonObject)
    }

    @Test
    fun `extrae varias llamadas en la misma respuesta`() {
        val salida = """
            <tool_call>{"name":"a","arguments":{}}</tool_call>
            <tool_call>{"name":"b","arguments":{"x":1}}</tool_call>
        """.trimIndent()
        val llamadas = ProtocoloDeHerramientas.extraerLlamadas(salida)
        assertEquals(listOf("a", "b"), llamadas.map { it.nombre })
        assertEquals(2, llamadas.map { it.id }.toSet().size)
    }

    @Test
    fun `tolera que el modelo pequeno olvide las etiquetas`() {
        val salida = """{"name": "buscar_web", "arguments": {"consulta": "pico y placa"}}"""
        val llamadas = ProtocoloDeHerramientas.extraerLlamadas(salida)
        assertEquals("buscar_web", llamadas.single().nombre)
    }

    @Test
    fun `acepta el formato de Llama 3, que usa parameters en vez de arguments`() {
        // La plantilla de Llama 3.2 emite el JSON pelado, sin etiquetas, con clave "parameters"
        val salida = """{"name": "buscar_web", "parameters": {"consulta": "clima Medellín"}}"""
        val llamada = ProtocoloDeHerramientas.extraerLlamadas(salida).single()
        assertEquals("buscar_web", llamada.nombre)
        assertTrue(llamada.argumentosJson.contains("Medellín"))
    }

    @Test
    fun `tolera el JSON envuelto en un bloque de codigo`() {
        val salida = """
            ```json
            {"name": "buscar_web", "arguments": {"consulta": "hola"}}
            ```
        """.trimIndent()
        assertEquals("buscar_web", ProtocoloDeHerramientas.extraerLlamadas(salida).single().nombre)
    }

    @Test
    fun `una respuesta normal no produce llamadas fantasma`() {
        val salida = "El código P0301 indica un fallo de encendido en el cilindro 1."
        assertTrue(ProtocoloDeHerramientas.extraerLlamadas(salida).isEmpty())
    }

    @Test
    fun `un JSON que no es una llamada se ignora`() {
        val salida = """{"resultado": 42, "unidad": "grados"}"""
        assertTrue(ProtocoloDeHerramientas.extraerLlamadas(salida).isEmpty())
    }

    @Test
    fun `las llaves dentro de cadenas no rompen el recorte`() {
        val salida = """{"name":"x","arguments":{"consulta":"busca {esto} y {aquello}"}}"""
        val llamada = ProtocoloDeHerramientas.extraerLlamadas(salida).single()
        assertTrue(llamada.argumentosJson.contains("{esto}"))
    }

    @Test
    fun `el texto para el usuario queda sin las llamadas`() {
        val salida = "Déjame buscar eso.\n<tool_call>{\"name\":\"x\",\"arguments\":{}}</tool_call>"
        assertEquals("Déjame buscar eso.", ProtocoloDeHerramientas.textoSinLlamadas(salida))
    }

    @Test
    fun `el JSON pelado de Llama 3 no se repite como texto para el usuario`() {
        val salida = """{"name":"buscar_web","parameters":{"consulta":"x"}}"""
        assertEquals("", ProtocoloDeHerramientas.textoSinLlamadas(salida))
    }

    @Test
    fun `el JSON pelado dentro de un bloque de codigo tambien se retira`() {
        val salida = """
            ```json
            {"name": "buscar_web", "arguments": {"consulta": "hola"}}
            ```
        """.trimIndent()
        assertEquals("", ProtocoloDeHerramientas.textoSinLlamadas(salida))
    }

    @Test
    fun `lo que el modelo escribe despues del JSON pelado se conserva`() {
        val salida = """{"name":"buscar_web","parameters":{"consulta":"x"}} Ya casi."""
        assertEquals("Ya casi.", ProtocoloDeHerramientas.textoSinLlamadas(salida))
    }

    @Test
    fun `un JSON que no es una llamada se conserva intacto para el usuario`() {
        val salida = """{"resultado": 42, "unidad": "grados"}"""
        assertEquals(salida, ProtocoloDeHerramientas.textoSinLlamadas(salida))
    }

    @Test
    fun `un bloque de codigo que no es una llamada conserva sus vallas`() {
        val salida = "```json\n{\"resultado\": 42}\n```"
        assertEquals(salida, ProtocoloDeHerramientas.textoSinLlamadas(salida))
    }

    @Test
    fun `el resultado de la herramienta se envuelve como espera la plantilla`() {
        val envuelto = ProtocoloDeHerramientas.comoRespuestaDeHerramienta("28 grados")
        assertEquals("<tool_response>\n28 grados\n</tool_response>", envuelto)
    }

    @Test
    fun `la definicion se serializa con la forma de OpenAI`() {
        val texto = Json.encodeToString(JsonObject.serializer(), ProtocoloDeHerramientas.aJsonDeOpenAi(buscar))
        assertTrue(texto.contains("\"type\":\"function\""))
        assertTrue(texto.contains("\"name\":\"buscar_web\""))
        assertTrue(texto.contains("\"parameters\""))
    }
}

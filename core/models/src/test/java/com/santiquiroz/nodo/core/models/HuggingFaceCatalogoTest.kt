package com.santiquiroz.nodo.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceCatalogoTest {

    // Recorte de la respuesta real de /api/models?filter=gguf&search=qwen2.5-3b
    private val busquedaReal = """
        [
          {"_id":"66e98aed","id":"Qwen/Qwen2.5-3B-Instruct-GGUF","likes":156,"private":false,
           "downloads":188869,"tags":["gguf","chat","text-generation","en","license:other","region:us"],
           "modelId":"Qwen/Qwen2.5-3B-Instruct-GGUF"},
          {"_id":"672f5997","id":"bartowski/Qwen2.5-3B-Instruct-GGUF","likes":12,"private":false,
           "downloads":50631,"tags":["gguf","license:apache-2.0"],"modelId":"bartowski/Qwen2.5-3B-Instruct-GGUF"}
        ]
    """.trimIndent()

    // Recorte de /api/models/{repo}/tree/main?recursive=true&expand=true
    private val arbolReal = """
        [
          {"type":"file","oid":"c6c4","size":3274,"path":".gitattributes"},
          {"type":"file","oid":"9cca","size":135,"lfs":{"oid":"0f3a9c1e5b7d2468ace013579bdf2468ace013579bdf2468ace013579bdf2468","size":1140516000,"pointerSize":135},
           "path":"Qwen2.5-3B-Instruct-IQ2_M.gguf"},
          {"type":"file","oid":"cc0b","size":135,"lfs":{"oid":"7e2b4d6f8a0c1e3f5a7b9c0d2e4f6a8b0c1d3e5f7a9b0c2d4e6f8a0b1c3d5e7f","size":1929903264,"pointerSize":135},
           "path":"Qwen2.5-3B-Instruct-Q4_K_M.gguf"},
          {"type":"file","oid":"aa11","size":1234,"path":"README.md"},
          {"type":"directory","oid":"bb22","size":0,"path":"subcarpeta"}
        ]
    """.trimIndent()

    @Test
    fun `extrae los repos de la respuesta de busqueda`() {
        val repos = HuggingFaceCatalogo.repos(busquedaReal)
        assertEquals(2, repos.size)
        assertEquals("Qwen/Qwen2.5-3B-Instruct-GGUF", repos[0].id)
        assertEquals(188869, repos[0].descargas)
    }

    @Test
    fun `saca la licencia de las etiquetas`() {
        val repos = HuggingFaceCatalogo.repos(busquedaReal)
        assertEquals("other", repos[0].licencia)
        assertEquals("apache-2.0", repos[1].licencia)
    }

    @Test
    fun `una respuesta corrupta devuelve lista vacia en vez de lanzar`() {
        assertTrue(HuggingFaceCatalogo.repos("{esto no es json").isEmpty())
        assertTrue(HuggingFaceCatalogo.archivosGguf("<html>error</html>", "x/y").isEmpty())
    }

    @Test
    fun `del arbol solo salen los gguf, no los readme ni las carpetas`() {
        val archivos = HuggingFaceCatalogo.archivosGguf(arbolReal, "bartowski/Qwen2.5-3B-Instruct-GGUF")
        assertEquals(2, archivos.size)
        assertTrue(archivos.all { it.ruta.endsWith(".gguf") })
    }

    @Test
    fun `el tamano real sale del bloque lfs, no del puntero de 135 bytes`() {
        val archivos = HuggingFaceCatalogo.archivosGguf(arbolReal, "repo/x")
        val q4 = archivos.first { it.ruta.contains("Q4_K_M") }
        assertEquals(1_929_903_264L, q4.tamanoBytes)
    }

    @Test
    fun `el sha256 sale del oid del bloque lfs`() {
        val archivos = HuggingFaceCatalogo.archivosGguf(arbolReal, "repo/x")
        val q4 = archivos.first { it.ruta.contains("Q4_K_M") }
        assertEquals("7e2b4d6f8a0c1e3f5a7b9c0d2e4f6a8b0c1d3e5f7a9b0c2d4e6f8a0b1c3d5e7f", q4.sha256)
    }

    @Test
    fun `un gguf sin bloque lfs no tiene sha256 que verificar`() {
        val sinLfs = """[{"type":"file","oid":"c6c4","size":2048,"path":"diminuto-Q4_0.gguf"}]"""
        val archivos = HuggingFaceCatalogo.archivosGguf(sinLfs, "repo/x")
        assertNull(archivos.single().sha256)
    }

    @Test
    fun `los archivos se ordenan de menor a mayor tamano`() {
        val archivos = HuggingFaceCatalogo.archivosGguf(arbolReal, "repo/x")
        assertEquals(listOf(1_140_516_000L, 1_929_903_264L), archivos.map { it.tamanoBytes })
    }

    @Test
    fun `deduce la cuantizacion del nombre del archivo`() {
        assertEquals("Q4_K_M", HuggingFaceCatalogo.cuantizacionDelNombre("Qwen2.5-3B-Instruct-Q4_K_M.gguf"))
        assertEquals("IQ2_M", HuggingFaceCatalogo.cuantizacionDelNombre("Qwen2.5-3B-Instruct-IQ2_M.gguf"))
        assertEquals("Q8_0", HuggingFaceCatalogo.cuantizacionDelNombre("modelo.Q8_0.gguf"))
        assertEquals("Q6_K", HuggingFaceCatalogo.cuantizacionDelNombre("carpeta/modelo_Q6_K.gguf"))
    }

    @Test
    fun `Q4_K_M no se confunde con Q4_K ni con Q4`() {
        assertEquals("Q4_K_M", HuggingFaceCatalogo.cuantizacionDelNombre("x-Q4_K_M.gguf"))
        assertEquals("Q4_K_S", HuggingFaceCatalogo.cuantizacionDelNombre("x-Q4_K_S.gguf"))
        assertEquals("Q4_0", HuggingFaceCatalogo.cuantizacionDelNombre("x-Q4_0.gguf"))
    }

    @Test
    fun `reconoce las variantes que publica bartowski en la practica`() {
        // Salieron como DESCONOCIDA al correr contra el repo real de Qwen2.5-1.5B
        val casos = mapOf(
            "Qwen2.5-1.5B-Instruct-Q2_K_L.gguf" to "Q2_K_L",
            "Qwen2.5-1.5B-Instruct-Q3_K_XL.gguf" to "Q3_K_XL",
            "Qwen2.5-1.5B-Instruct-Q4_K_L.gguf" to "Q4_K_L",
            "Qwen2.5-1.5B-Instruct-Q5_K_L.gguf" to "Q5_K_L",
            "Qwen2.5-1.5B-Instruct-Q6_K_L.gguf" to "Q6_K_L",
            "Qwen2.5-1.5B-Instruct-Q4_0_4_4.gguf" to "Q4_0_4_4",
            "Qwen2.5-1.5B-Instruct-Q4_0_8_8.gguf" to "Q4_0_8_8",
            "Qwen2.5-1.5B-Instruct-f16.gguf" to "F16",
        )
        casos.forEach { (archivo, esperado) ->
            assertEquals(archivo, esperado, HuggingFaceCatalogo.cuantizacionDelNombre(archivo))
        }
    }

    @Test
    fun `un nombre sin cuantizacion reconocible no revienta`() {
        assertEquals("DESCONOCIDA", HuggingFaceCatalogo.cuantizacionDelNombre("modelo-raro.gguf"))
    }

    @Test
    fun `los modelos partidos en varios archivos se descartan`() {
        val partido = """
            [{"type":"file","size":135,"lfs":{"size":5000000000},"path":"grande-00001-of-00003.gguf"},
             {"type":"file","size":135,"lfs":{"size":2000000000},"path":"normal-Q4_K_M.gguf"}]
        """.trimIndent()
        val archivos = HuggingFaceCatalogo.archivosGguf(partido, "repo/x")
        assertEquals(1, archivos.size)
        assertEquals("normal-Q4_K_M.gguf", archivos[0].nombreDeArchivo)
    }

    @Test
    fun `la url de descarga apunta al endpoint resolve`() {
        val archivos = HuggingFaceCatalogo.archivosGguf(arbolReal, "bartowski/Qwen2.5-3B-Instruct-GGUF")
        assertEquals(
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            archivos.first { it.cuantizacion == "Q4_K_M" }.urlDeDescarga,
        )
    }
}

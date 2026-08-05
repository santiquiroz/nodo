package com.santiquiroz.nodo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Fondo = Color(0xFF0B0E14)
private val Superficie = Color(0xFF131822)
private val SuperficieAlta = Color(0xFF1B2230)
private val Primario = Color(0xFF57D9A3)      // verde terminal
private val Secundario = Color(0xFF7AA2F7)    // azul técnico
private val TextoPrincipal = Color(0xFFE6E9EF) // contraste >4.5:1 sobre Fondo
private val TextoSecundario = Color(0xFF8B93A7)
private val ErrorRojo = Color(0xFFF7768E)

private val EsquemaOscuro = darkColorScheme(
    primary = Primario,
    onPrimary = Fondo,
    secondary = Secundario,
    onSecondary = Fondo,
    background = Fondo,
    onBackground = TextoPrincipal,
    surface = Superficie,
    onSurface = TextoPrincipal,
    surfaceVariant = SuperficieAlta,
    onSurfaceVariant = TextoSecundario,
    error = ErrorRojo,
    onError = Fondo,
)

val TipografiaMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun NodoTheme(content: @Composable () -> Unit) {
    // v1 es dark-only: estética de herramienta de desarrollador
    MaterialTheme(
        colorScheme = EsquemaOscuro,
        typography = Typography(),
        content = content,
    )
}

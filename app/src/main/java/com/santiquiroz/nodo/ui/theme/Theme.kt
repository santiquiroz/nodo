package com.santiquiroz.nodo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dirección: instrumento de medición, no terminal de hacker.
 *
 * El fondo es grafito azulado en vez de negro puro, como la carcasa de un aparato.
 * El ámbar es el color de marca — el de los indicadores de instrumentos analógicos —
 * y el verde queda reservado exclusivamente para "esto está vivo": estado, no decoración.
 */
private val Fondo = Color(0xFF0D1117)
private val Superficie = Color(0xFF161B22)
private val SuperficieAlta = Color(0xFF1F262F)
private val Borde = Color(0xFF2A323D)

private val Ambar = Color(0xFFE8A33D)
private val AmbarClaro = Color(0xFFF0B95C)

/** Solo para estado: servidor corriendo, modelo cargado, "corre bien". */
val VerdeSenal = Color(0xFF3FB950)

/** Solo para el escalón intermedio del semáforo. Frío a propósito, para no leerse como el ámbar. */
val AmarilloAviso = Color(0xFFD6A22B)

private val Rojo = Color(0xFFF25C54)
private val TextoPrincipal = Color(0xFFE6EDF3)
private val TextoTenue = Color(0xFF8B949E)

private val EsquemaOscuro = darkColorScheme(
    primary = Ambar,
    onPrimary = Fondo,
    primaryContainer = SuperficieAlta,
    onPrimaryContainer = AmbarClaro,
    secondary = VerdeSenal,
    onSecondary = Fondo,
    tertiary = AmbarClaro,
    background = Fondo,
    onBackground = TextoPrincipal,
    surface = Superficie,
    onSurface = TextoPrincipal,
    surfaceVariant = SuperficieAlta,
    onSurfaceVariant = TextoTenue,
    surfaceContainerHighest = SuperficieAlta,
    outline = Borde,
    outlineVariant = Borde,
    error = Rojo,
    onError = Fondo,
)

/** Cifras y URLs siempre en monoespaciada y con cifras de ancho fijo: no bailan al actualizarse. */
val EstiloDato = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
)

private val TipografiaNodo = Typography().run {
    copy(
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun NodoTheme(content: @Composable () -> Unit) {
    // v1 es solo oscuro: es una consola, no una app de consumo
    MaterialTheme(
        colorScheme = EsquemaOscuro,
        typography = TipografiaNodo,
        content = content,
    )
}

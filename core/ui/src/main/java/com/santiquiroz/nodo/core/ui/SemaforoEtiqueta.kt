package com.santiquiroz.nodo.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.santiquiroz.nodo.core.capability.Semaforo

/** Verde solo significa "esto corre": es estado, nunca decoración. */
val VerdeCorreBien = Color(0xFF3FB950)

/** Amarillo frío, para que no se confunda con el ámbar de la marca. */
val AmarilloJusto = Color(0xFFD6A22B)

/**
 * Semáforo de compatibilidad: icono, título y motivo. Nunca solo color — a igual
 * distancia de un daltónico y de alguien mirando el teléfono al sol.
 */
@Composable
fun SemaforoEtiqueta(estado: Semaforo, razon: String, compacto: Boolean = false) {
    val (icono, color, titulo) = when (estado) {
        Semaforo.CORRE_BIEN -> Triple(Icons.Outlined.CheckCircle, VerdeCorreBien, "Corre bien")
        Semaforo.JUSTO -> Triple(Icons.Outlined.WarningAmber, AmarilloJusto, "Justo")
        Semaforo.NO_CABE -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "No cabe")
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(if (compacto) 6.dp else 8.dp),
    ) {
        Icon(
            icono,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(if (compacto) 18.dp else 20.dp),
        )
        Column {
            Text(
                titulo,
                style = if (compacto) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = color,
            )
            Text(
                razon,
                style = if (compacto) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

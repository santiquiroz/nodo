package com.santiquiroz.nodo.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santiquiroz.nodo.core.capability.DeviceProfile
import com.santiquiroz.nodo.core.capability.Semaforo

@Composable
fun ModelsScreen(viewModel: ModelsViewModel = hiltViewModel()) {
    val estado by viewModel.uiState.collectAsStateWithLifecycle()

    if (estado.cargando) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { estado.dispositivo?.let { TarjetaDispositivo(it) } }

        if (estado.modelos.isEmpty()) {
            item { CarpetaVacia(estado.carpeta) }
        } else {
            items(estado.modelos) { modelo -> TarjetaModelo(modelo) }
        }
    }
}

@Composable
private fun TarjetaDispositivo(dispositivo: DeviceProfile) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Tu dispositivo", style = MaterialTheme.typography.labelMedium)
            Text(
                "%.1f GB libres de %.1f GB".format(dispositivo.ramDisponibleGb, dispositivo.ramTotalGb),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                listOfNotNull(dispositivo.socModelo, "${dispositivo.nucleos} núcleos").joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CarpetaVacia(carpeta: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Todavía no hay modelos", style = MaterialTheme.typography.titleSmall)
        Text(
            "Copia un archivo .gguf a esta carpeta:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            carpeta,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun TarjetaModelo(modelo: ModeloLocal) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(modelo.archivo.name, style = MaterialTheme.typography.titleSmall)

            val spec = modelo.spec
            if (spec == null) {
                Text(
                    "No se pudo leer la metadata de este archivo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Text(
                fichaTecnica(modelo),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            modelo.veredicto?.let { veredicto -> Semaforo(veredicto.semaforo, veredicto.razon) }
        }
    }
}

private fun fichaTecnica(modelo: ModeloLocal): String {
    val spec = modelo.spec ?: return ""
    return listOfNotNull(
        spec.parametrosMilesDeMillones?.let { "%.1fB parámetros".format(it) },
        spec.cuantizacion,
        "${modelo.archivo.length() / 1_000_000} MB",
        "contexto ${spec.contextoEntrenado / 1024}K",
        if (modelo.plantillaDeChat) "plantilla propia" else "sin plantilla",
    ).joinToString(" · ")
}

/** Icono + texto, nunca solo color: el semáforo tiene que leerse sin distinguir tonos. */
@Composable
private fun Semaforo(estado: Semaforo, razon: String) {
    val (icono, color, titulo) = when (estado) {
        Semaforo.CORRE_BIEN -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary, "Corre bien")
        Semaforo.JUSTO -> Triple(Icons.Outlined.WarningAmber, Color(0xFFE0AF68), "Justo")
        Semaforo.NO_CABE -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "No cabe")
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Column {
            Text(titulo, style = MaterialTheme.typography.labelLarge, color = color)
            Text(
                razon,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

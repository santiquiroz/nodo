package com.santiquiroz.nodo.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@Composable
fun ServerScreen(viewModel: ServerViewModel = hiltViewModel()) {
    val estado by viewModel.uiState.collectAsStateWithLifecycle()
    val portapapeles = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TarjetaEstado(estado)

        estado.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (estado.corriendo) {
            UrlCopiable("URL local", estado.urlLocal) { portapapeles.setText(AnnotatedString(it)) }
            estado.urlLan?.let { UrlCopiable("URL en la red WiFi", it) { url -> portapapeles.setText(AnnotatedString(url)) } }
            estado.token?.let { token ->
                UrlCopiable("Token (obligatorio fuera de este teléfono)", token) {
                    portapapeles.setText(AnnotatedString(it))
                }
                Text(
                    "Mándalo como cabecera Authorization: Bearer <token>. Sin él, cualquiera en la " +
                        "misma red WiFi podría usar tu modelo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SelectorDeModelo(estado.modelosDisponibles, estado.modeloElegido, estado.corriendo, viewModel::elegirModelo)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Exponer en la red WiFi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Otras máquinas de tu red podrán usar el modelo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = estado.exponerEnLan,
                onCheckedChange = viewModel::alternarLan,
                enabled = !estado.corriendo,
            )
        }

        Button(onClick = viewModel::alternarServidor, modifier = Modifier.fillMaxWidth()) {
            Text(if (estado.corriendo) "Detener servidor" else "Iniciar servidor")
        }

        Text(
            "Apunta cualquier cliente compatible con OpenAI a la URL local. " +
                "En RevScope: Ajustes → IA → proveedor \"Compatible OpenAI\" → pega la URL.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TarjetaEstado(estado: ServerUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (estado.corriendo) "Servidor activo" else "Servidor detenido",
                style = MaterialTheme.typography.titleMedium,
                color = if (estado.corriendo) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                estado.modeloCargado?.let { "Modelo caliente: $it" } ?: "Sin modelo cargado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UrlCopiable(etiqueta: String, url: String, onCopiar: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(etiqueta, style = MaterialTheme.typography.labelSmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Button(onClick = { onCopiar(url) }) { Text("Copiar") }
            }
        }
    }
}

@Composable
private fun SelectorDeModelo(
    modelos: List<File>,
    elegido: File?,
    bloqueado: Boolean,
    onElegir: (File) -> Unit,
) {
    if (modelos.isEmpty()) {
        Text(
            "No hay modelos en la carpeta de Nodo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Modelo a servir", style = MaterialTheme.typography.labelMedium)
        modelos.forEach { modelo ->
            FilterChip(
                selected = modelo == elegido,
                onClick = { onElegir(modelo) },
                enabled = !bloqueado,
                label = { Text("${modelo.name} · ${modelo.length() / 1_000_000} MB") },
            )
        }
    }
}

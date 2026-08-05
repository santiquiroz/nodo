package com.santiquiroz.nodo.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santiquiroz.nodo.core.inference.ChatMessage
import com.santiquiroz.nodo.core.inference.EngineState

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val estado by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(estado.messages.size, (estado.messages.lastOrNull()?.content?.length ?: 0)) {
        if (estado.messages.isNotEmpty()) listState.animateScrollToItem(estado.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SelectorModelo(estado, viewModel::onSelectModel, viewModel::onRefreshModels)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(estado.messages) { mensaje -> BurbujaMensaje(mensaje) }
        }

        estado.lastStats?.let { stats ->
            Text(
                text = "%.1f tok/s · %d tokens · primer token %d ms".format(
                    stats.tokensPerSecond, stats.generatedTokens, stats.timeToFirstTokenMs,
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        estado.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = estado.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribe un mensaje…") },
                enabled = estado.engineState is EngineState.Ready,
            )
            if (estado.isGenerating) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).widthIn(max = 28.dp))
            } else {
                IconButton(onClick = viewModel::onSend, enabled = estado.engineState is EngineState.Ready) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorModelo(
    estado: ChatUiState,
    onSeleccionar: (ModelFile) -> Unit,
    onRefrescar: () -> Unit,
) {
    var expandido by remember { mutableStateOf(false) }
    val etiqueta = when (val e = estado.engineState) {
        is EngineState.Ready -> e.model.name
        is EngineState.Loading -> "Cargando ${e.modelName}…"
        is EngineState.Error -> "Error: ${e.message}"
        EngineState.Idle -> if (estado.availableModels.isEmpty())
            "Sin modelos en Android/data/com.santiquiroz.nodo/files/models" else "Elige un modelo"
    }
    ExposedDropdownMenuBox(
        expanded = expandido && !estado.isGenerating,
        onExpandedChange = { abierto ->
            if (estado.isGenerating) return@ExposedDropdownMenuBox
            expandido = abierto
            if (abierto) onRefrescar()
        },
    ) {
        OutlinedTextField(
            value = etiqueta,
            onValueChange = {},
            readOnly = true,
            enabled = !estado.isGenerating,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandido) },
            label = { Text("Modelo") },
        )
        ExposedDropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
            estado.availableModels.forEach { modelo ->
                DropdownMenuItem(
                    text = { Text("${modelo.name} · ${modelo.sizeBytes / 1_000_000} MB") },
                    onClick = {
                        expandido = false
                        onSeleccionar(modelo)
                    },
                )
            }
        }
    }
}

@Composable
private fun BurbujaMensaje(mensaje: ChatMessage) {
    val esUsuario = mensaje.role == ChatMessage.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (esUsuario) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (esUsuario) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                mensaje.content,
                Modifier.padding(10.dp).widthIn(max = 300.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

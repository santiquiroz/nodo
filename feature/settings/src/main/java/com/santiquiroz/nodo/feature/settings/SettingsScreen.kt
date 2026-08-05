package com.santiquiroz.nodo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santiquiroz.nodo.core.settings.Ajustes

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val estado by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SeccionToken(estado, viewModel)
        HorizontalDivider()
        SeccionServidor(estado, viewModel)
        HorizontalDivider()
        SeccionMotor(estado.ajustes, viewModel)
        HorizontalDivider()
        AcercaDe()
    }
}

@Composable
private fun SeccionToken(estado: SettingsUiState, viewModel: SettingsViewModel) {
    var visible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Titulo("Hugging Face")
        Explicacion(
            "Solo hace falta para modelos restringidos (Llama, Gemma), que exigen aceptar sus " +
                "términos en la web. Un token de lectura basta.",
        )
        OutlinedTextField(
            value = estado.ajustes.tokenHuggingFace,
            onValueChange = viewModel::onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Token de acceso") },
            placeholder = { Text("hf_…") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::guardarToken) { Text("Guardar") }
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Ocultar" else "Ver") }
            if (estado.ajustes.tokenHuggingFace.isNotBlank()) {
                TextButton(onClick = viewModel::borrarToken) { Text("Borrar") }
            }
        }
        if (estado.tokenGuardado) {
            Text(
                "Token guardado",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SeccionServidor(estado: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Titulo("Servidor")
        OutlinedTextField(
            value = estado.puertoEnEdicion,
            onValueChange = viewModel::onPuertoChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Puerto") },
            singleLine = true,
            isError = estado.errorDePuerto != null,
            supportingText = {
                Text(estado.errorDePuerto ?: "Se aplica la próxima vez que inicies el servidor")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun SeccionMotor(ajustes: Ajustes, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Titulo("Motor")
        Explicacion(
            "Más contexto permite conversaciones más largas, pero ocupa más memoria y hace " +
                "más lento el primer token.",
        )
        Text("Contexto", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ajustes.CONTEXTOS_OFRECIDOS.forEach { contexto ->
                FilterChip(
                    selected = ajustes.contexto == contexto,
                    onClick = { viewModel.onContextoChange(contexto) },
                    label = { Text(if (contexto >= 1024) "${contexto / 1024}K" else "$contexto") },
                )
            }
        }

        Text("Hilos de CPU", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2, 4, 6, 8).forEach { hilos ->
                FilterChip(
                    selected = ajustes.hilos == hilos,
                    onClick = { viewModel.onHilosChange(hilos) },
                    label = { Text("$hilos") },
                )
            }
        }
        Explicacion("Más hilos no siempre es más rápido: los núcleos de eficiencia frenan al resto.")
    }
}

@Composable
private fun AcercaDe() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Titulo("Acerca de")
        Explicacion(
            "Nodo carga un modelo una sola vez y lo comparte con tus apps por un endpoint " +
                "compatible con OpenAI. Los datos nunca salen del teléfono.",
        )
        AssistChip(onClick = {}, label = { Text("github.com/santiquiroz/nodo") })
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Explicacion(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

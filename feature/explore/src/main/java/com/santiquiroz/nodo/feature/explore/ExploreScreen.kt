package com.santiquiroz.nodo.feature.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.santiquiroz.nodo.core.ui.SemaforoEtiqueta
import com.santiquiroz.nodo.core.models.RepoDeModelos

@Composable
fun ExploreScreen(viewModel: ExploreViewModel = hiltViewModel()) {
    val estado by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = estado.consulta,
            onValueChange = viewModel::onConsultaChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Buscar modelos GGUF en Hugging Face") },
            placeholder = { Text("qwen2.5 3b, llama 3.2, phi-4…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.buscar() }),
            trailingIcon = {
                IconButton(onClick = viewModel::buscar) {
                    Icon(Icons.Outlined.Search, contentDescription = "Buscar")
                }
            },
        )

        estado.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (estado.buscando) {
            CircularProgressIndicator(Modifier.size(24.dp))
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(estado.repos) { repo ->
                TarjetaRepo(
                    repo = repo,
                    abierto = estado.repoAbierto == repo.id,
                    onClick = { viewModel.abrirRepo(repo) },
                )
                if (estado.repoAbierto == repo.id) {
                    if (estado.cargandoArchivos) {
                        CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp))
                    } else {
                        estado.archivos.forEach { archivo ->
                            FilaArchivo(
                                item = archivo,
                                onEvaluar = { viewModel.evaluarCompatibilidad(archivo.archivo) },
                                onDescargar = { viewModel.descargar(archivo.archivo) },
                                onCancelar = { viewModel.cancelarDescarga(archivo.archivo) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TarjetaRepo(repo: RepoDeModelos, abierto: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (abierto) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(repo.id, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    "%,d descargas".format(repo.descargas),
                    repo.licencia?.let { "licencia $it" },
                    if (repo.esGated) "requiere aceptar términos" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilaArchivo(
    item: ArchivoConVeredicto,
    onEvaluar: () -> Unit,
    onDescargar: () -> Unit,
    onCancelar: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(start = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.archivo.cuantizacion,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(
                        "%,d MB".format(item.archivo.tamanoBytes / 1_000_000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AccionesDeArchivo(item, onEvaluar, onDescargar, onCancelar)
            }

            item.progreso?.let { fraccion ->
                LinearProgressIndicator(progress = { fraccion }, modifier = Modifier.fillMaxWidth())
                Text(
                    "%.0f%% de %,d MB".format(fraccion * 100, item.archivo.tamanoBytes / 1_000_000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.veredicto?.let { veredicto -> SemaforoEtiqueta(veredicto.semaforo, veredicto.razon, compacto = true) }

            item.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AccionesDeArchivo(
    item: ArchivoConVeredicto,
    onEvaluar: () -> Unit,
    onDescargar: () -> Unit,
    onCancelar: () -> Unit,
) {
    when {
        item.yaDescargado -> Text(
            "Descargado",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        item.progreso != null -> TextButton(onClick = onCancelar) { Text("Cancelar") }
        item.evaluando -> CircularProgressIndicator(Modifier.size(20.dp))
        item.veredicto == null -> TextButton(onClick = onEvaluar) { Text("¿Corre aquí?") }
        else -> IconButton(onClick = onDescargar) {
            Icon(Icons.Outlined.Download, contentDescription = "Descargar")
        }
    }
}


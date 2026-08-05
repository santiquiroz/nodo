package com.santiquiroz.nodo.ui.nav

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.santiquiroz.nodo.feature.chat.ChatScreen
import com.santiquiroz.nodo.feature.server.ServerScreen
import com.santiquiroz.nodo.ui.screens.PlaceholderScreen

data class Destino(val ruta: String, val titulo: String, val icono: ImageVector)

val destinos = listOf(
    Destino("modelos", "Modelos", Icons.Outlined.Folder),
    Destino("explorar", "Explorar", Icons.Outlined.Search),
    Destino("servidor", "Servidor", Icons.Outlined.Dns),
    Destino("chat", "Chat", Icons.Outlined.ChatBubbleOutline),
    Destino("ajustes", "Ajustes", Icons.Outlined.Settings),
)

@Composable
fun NodoNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val rutaActual = backStack?.destination?.route

    Scaffold(
        // Sin esto la ventana edge-to-edge no cede espacio al teclado y el chat se sale de pantalla
        modifier = Modifier.imePadding(),
        bottomBar = {
            NavigationBar {
                destinos.forEach { destino ->
                    NavigationBarItem(
                        selected = rutaActual == destino.ruta,
                        onClick = {
                            navController.navigate(destino.ruta) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destino.icono, contentDescription = destino.titulo) },
                        label = { Text(destino.titulo) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "modelos",
            modifier = Modifier.padding(padding),
        ) {
            composable("modelos") { PlaceholderScreen("Modelos") }
            composable("explorar") { PlaceholderScreen("Explorar") }
            composable("servidor") { ServerScreen() }
            composable("chat") { ChatScreen() }
            composable("ajustes") { PlaceholderScreen("Ajustes") }
        }
    }
}

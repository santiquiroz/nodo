package com.santiquiroz.nodo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.santiquiroz.nodo.ui.nav.NodoNavHost
import com.santiquiroz.nodo.ui.theme.NodoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val pedirNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        asegurarPermisoDeNotificaciones()
        setContent {
            NodoTheme {
                NodoNavHost()
            }
        }
    }

    // Sin este permiso Android 13+ suprime la notificación del servidor, y con ella
    // el único indicador de que está corriendo y su botón de detener.
    private fun asegurarPermisoDeNotificaciones() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedido = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!concedido) pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

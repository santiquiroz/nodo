package com.santiquiroz.nodo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.santiquiroz.nodo.ui.nav.NodoNavHost
import com.santiquiroz.nodo.ui.theme.NodoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NodoTheme {
                NodoNavHost()
            }
        }
    }
}

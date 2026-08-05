package com.santiquiroz.nodo.core.serving

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.santiquiroz.nodo.core.inference.EngineConfig
import com.santiquiroz.nodo.core.inference.InferenceEngine
import com.santiquiroz.nodo.core.settings.Preferencias
import com.santiquiroz.nodo.core.tools.RegistroDeHerramientas
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.inject.Inject

private const val TAG = "NodoServidor"

/**
 * Mantiene el modelo caliente y el servidor HTTP vivo mientras la app está en segundo plano.
 *
 * Tipo `connectedDevice`: Android 14+ exige un foregroundServiceType y no existe uno para IA;
 * `dataSync` está capado a ~6 h/día en Android 15 y `specialUse` requiere justificación en
 * Play Console. Servir clientes en localhost/LAN encaja en connectedDevice — que además
 * obliga a declarar CHANGE_NETWORK_STATE como prerrequisito (ver AndroidManifest).
 */
@AndroidEntryPoint
class NodoServerService : Service() {

    @Inject
    lateinit var engine: InferenceEngine

    @Inject
    lateinit var estado: ServerStateHolder

    @Inject
    lateinit var preferencias: Preferencias

    @Inject
    lateinit var registroDeHerramientas: RegistroDeHerramientas

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var servidor: NodoHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            detenerTodo()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Reinicio del sistema sin intent: sin modelo ni preferencias no hay nada que servir
            Log.w(TAG, "reinicio sin intent: el servidor no puede recuperar el modelo, deteniendo")
            estado.marcarFallo("El servidor se detuvo al reiniciarse el sistema")
            stopSelf()
            return START_NOT_STICKY
        }
        arrancar(intent)
        // REDELIVER en vez de STICKY: si el sistema nos mata, queremos el intent original
        // (puerto, modelo, LAN) de vuelta, no un arranque con valores por defecto.
        return START_REDELIVER_INTENT
    }

    private fun arrancar(intent: Intent) {
        val puerto = intent.getIntExtra(EXTRA_PUERTO, NodoHttpServer.PUERTO_DEFECTO)
        val exponerEnLan = intent.getBooleanExtra(EXTRA_LAN, false)
        val rutaModelo = intent.getStringExtra(EXTRA_MODELO)
        val token = if (exponerEnLan) generarToken() else null

        crearCanal()
        val arrancoEnPrimerPlano = runCatching {
            startForeground(ID_NOTIFICACION, construirNotificacion(puerto, exponerEnLan))
        }.onFailure { error ->
            Log.e(TAG, "startForeground falló", error)
            estado.marcarFallo("Android no permitió el servicio en primer plano: ${error.message}")
        }.isSuccess
        if (!arrancoEnPrimerPlano) {
            stopSelf()
            return
        }

        if (servidor == null) {
            val srv = NodoHttpServer(
                service = ChatCompletionsService(
                    engine = engine,
                    herramientasPropias = { registroDeHerramientas.disponibles() },
                ),
                puerto = puerto,
                soloLocalhost = !exponerEnLan,
                token = token,
            )
            val arranco = runCatching { srv.iniciar() }
                .onFailure { error ->
                    Log.e(TAG, "no se pudo abrir el puerto $puerto", error)
                    estado.marcarFallo("No se pudo abrir el puerto $puerto: ${error.message}")
                }
                .isSuccess
            if (!arranco) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            servidor = srv
            estado.marcarIniciado(puerto, exponerEnLan, token)
        }

        // Precalentar: la primera petición no debería pagar la carga completa del modelo
        if (rutaModelo != null) {
            scope.launch {
                val ajustes = preferencias.actuales()
                engine.load(rutaModelo, EngineConfig(contextLength = ajustes.contexto, threads = ajustes.hilos))
            }
        }
    }

    private fun detenerTodo() {
        servidor?.detener()
        servidor = null
        estado.marcarDetenido()
        liberarModelo()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Sincrónico y NonCancellable a propósito: son varios GB de memoria nativa y el
     * `scope.cancel()` de onDestroy llega en milisegundos — una corrutina normal se
     * cancelaría esperando el mutex del motor y el modelo quedaría residente.
     */
    private fun liberarModelo() {
        runCatching {
            runBlocking { withContext(NonCancellable) { engine.unload() } }
        }.onFailure { Log.e(TAG, "fallo al descargar el modelo", it) }
    }

    override fun onDestroy() {
        servidor?.detener()
        servidor = null
        estado.marcarDetenido()
        liberarModelo()
        scope.cancel()
        super.onDestroy()
    }

    private fun generarToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun crearCanal() {
        val canal = NotificationChannel(
            ID_CANAL,
            "Servidor de Nodo",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Indica que Nodo está sirviendo modelos a otras apps" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun construirNotificacion(puerto: Int, enLan: Boolean): Notification {
        val alcance = if (enLan) "localhost y red WiFi" else "localhost"
        val detener = PendingIntent.getService(
            this,
            0,
            Intent(this, NodoServerService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle("Nodo sirviendo en el puerto $puerto")
            .setContentText("Disponible en $alcance")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", detener)
            .build()
    }

    companion object {
        private const val ID_CANAL = "nodo_servidor"
        private const val ID_NOTIFICACION = 1
        const val ACCION_DETENER = "com.santiquiroz.nodo.DETENER_SERVIDOR"
        const val EXTRA_PUERTO = "puerto"
        const val EXTRA_LAN = "lan"
        const val EXTRA_MODELO = "modelo"

        fun iniciar(context: Context, puerto: Int, enLan: Boolean, rutaModelo: String?) {
            val intent = Intent(context, NodoServerService::class.java)
                .putExtra(EXTRA_PUERTO, puerto)
                .putExtra(EXTRA_LAN, enLan)
                .putExtra(EXTRA_MODELO, rutaModelo)
            context.startForegroundService(intent)
        }

        fun detener(context: Context) {
            context.startService(
                Intent(context, NodoServerService::class.java).setAction(ACCION_DETENER),
            )
        }
    }
}

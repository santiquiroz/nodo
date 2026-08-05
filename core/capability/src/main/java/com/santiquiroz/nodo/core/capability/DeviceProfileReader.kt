package com.santiquiroz.nodo.core.capability

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceProfileReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Se presupuesta contra `availMem`, no contra `totalMem`: lo que importa es lo que queda
     * libre ahora, con las demás apps del usuario ya abiertas.
     */
    fun leer(): DeviceProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceProfile(
            ramTotalBytes = info.totalMem,
            ramDisponibleBytes = info.availMem,
            socModelo = Build.SOC_MODEL.takeIf { it != Build.UNKNOWN },
            socFabricante = Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN },
            nucleos = Runtime.getRuntime().availableProcessors(),
        )
    }
}

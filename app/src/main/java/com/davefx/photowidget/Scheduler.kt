package com.davefx.photowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Programación de los cambios automáticos de foto.
 *
 * Clave del ahorro de batería: se usa AlarmManager.RTC (NO RTC_WAKEUP) con alarma inexacta.
 *  - Si el teléfono está dormido cuando vence, NO se despierta: el cambio se hace la próxima vez
 *    que el dispositivo despierte por otro motivo (normalmente, al encender la pantalla).
 *  - Al ser inexacta, Android la agrupa con otras alarmas para minimizar despertares de CPU.
 *  - No hace falta permiso de alarmas exactas, ni servicios, ni WorkManager, ni wakelocks.
 * Si el widget tiene una sola foto o solo cambia al tocarlo, no se programa nada.
 *
 * En modo pase de diapositivas (intervalos cortos) el launcher se encarga de pasar las fotos;
 * la alarma solo sirve para rebarajar y detectar fotos nuevas una vez al día. Desde Android 15
 * suena cada pocos minutos (con la pantalla apagada no hace nada) para pasar a la siguiente ventana de fotos (ver PhotoWindow).
 */
object Scheduler {
    const val ACTION_ALARM = "com.davefx.photowidget.ALARM"

    private fun pendingIntent(ctx: Context, id: Int, create: Boolean): PendingIntent? {
        val intent = Intent(ctx, PhotoWidgetProvider::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("dfxphoto://widget/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, id, intent, flags)
    }

    fun schedule(ctx: Context, id: Int, cfg: WidgetConfig, photoCount: Int) {
        if (cfg.intervalSeconds <= 0 || photoCount in 0..1 || !cfg.hasSources) {
            cancel(ctx, id)
            return
        }
        val interval = when {
            !cfg.slideshow -> cfg.intervalSeconds * 1000L
            PhotoWindow.enabled -> PhotoWindow.advanceIntervalMs(ctx, id, cfg) // siguiente ventana de fotos
            else -> AlarmManager.INTERVAL_DAY
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + interval,
            interval,
            pendingIntent(ctx, id, true)!!,
        )
    }

    fun cancel(ctx: Context, id: Int) {
        val pi = pendingIntent(ctx, id, false) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }
}

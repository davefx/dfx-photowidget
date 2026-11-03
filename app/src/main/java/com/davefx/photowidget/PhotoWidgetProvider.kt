package com.davefx.photowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors

/**
 * Receptor del widget. No hay ningún servicio: cada evento se atiende con goAsync() en un único
 * hilo de fondo y el proceso queda libre en cuanto termina.
 */
class PhotoWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            WidgetUpdater.ACTION_NEXT -> runAsync {
                if (validId(ctx, id)) WidgetUpdater.handle(ctx, id, WidgetUpdater.Trigger.TAP, intent.getIntExtra(WidgetUpdater.EXTRA_SHOWN, -1))
            }
            Scheduler.ACTION_ALARM -> runAsync {
                if (validId(ctx, id)) WidgetUpdater.handle(ctx, id, WidgetUpdater.Trigger.ALARM)
                else Scheduler.cancel(ctx, id) // alarma huérfana de un widget eliminado
            }
            ACTION_CONFIGURED -> runAsync { if (validId(ctx, id)) WidgetUpdater.handle(ctx, id, WidgetUpdater.Trigger.CONFIGURED) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> runAsync {
                // Las alarmas no sobreviven a un reinicio: reprograma y repinta la foto en caché.
                for (wid in allIds(ctx)) {
                    if (!Store.isConfigured(ctx, wid)) continue
                    val cfg = Store.load(ctx, wid)
                    Scheduler.schedule(ctx, wid, cfg, Store.loadState(ctx, wid).indexSize.takeIf { it > 0 } ?: -1)
                    WidgetUpdater.redraw(ctx, wid)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val ctx = context.applicationContext
        runAsync {
            for (id in ids) {
                if (Store.isConfigured(ctx, id)) WidgetUpdater.redraw(ctx, id)
                else WidgetUpdater.showEmpty(ctx, id, configured = false)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val ctx = context.applicationContext
        if (Store.isConfigured(ctx, id)) runAsync { WidgetUpdater.resized(ctx, id) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val ctx = context.applicationContext
        runAsync {
            for (id in ids) {
                Scheduler.cancel(ctx, id)
                Store.delete(ctx, id)
            }
            Grants.releaseUnused(ctx)
        }
    }

    private fun runAsync(block: () -> Unit) {
        val pending = goAsync()
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e("PhotoWidget", "Error en el widget", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIGURED = "com.davefx.photowidget.CONFIGURED"
        private val executor = Executors.newSingleThreadExecutor()

        fun allIds(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, PhotoWidgetProvider::class.java))

        private fun validId(ctx: Context, id: Int) =
            id != AppWidgetManager.INVALID_APPWIDGET_ID && id in allIds(ctx)

        /** Avisa al widget de que su configuración ha cambiado. */
        fun notifyConfigured(ctx: Context, id: Int) {
            ctx.sendBroadcast(
                Intent(ctx, PhotoWidgetProvider::class.java)
                    .setAction(ACTION_CONFIGURED)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            )
        }
    }
}

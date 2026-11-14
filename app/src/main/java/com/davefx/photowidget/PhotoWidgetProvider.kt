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
 * Widget receiver. There is no service: each event is handled with goAsync() on a single
 * background thread, and the process is free again as soon as it finishes.
 */
class PhotoWidgetProvider : AppWidgetProvider() {

    private var appContext: Context? = null

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        appContext = ctx
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            WidgetUpdater.ACTION_NEXT -> runAsync {
                if (validId(ctx, id)) WidgetUpdater.handle(ctx, id, WidgetUpdater.Trigger.TAP, intent.getIntExtra(WidgetUpdater.EXTRA_SHOWN, -1))
            }
            Scheduler.ACTION_ALARM -> runAsync {
                if (validId(ctx, id)) WidgetUpdater.handle(ctx, id, WidgetUpdater.Trigger.ALARM)
                else Scheduler.cancel(ctx, id) // orphaned alarm from a deleted widget
            }
            ACTION_CONFIGURED -> runAsync { if (validId(ctx, id)) WidgetUpdater.handle(ctx, id, WidgetUpdater.Trigger.CONFIGURED) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> runAsync {
                DiagLog.log(ctx, "${intent.action?.substringAfterLast('.')}: widgets ${allIds(ctx).joinToString()}")
                // Alarms don't survive a reboot: reschedule and redraw the cached photo.
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
            DiagLog.log(ctx, "onUpdate ${ids.joinToString()}")
            for (id in ids) {
                if (Store.isConfigured(ctx, id)) WidgetUpdater.redraw(ctx, id)
                else WidgetUpdater.showEmpty(ctx, id, configured = false)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val ctx = context.applicationContext
        if (Store.isConfigured(ctx, id)) runAsync {
            DiagLog.log(ctx, "resized widget=$id to ${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}-${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}" +
                "x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}-${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)} dp")
            WidgetUpdater.resized(ctx, id)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val ctx = context.applicationContext
        runAsync {
            DiagLog.log(ctx, "deleted ${ids.joinToString()}")
            for (id in ids) {
                Scheduler.cancel(ctx, id)
                Store.delete(ctx, id)
            }
            Grants.releaseUnused(ctx)
        }
    }

    private fun runAsync(block: () -> Unit) {
        val pending = goAsync()
        val ctx = appContext
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                if (ctx != null) DiagLog.log(ctx, "Widget error", e) else Log.e("PhotoWidget", "Widget error", e)
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

        /** Notifies the widget that its configuration has changed. */
        fun notifyConfigured(ctx: Context, id: Int) {
            ctx.sendBroadcast(
                Intent(ctx, PhotoWidgetProvider::class.java)
                    .setAction(ACTION_CONFIGURED)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            )
        }
    }
}

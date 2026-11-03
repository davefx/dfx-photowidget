package com.davefx.photowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews

/**
 * Lógica del widget.
 *
 * Principio: aquí nunca se decodifican fotos ni se recorren carpetas. Este código solo le dice
 * al launcher QUÉ foto toca mostrar. El trabajo caro (leer el índice, decodificar) lo hace
 * SlideshowService cuando el launcher le pide la foto, y el launcher solo la pide para dibujar
 * el widget, es decir, con el escritorio en primer plano.
 */
object WidgetUpdater {
    const val ACTION_NEXT = "com.davefx.photowidget.NEXT"
    /** Android 15+: posición dentro de la ventana de la foto que se ha tocado. */
    const val EXTRA_SHOWN = "shown"

    enum class Trigger { TAP, ALARM, CONFIGURED }

    fun handle(ctx: Context, id: Int, trigger: Trigger, shown: Int = -1) {
        val cfg = Store.load(ctx, id)
        if (!cfg.hasSources) {
            showEmpty(ctx, id, configured = false)
            Scheduler.cancel(ctx, id)
            return
        }
        when (trigger) {
            Trigger.CONFIGURED -> {
                // El índice se reconstruirá cuando el launcher pida la primera foto.
                Store.saveState(ctx, id, WidgetState())
                push(ctx, id, cfg)
                AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
                val known = if (cfg.folders.isEmpty()) cfg.photos.size else -1
                Scheduler.schedule(ctx, id, cfg, known)
            }
            Trigger.TAP ->
                // Pase: showNext no sobrevive a una actualización parcial en Android 12+ (el sistema
                // descarta esa acción al fusionar), así que va en una completa. Es barata: el
                // adaptador es el mismo y el launcher no vuelve a pedir las fotos que ya tiene.
                when {
                    !cfg.slideshow -> step(ctx, id, cfg)
                    PhotoWindow.enabled && shown >= 0 -> tapWindow(ctx, id, cfg, shown)
                    else -> push(ctx, id, cfg) { showNext(R.id.flipper) }
                }
            Trigger.ALARM ->
                if (cfg.slideshow) {
                    // Hasta Android 14, alarma diaria: marca el índice como caducado y se rebaraja al
                    // volver al escritorio. Desde Android 15, cada pocos minutos: pasa a la siguiente ventana.
                    if (PhotoWindow.enabled) advanceWindow(ctx, id, cfg) else refreshIndexLater(ctx, id)
                } else {
                    if (cfg.pauseOnBatterySaver && ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return
                    val st = Store.loadState(ctx, id)
                    // Si se cambió hace poco (p. ej., tocando el widget), no repetir.
                    if (System.currentTimeMillis() - st.lastChange < cfg.intervalSeconds * 1000L / 2) return
                    step(ctx, id, cfg)
                }
        }
    }

    /** Pasa a la siguiente posición. Casi siempre solo escribe dos números y hace una llamada al sistema. */
    private fun step(ctx: Context, id: Int, cfg: WidgetConfig) {
        val st = Store.loadState(ctx, id)
        if (st.indexSize == 1) {
            Scheduler.cancel(ctx, id)
            return
        }
        if (st.indexSize <= 0) return // el índice aún no se ha construido
        val now = System.currentTimeMillis()
        val pos = st.position + 1
        when {
            pos >= st.indexSize -> {
                // Vuelta completa: rebarajar (y detectar fotos nuevas) al preparar la siguiente foto.
                if (PhotoWindow.enabled && !interactive(ctx)) return // Android 15+ decodifica ya: esperar
                Store.saveState(ctx, id, st.copy(position = 0, windowStart = 0, indexTime = 0L, lastChange = now))
                AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
                partial(ctx, id, cfg) { setDisplayedChild(R.id.flipper, 0) }
            }
            PhotoWindow.enabled && pos - st.windowStart !in 0 until PhotoWindow.size(ctx, id) -> {
                // La siguiente foto está fuera de la ventana: el sistema prepara la nueva ventana
                // ahora mismo, así que no se hace con la pantalla apagada.
                if (!interactive(ctx)) return
                Store.saveState(ctx, id, st.copy(position = pos, windowStart = pos, lastChange = now))
                AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
                partial(ctx, id, cfg) { setDisplayedChild(R.id.flipper, 0) }
            }
            else -> {
                Store.saveState(ctx, id, st.copy(position = pos, lastChange = now))
                partial(ctx, id, cfg) { setDisplayedChild(R.id.flipper, pos - windowStart(st)) }
            }
        }
    }

    /**
     * Pase en Android 15+: el launcher solo tiene la ventana actual y la repite. Cada pocos minutos,
     * con la pantalla encendida, se pasa a las siguientes fotos (el sistema las prepara en ese momento).
     */
    private fun advanceWindow(ctx: Context, id: Int, cfg: WidgetConfig) {
        if (!interactive(ctx)) return
        if (cfg.pauseOnBatterySaver && ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return
        val st = Store.loadState(ctx, id)
        if (st.indexSize <= 1) return
        if (st.indexSize <= PhotoWindow.size(ctx, id)) {
            // Todas las fotos caben: solo rebarajar una vez al día.
            if (System.currentTimeMillis() - st.indexTime > PhotoIndex.MAX_AGE_MS) refreshIndexLater(ctx, id)
            return
        }
        val next = st.windowStart + PhotoWindow.size(ctx, id)
        Store.saveState(
            ctx, id,
            if (next >= st.indexSize) st.copy(windowStart = 0, position = 0, indexTime = 0L) // vuelta: rebarajar
            else st.copy(windowStart = next, position = next),
        )
        AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
    }

    /**
     * Toque en el pase, Android 15+: se sabe qué foto de la ventana se estaba viendo. Si es la
     * última, se carga entera la ventana siguiente y se empieza por su primera foto.
     */
    private fun tapWindow(ctx: Context, id: Int, cfg: WidgetConfig, shown: Int) {
        val st = Store.loadState(ctx, id)
        val k = PhotoWindow.size(ctx, id)
        val count = minOf(st.indexSize, k)
        android.util.Log.d("PhotoWidget", "toque widget=$id foto=$shown de $count")
        if (shown < count - 1) {
            push(ctx, id, cfg) { setDisplayedChild(R.id.flipper, shown + 1) }
            return
        }
        if (st.indexSize > k) {
            val next = st.windowStart + k
            Store.saveState(
                ctx, id,
                if (next >= st.indexSize) st.copy(windowStart = 0, position = 0, indexTime = 0L) // vuelta: rebarajar
                else st.copy(windowStart = next, position = next),
            )
            // Que la alarma no cambie la ventana nueva antes de que se haya visto entera.
            Scheduler.schedule(ctx, id, cfg, st.indexSize)
        }
        // La actualización completa hace que el sistema pida la ventana nueva (o la misma, si caben todas).
        push(ctx, id, cfg) { setDisplayedChild(R.id.flipper, 0) }
    }

    private fun interactive(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    private fun windowStart(st: WidgetState) = if (PhotoWindow.enabled) st.windowStart else 0

    private fun refreshIndexLater(ctx: Context, id: Int) {
        Store.saveState(ctx, id, Store.loadState(ctx, id).copy(indexTime = 0L))
        AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
    }

    /** Vuelve a montar el widget (arranque, actualización de la app, el launcher lo pide). */
    fun redraw(ctx: Context, id: Int) {
        val cfg = Store.load(ctx, id)
        if (!cfg.hasSources) showEmpty(ctx, id, configured = false) else push(ctx, id, cfg)
    }

    /** El widget cambió de tamaño: el launcher volverá a pedir las fotos al nuevo tamaño. */
    fun resized(ctx: Context, id: Int) {
        AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
    }

    private fun layoutFor(cfg: WidgetConfig) = when {
        !cfg.slideshow -> R.layout.widget_slideshow_manual
        cfg.intervalSeconds <= 30 -> R.layout.widget_slideshow_30
        cfg.intervalSeconds <= 60 -> R.layout.widget_slideshow_60
        cfg.intervalSeconds <= 120 -> R.layout.widget_slideshow_120
        else -> R.layout.widget_slideshow_300
    }

    @Suppress("DEPRECATION") // setRemoteAdapter(Int, Intent) es la vía compatible con Android 10-11
    private fun push(ctx: Context, id: Int, cfg: WidgetConfig, extra: RemoteViews.() -> Unit = {}) {
        val adapter = Intent(ctx, SlideshowService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("dfxphoto://slideshow/$id"))
        val views = RemoteViews(ctx.packageName, layoutFor(cfg)).apply {
            setRemoteAdapter(R.id.flipper, adapter)
            setEmptyView(R.id.flipper, R.id.empty)
            setOnClickPendingIntent(R.id.root, tapIntent(ctx, id))
            if (PhotoWindow.enabled) setPendingIntentTemplate(R.id.flipper, tapTemplate(ctx, id))
            if (!cfg.slideshow) Store.loadState(ctx, id).let { st ->
                setDisplayedChild(R.id.flipper, (st.position - windowStart(st)).coerceAtLeast(0))
            }
            extra()
        }
        AppWidgetManager.getInstance(ctx).updateAppWidget(id, views)
    }

    private inline fun partial(ctx: Context, id: Int, cfg: WidgetConfig, block: RemoteViews.() -> Unit) {
        AppWidgetManager.getInstance(ctx)
            .partiallyUpdateAppWidget(id, RemoteViews(ctx.packageName, layoutFor(cfg)).apply(block))
    }

    fun showEmpty(ctx: Context, id: Int, configured: Boolean) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_photo).apply {
            setViewVisibility(R.id.photo, View.GONE)
            setViewVisibility(R.id.empty, View.VISIBLE)
            setTextViewText(
                R.id.empty,
                ctx.getString(if (configured) R.string.no_photos_found else R.string.tap_to_configure),
            )
            setOnClickPendingIntent(R.id.root, ConfigActivity.pendingIntent(ctx, id))
        }
        AppWidgetManager.getInstance(ctx).updateAppWidget(id, views)
    }

    /** Plantilla para los toques en cada foto; mutable para que el sistema añada la posición. */
    private fun tapTemplate(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(ctx, PhotoWidgetProvider::class.java)
            .setAction(ACTION_NEXT)
            .setData(Uri.parse("dfxphoto://tapitem/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun tapIntent(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(ctx, PhotoWidgetProvider::class.java)
            .setAction(ACTION_NEXT)
            .setData(Uri.parse("dfxphoto://tap/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

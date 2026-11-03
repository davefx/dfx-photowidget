package com.davefx.photowidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * Proveedor de fotos para el pase de diapositivas (intervalos cortos).
 *
 * El launcher anima un AdapterViewFlipper y solo pide la siguiente foto cuando le toca pasar.
 * AdapterViewFlipper se detiene solo con la pantalla apagada, en la pantalla de bloqueo o cuando
 * hay otra app delante: no hay temporizador, alarma ni proceso nuestro trabajando en ese tiempo.
 */
class SlideshowService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    /** Visible para los tests. */
    internal class Factory(private val ctx: Context, private val id: Int) : RemoteViewsFactory {
        private var uris: List<String> = emptyList()
        private var cfg = WidgetConfig()
        /** Android 15+: posición del índice donde empieza la ventana (ver PhotoWindow). */
        private var start = 0
        private var windowSize = Int.MAX_VALUE

        override fun onCreate() {}

        override fun onDataSetChanged() {
            cfg = Store.load(ctx, id)
            val st = Store.loadState(ctx, id)
            val now = System.currentTimeMillis()
            // Única ocasión en que se recorren las carpetas: el launcher está pidiendo fotos.
            if (st.indexTime == 0L || now - st.indexTime > PhotoIndex.MAX_AGE_MS || !PhotoIndex.file(ctx, id).exists()) {
                val size = PhotoIndex.rebuild(ctx, id, cfg)
                Store.saveState(ctx, id, st.copy(indexTime = now, indexSize = size, position = 0, windowStart = 0))
                if (size <= 1) Scheduler.cancel(ctx, id)
            }
            uris = PhotoIndex.readAll(ctx, id)
            if (PhotoWindow.enabled) {
                start = Store.loadState(ctx, id).windowStart.let { if (it in uris.indices) it else 0 }
                windowSize = PhotoWindow.size(ctx, id)
            } else {
                start = 0
                windowSize = Int.MAX_VALUE
            }
        }

        override fun getCount() = minOf(uris.size, windowSize)

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, itemLayout())
            if (position !in 0 until getCount()) return views
            val uri = uris[(start + position) % uris.size]
            Log.d("Slideshow", "getViewAt widget=$id pos=$position")
            val (w, h) = PhotoWindow.photoSize(ctx, id)
            // Android 15+: al tocar, la foto dice en qué posición de la ventana está; si es la
            // última, se carga la ventana siguiente (ver WidgetUpdater).
            if (PhotoWindow.enabled) views.setOnClickFillInIntent(
                R.id.item_photo, Intent().putExtra(WidgetUpdater.EXTRA_SHOWN, position),
            )
            try {
                views.setImageViewBitmap(R.id.item_photo, render(uri, w, h))
            } catch (e: Exception) {
                Log.w("Slideshow", "No se puede mostrar $uri", e)
            }
            return views
        }

        /**
         * Android 15+ vuelve a pedir toda la ventana en cada actualización completa (un toque,
         * por ejemplo). Mientras el proceso sigue vivo, las fotos ya preparadas no se decodifican otra vez.
         */
        private fun render(uri: String, w: Int, h: Int): Bitmap {
            if (!PhotoWindow.enabled) return Renderer.frame(ctx, Renderer.decode(ctx, Uri.parse(uri), w, h, cfg), cfg, w)
            val key = "$id|$uri|$w|$h|${cfg.hashCode()}"
            cache.get(key)?.let { return it }
            Log.d("Slideshow", "decode widget=$id")
            return Renderer.frame(ctx, Renderer.decode(ctx, Uri.parse(uri), w, h, cfg), cfg, w)
                .also { cache.put(key, it) }
        }

        /** Vista vacía mientras se prepara la foto, en lugar del texto «Cargando…». */
        override fun getLoadingView() = RemoteViews(ctx.packageName, R.layout.widget_item)
        /**
         * El launcher ajusta la foto al tamaño REAL del widget en cada dibujado. Así llena todo
         * el espacio aunque el tamaño que nos comunicó el launcher sea aproximado o esté desfasado.
         */
        private fun itemLayout() = if (cfg.crop) R.layout.widget_item_crop else R.layout.widget_item

        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
        override fun onDestroy() {
            uris = emptyList()
        }
    }

    companion object {
        /** Cabe una ventana completa (PhotoWindow.MAX_SIZE × 2 MB). Se libera con el proceso. */
        private val cache = object : LruCache<String, Bitmap>(16_000_000) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
}

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
 * Photo provider for the slideshow (short intervals).
 *
 * The launcher animates an AdapterViewFlipper and only requests the next photo when it's time to flip.
 * AdapterViewFlipper stops on its own when the screen is off, on the lock screen, or when another
 * app is in front: no timer, alarm or process of ours is doing any work during that time.
 */
class SlideshowService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    /** Visible for tests. */
    internal class Factory(private val ctx: Context, private val id: Int) : RemoteViewsFactory {
        private var uris: List<String> = emptyList()
        private var cfg = WidgetConfig()
        /** Android 15+: index position where the window starts (see PhotoWindow). */
        private var start = 0
        private var windowSize = Int.MAX_VALUE
        /** Diagnostics: bitmap bytes handed out since the last onDataSetChanged. */
        private var servedBytes = 0L
        private var served = 0
        private var decoded = 0

        override fun onCreate() {}

        override fun onDataSetChanged() {
            cfg = Store.load(ctx, id)
            val st = Store.loadState(ctx, id)
            val now = System.currentTimeMillis()
            // The only time folders are scanned: the launcher is requesting photos.
            val rebuild = st.indexTime == 0L || now - st.indexTime > PhotoIndex.MAX_AGE_MS || !PhotoIndex.file(ctx, id).exists()
            if (rebuild) {
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
            servedBytes = 0; served = 0; decoded = 0
            DiagLog.log(ctx, "dataSetChanged widget=$id rebuilt=$rebuild photos=${uris.size} start=$start count=${getCount()}" +
                if (PhotoWindow.enabled) PhotoWindow.photoSize(ctx, id).let { (w, h) -> " photo=${w}x$h" } else "")
        }

        override fun getCount() = minOf(uris.size, windowSize)

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, itemLayout())
            if (position !in 0 until getCount()) return views
            val uri = uris[(start + position) % uris.size]
            Log.d("Slideshow", "getViewAt widget=$id pos=$position")
            val (w, h) = PhotoWindow.photoSize(ctx, id)
            // Android 15+: on tap, the photo reports its position in the window; if it's the
            // last one, the next window is loaded (see WidgetUpdater).
            if (PhotoWindow.enabled) views.setOnClickFillInIntent(
                R.id.item_photo, Intent().putExtra(WidgetUpdater.EXTRA_SHOWN, position),
            )
            try {
                val bmp = render(uri, w, h)
                views.setImageViewBitmap(R.id.item_photo, bmp)
                servedBytes += bmp.allocationByteCount
            } catch (e: Exception) {
                DiagLog.log(ctx, "widget=$id can't display $uri", e)
            }
            if (++served == getCount()) {
                val dm = ctx.resources.displayMetrics
                DiagLog.log(ctx, "widget=$id served $served photos ($decoded decoded): ${DiagLog.mb(servedBytes)}" +
                    " of a ${DiagLog.mb(6L * dm.widthPixels * dm.heightPixels)} limit")
            }
            return views
        }

        /**
         * Android 15+ requests the whole window again on every full update (a tap, for
         * example). While the process stays alive, photos already prepared aren't decoded again.
         */
        private fun render(uri: String, w: Int, h: Int): Bitmap {
            if (!PhotoWindow.enabled) return Renderer.frame(ctx, Renderer.decode(ctx, Uri.parse(uri), w, h, cfg), cfg, w)
            val key = "$id|$uri|$w|$h|${cfg.hashCode()}"
            cache.get(key)?.let { return it }
            Log.d("Slideshow", "decode widget=$id")
            decoded++
            return Renderer.frame(ctx, Renderer.decode(ctx, Uri.parse(uri), w, h, cfg), cfg, w)
                .also { cache.put(key, it) }
        }

        /** Empty view while the photo is being prepared, instead of the "Loading…" text. */
        override fun getLoadingView() = RemoteViews(ctx.packageName, R.layout.widget_item)
        /**
         * The launcher fits the photo to the widget's ACTUAL size on every draw. That way it fills
         * the whole space even if the size the launcher reported to us is approximate or stale.
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
        /** Holds a full window (PhotoWindow.MAX_SIZE × 2 MB). Freed along with the process. */
        private val cache = object : LruCache<String, Bitmap>(16_000_000) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
}

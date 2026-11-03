package com.davefx.photowidget

import android.content.Context
import android.os.Build
import kotlin.math.sqrt

/**
 * Photo window for Android 15 and later.
 *
 * Up to Android 14, the launcher requests each photo from SlideshowService only when it's about to
 * draw it. Since Android 15, AppWidgetManager converts the adapter into a fixed list: on every
 * full update (or notifyAppWidgetViewDataChanged) it calls getViewAt() for ALL positions, in our
 * process, and sends all the bitmaps together. If they add up to more than the system limit
 * (about 1.5 screens' worth of ARGB), the update is rejected and the widget ends up empty.
 * Seen on a Xiaomi with Android 16 and Nova Launcher.
 *
 * That's why, on those versions, the adapter only exposes a few photos (the window), which always
 * fit. The window moves along the index: in slideshow mode, with a non-wakeup alarm; in alarm or
 * tap mode, when the next photo falls outside it.
 */
object PhotoWindow {
    val enabled get() = Build.VERSION.SDK_INT >= 35

    /** Maximum memory per photo: the launcher's cache is limited. */
    private const val MAX_PHOTO_BYTES = 2_000_000

    /** Fraction of the system limit we use, leaving some headroom. */
    private const val BUDGET_FRACTION = 0.8

    const val MIN_SIZE = 2
    const val MAX_SIZE = 12

    /** Size in pixels of each photo in the adapter. */
    fun photoSize(ctx: Context, id: Int): Pair<Int, Int> {
        var (w, h) = Renderer.targetSize(ctx, id)
        val maxPixels = MAX_PHOTO_BYTES / 4
        if (w * h > maxPixels) {
            val f = sqrt(maxPixels.toDouble() / (w * h))
            w = (w * f).toInt().coerceAtLeast(1)
            h = (h * f).toInt().coerceAtLeast(1)
        }
        return w to h
    }

    /**
     * How often to move to the next window in slideshow mode: exactly the time the launcher takes
     * to show all of its photos. An inexact alarm never fires early, so no photo is skipped: at
     * worst, one is repeated. Android doesn't allow repeating alarms shorter than 1 min.
     * Cost: one decode per photo shown, same as the launcher before Android 15.
     */
    fun advanceIntervalMs(ctx: Context, id: Int, cfg: WidgetConfig): Long =
        maxOf(60_000L, size(ctx, id) * cfg.intervalSeconds * 1000L)

    /** How many photos fit in the window without getting close to the system limit. */
    fun size(ctx: Context, id: Int): Int {
        val dm = ctx.resources.displayMetrics
        // AppWidgetServiceImpl: 1.5 × screen width × screen height × 4 bytes.
        val systemCap = 6L * dm.widthPixels * dm.heightPixels
        val (w, h) = photoSize(ctx, id)
        val perPhoto = 4L * w * h
        return ((systemCap * BUDGET_FRACTION) / perPhoto).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
    }
}

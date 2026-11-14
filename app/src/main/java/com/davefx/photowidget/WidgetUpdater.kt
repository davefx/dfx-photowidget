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
 * Widget logic.
 *
 * Principle: no photos are decoded and no folders are scanned here. This code only tells the
 * launcher WHICH photo to show. The expensive work (reading the index, decoding) is done by
 * SlideshowService when the launcher asks it for the photo, and the launcher only asks in order
 * to draw the widget, i.e. with the home screen in the foreground.
 */
object WidgetUpdater {
    const val ACTION_NEXT = "com.davefx.photowidget.NEXT"
    /** Android 15+: position within the window of the photo that was tapped. */
    const val EXTRA_SHOWN = "shown"

    enum class Trigger { TAP, ALARM, CONFIGURED }

    fun handle(ctx: Context, id: Int, trigger: Trigger, shown: Int = -1) {
        val cfg = Store.load(ctx, id)
        // Slideshow alarms with every photo in the window usually do nothing: not logged.
        if (trigger != Trigger.ALARM) DiagLog.log(ctx, "$trigger widget=$id shown=$shown interactive=${interactive(ctx)} state=${Store.loadState(ctx, id)}")
        if (!cfg.hasSources) {
            showEmpty(ctx, id, configured = false)
            Scheduler.cancel(ctx, id)
            return
        }
        when (trigger) {
            Trigger.CONFIGURED -> {
                // The index will be rebuilt when the launcher requests the first photo.
                Store.saveState(ctx, id, WidgetState())
                push(ctx, id, cfg)
                if (!PhotoWindow.enabled) notifyChanged(ctx, id) // since Android 15 the full update already fetches the photos
                val known = if (cfg.folders.isEmpty()) cfg.photos.size else -1
                Scheduler.schedule(ctx, id, cfg, known)
            }
            Trigger.TAP ->
                // Slideshow: showNext doesn't survive a partial update on Android 12+ (the system
                // drops that action when merging), so it goes in a full one. It's cheap: the
                // adapter is the same and the launcher doesn't re-request the photos it already has.
                when {
                    !cfg.slideshow -> step(ctx, id, cfg)
                    PhotoWindow.enabled && shown >= 0 -> tapWindow(ctx, id, cfg, shown)
                    else -> push(ctx, id, cfg) { showNext(R.id.flipper) }
                }
            Trigger.ALARM ->
                if (cfg.slideshow) {
                    // Up to Android 14, daily alarm: marks the index as stale so it's reshuffled when
                    // returning to the home screen. Since Android 15, every few minutes: move to the next window.
                    if (PhotoWindow.enabled) advanceWindow(ctx, id, cfg) else refreshIndexLater(ctx, id)
                } else {
                    if (cfg.pauseOnBatterySaver && ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return
                    val st = Store.loadState(ctx, id)
                    // If it changed recently (e.g. by tapping the widget), don't change it again.
                    if (System.currentTimeMillis() - st.lastChange < cfg.intervalSeconds * 1000L / 2) return
                    step(ctx, id, cfg)
                }
        }
    }

    /** Moves to the next position. Almost always it just writes two numbers and makes one system call. */
    private fun step(ctx: Context, id: Int, cfg: WidgetConfig) {
        val st = Store.loadState(ctx, id)
        if (st.indexSize == 1) {
            Scheduler.cancel(ctx, id)
            return
        }
        if (st.indexSize <= 0) return // the index hasn't been built yet
        val now = System.currentTimeMillis()
        val pos = st.position + 1
        when {
            pos >= st.indexSize -> {
                // Full cycle: reshuffle (and pick up new photos) when preparing the next photo.
                if (PhotoWindow.enabled && !interactive(ctx)) return // Android 15+ would decode right now: wait
                Store.saveState(ctx, id, st.copy(position = 0, windowStart = 0, indexTime = 0L, lastChange = now))
                reload(ctx, id, cfg, child = 0)
            }
            PhotoWindow.enabled && pos - st.windowStart !in 0 until PhotoWindow.size(ctx, id) -> {
                // The next photo is outside the window: the system prepares the new window
                // right away, so don't do it while the screen is off.
                if (!interactive(ctx)) return
                Store.saveState(ctx, id, st.copy(position = pos, windowStart = pos, lastChange = now))
                reload(ctx, id, cfg, child = 0)
            }
            else -> {
                Store.saveState(ctx, id, st.copy(position = pos, lastChange = now))
                partial(ctx, id, cfg) { setDisplayedChild(R.id.flipper, pos - windowStart(st)) }
            }
        }
    }

    /**
     * Slideshow on Android 15+: the launcher only has the current window and loops over it. Every few
     * minutes, with the screen on, move on to the next photos (the system prepares them at that point).
     */
    private fun advanceWindow(ctx: Context, id: Int, cfg: WidgetConfig) {
        if (!interactive(ctx)) return
        if (cfg.pauseOnBatterySaver && ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return
        val st = Store.loadState(ctx, id)
        if (st.indexSize <= 1) return
        if (st.indexSize <= PhotoWindow.size(ctx, id)) {
            // All photos fit: just reshuffle once a day.
            if (System.currentTimeMillis() - st.indexTime > PhotoIndex.MAX_AGE_MS) refreshIndexLater(ctx, id)
            return
        }
        val next = st.windowStart + PhotoWindow.size(ctx, id)
        DiagLog.log(ctx, "ALARM widget=$id next window at $next of ${st.indexSize}")
        Store.saveState(
            ctx, id,
            if (next >= st.indexSize) st.copy(windowStart = 0, position = 0, indexTime = 0L) // vuelta: rebarajar
            else st.copy(windowStart = next, position = next),
        )
        reload(ctx, id, cfg)
    }

    /**
     * Tap on the slideshow, Android 15+: we know which photo of the window was being shown. If it's
     * the last one, the whole next window is loaded, starting from its first photo.
     */
    private fun tapWindow(ctx: Context, id: Int, cfg: WidgetConfig, shown: Int) {
        val st = Store.loadState(ctx, id)
        val k = PhotoWindow.size(ctx, id)
        val count = minOf(st.indexSize, k)
        android.util.Log.d("PhotoWidget", "tap widget=$id photo=$shown of $count")
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
            // Keep the alarm from replacing the new window before it has been shown in full.
            Scheduler.schedule(ctx, id, cfg, st.indexSize)
        }
        // The full update makes the system request the new window (or the same one, if all photos fit).
        push(ctx, id, cfg) { setDisplayedChild(R.id.flipper, 0) }
    }

    private fun interactive(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isInteractive

    private fun windowStart(st: WidgetState) = if (PhotoWindow.enabled) st.windowStart else 0

    private fun refreshIndexLater(ctx: Context, id: Int) {
        DiagLog.log(ctx, "widget=$id index marked stale")
        Store.saveState(ctx, id, Store.loadState(ctx, id).copy(indexTime = 0L))
        reload(ctx, id, Store.load(ctx, id))
    }

    /**
     * New photos for the widget. Up to Android 14, notifyAppWidgetViewDataChanged: the launcher
     * requests them when it draws. Since Android 15, a full update instead: the data-changed path
     * converts the adapter again on top of the stored views, and on a Xiaomi with Android 16 the
     * widget went blank after every daily reshuffle done that way, while full updates (boot, app
     * update, opening the app) always drew fine. The cost is the same: the photos are decoded once.
     */
    private fun reload(ctx: Context, id: Int, cfg: WidgetConfig, child: Int? = null) {
        if (PhotoWindow.enabled) {
            push(ctx, id, cfg) { if (child != null) setDisplayedChild(R.id.flipper, child) }
        } else {
            notifyChanged(ctx, id)
            if (child != null) partial(ctx, id, cfg) { setDisplayedChild(R.id.flipper, child) }
        }
    }

    /** Asks the launcher (or, since Android 15, the system right away) to fetch the photos again. */
    private fun notifyChanged(ctx: Context, id: Int) {
        try {
            AppWidgetManager.getInstance(ctx).notifyAppWidgetViewDataChanged(id, R.id.flipper)
        } catch (e: Exception) {
            DiagLog.log(ctx, "notifyAppWidgetViewDataChanged failed widget=$id", e)
        }
    }

    private fun update(ctx: Context, id: Int, views: RemoteViews) {
        try {
            AppWidgetManager.getInstance(ctx).updateAppWidget(id, views)
        } catch (e: Exception) {
            DiagLog.log(ctx, "updateAppWidget failed widget=$id", e)
        }
    }

    /** Rebuilds the widget (boot, app update, launcher request). */
    fun redraw(ctx: Context, id: Int) {
        val cfg = Store.load(ctx, id)
        if (!cfg.hasSources) showEmpty(ctx, id, configured = false) else push(ctx, id, cfg)
    }

    /** The widget was resized: the launcher will request the photos again at the new size. */
    fun resized(ctx: Context, id: Int) = if (PhotoWindow.enabled) redraw(ctx, id) else notifyChanged(ctx, id)

    private fun layoutFor(cfg: WidgetConfig) = when {
        !cfg.slideshow -> R.layout.widget_slideshow_manual
        cfg.intervalSeconds <= 30 -> R.layout.widget_slideshow_30
        cfg.intervalSeconds <= 60 -> R.layout.widget_slideshow_60
        cfg.intervalSeconds <= 120 -> R.layout.widget_slideshow_120
        else -> R.layout.widget_slideshow_300
    }

    @Suppress("DEPRECATION") // setRemoteAdapter(Int, Intent) is the way that works on Android 10-11
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
        update(ctx, id, views)
    }

    private inline fun partial(ctx: Context, id: Int, cfg: WidgetConfig, block: RemoteViews.() -> Unit) {
        try {
            AppWidgetManager.getInstance(ctx)
                .partiallyUpdateAppWidget(id, RemoteViews(ctx.packageName, layoutFor(cfg)).apply(block))
        } catch (e: Exception) {
            DiagLog.log(ctx, "partiallyUpdateAppWidget failed widget=$id", e)
        }
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
        DiagLog.log(ctx, "widget=$id shows empty (configured=$configured)")
        update(ctx, id, views)
    }

    /** Template for taps on each photo; mutable so the system can add the position. */
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

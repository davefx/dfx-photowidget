package com.davefx.photowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Scheduling of automatic photo changes.
 *
 * Key to saving battery: AlarmManager.RTC (NOT RTC_WAKEUP) with an inexact alarm.
 *  - If the phone is asleep when it's due, it does NOT wake up: the change happens the next time
 *    the device wakes for some other reason (usually, when the screen is turned on).
 *  - Being inexact, Android batches it with other alarms to minimize CPU wakeups.
 *  - No exact-alarm permission, services, WorkManager or wakelocks needed.
 * If the widget has a single photo or only changes on tap, nothing is scheduled.
 *
 * In slideshow mode (short intervals) the launcher takes care of flipping photos; the alarm
 * only reshuffles and picks up new photos once a day. Since Android 15 it fires every few
 * minutes (doing nothing while the screen is off) to move to the next photo window (see PhotoWindow).
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
            PhotoWindow.enabled -> PhotoWindow.advanceIntervalMs(ctx, id, cfg) // next photo window
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

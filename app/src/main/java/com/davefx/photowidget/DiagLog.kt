package com.davefx.photowidget

import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small diagnostic log kept in the app's private storage, so a failure can be studied without a
 * computer: the user saves a report from the app and sends it.
 *
 * Only rare events are written (configuration, taps, window changes, index rebuilds, errors), a
 * few hundred short lines a day at most. The file is capped and trimmed to its newer half.
 */
object DiagLog {
    private const val TAG = "PhotoWidget"
    private const val MAX_BYTES = 512_000L
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, "diag.log")

    @Synchronized
    fun log(ctx: Context, msg: String, e: Throwable? = null) {
        if (e == null) Log.i(TAG, msg) else Log.w(TAG, msg, e)
        try {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) {
                val bytes = f.readBytes()
                var cut = bytes.size / 2
                while (cut < bytes.size && bytes[cut] != '\n'.code.toByte()) cut++
                f.writeBytes(bytes.copyOfRange(minOf(cut + 1, bytes.size), bytes.size))
            }
            val trace = e?.let { "\n    " + Log.getStackTraceString(it).lines().take(12).joinToString("\n    ") } ?: ""
            f.appendText("${time.format(Date())} $msg$trace\n")
        } catch (_: Exception) {
        }
    }

    /** Full report: device, every widget's settings and state, this log and the app's recent logcat. */
    fun report(ctx: Context): String = buildString {
        val dm = ctx.resources.displayMetrics
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        appendLine("DFX Photo Widget ${pkg.versionName} (${pkg.longVersionCode})")
        appendLine("Report: ${time.format(Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Screen: ${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density}")
        appendLine("System bitmap limit (estimate): ${mb(6L * dm.widthPixels * dm.heightPixels)}")
        appendLine("Photo window: ${if (PhotoWindow.enabled) "on" else "off"}")
        appendLine()

        val awm = AppWidgetManager.getInstance(ctx)
        val held = ctx.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        for (id in PhotoWidgetProvider.allIds(ctx)) {
            val o = awm.getAppWidgetOptions(id)
            appendLine("== Widget $id ==")
            appendLine("Size (dp): min ${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}, " +
                "max ${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}x${o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}")
            if (!Store.isConfigured(ctx, id)) {
                appendLine("Not configured")
                continue
            }
            val c = Store.load(ctx, id)
            appendLine("Config: interval ${c.intervalSeconds} s, fit ${c.fit}, anchor ${c.anchor}, corners ${c.cornerDp}, " +
                "border ${c.borderDp}, subfolders ${c.includeSubfolders}, pause on saver ${c.pauseOnBatterySaver}")
            c.folders.forEach { appendLine("Folder: $it (permission ${if (it in held) "ok" else "MISSING"})") }
            val missing = c.photos.count { it !in held }
            appendLine("Photos picked: ${c.photos.size}, without permission: $missing")
            appendLine("State: ${Store.loadState(ctx, id)}")
            appendLine("Index file: ${PhotoIndex.readAll(ctx, id).size} lines")
            if (PhotoWindow.enabled) {
                val (w, h) = PhotoWindow.photoSize(ctx, id)
                appendLine("Window: ${PhotoWindow.size(ctx, id)} photos of ${w}x$h px (${mb(4L * w * h)} each)")
            }
            appendLine()
        }

        if (Build.VERSION.SDK_INT >= 30) {
            appendLine("== Recent process exits ==")
            ctx.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
                .forEach { appendLine("${time.format(Date(it.timestamp))} reason ${it.reason} status ${it.status} ${it.description ?: ""}") }
            appendLine()
        }

        appendLine("== Diagnostic log ==")
        file(ctx).takeIf { it.exists() }?.let { append(it.readText()) }
        appendLine()

        // The app can read its own log lines; the AppWidgetManager errors land here too.
        appendLine("== Recent logcat (this app) ==")
        try {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().useLines { lines ->
                lines.filter { Regex("AppWidget|RemoteViews|PhotoWidget|Slideshow|PhotoIndex|AndroidRuntime|Exception|exceeds").containsMatchIn(it) }
                    .forEach { appendLine(it) }
            }
        } catch (e: Exception) {
            appendLine("logcat unavailable: $e")
        }
    }

    fun mb(bytes: Long) = String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
}

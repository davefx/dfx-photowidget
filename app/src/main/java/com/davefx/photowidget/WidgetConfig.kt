package com.davefx.photowidget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Configuration of a widget instance. */
data class WidgetConfig(
    /** Tree URIs (SAF) of the chosen folders. */
    val folders: Set<String> = emptySet(),
    /** URIs of individual photos chosen with the picker. */
    val photos: Set<String> = emptySet(),
    val includeSubfolders: Boolean = true,
    /** Seconds between changes. 0 = only changes when the widget is tapped. */
    val intervalSeconds: Int = 3600,
    /** How the photo fills the widget: FIT_CROP (fill it by cropping) or FIT_WHOLE (whole photo). */
    val fit: Int = FIT_CROP,
    /** Which part of the photo is kept when cropping: ANCHOR_*. */
    val anchor: Int = ANCHOR_CENTER,
    /** Corner radius in dp. */
    val cornerDp: Int = 16,
    /** Frame thickness in dp (0 = no frame). */
    val borderDp: Int = 0,
    val borderColor: Int = 0xFFFFFFFF.toInt(),
    /** Don't change photos automatically while Battery Saver is on. */
    val pauseOnBatterySaver: Boolean = true,
) {
    val hasSources get() = folders.isNotEmpty() || photos.isNotEmpty()

    /**
     * Short intervals (< 15 min): slideshow animated by the launcher, which only advances
     * while the home screen is visible. Long intervals: non-wakeup alarm.
     */
    val slideshow get() = intervalSeconds in 1 until SLIDESHOW_MAX_SECONDS

    val crop get() = fit == FIT_CROP

    companion object {
        const val SLIDESHOW_MAX_SECONDS = 15 * 60

        const val FIT_CROP = 0
        const val FIT_WHOLE = 1

        const val ANCHOR_CENTER = 0
        const val ANCHOR_TOP = 1
        const val ANCHOR_BOTTOM = 2
        const val ANCHOR_LEFT = 3
        const val ANCHOR_RIGHT = 4
    }
}

/** Runtime state of a widget (not edited by the user). */
data class WidgetState(
    val currentUri: String? = null,
    val lastChange: Long = 0L,
    val position: Int = 0,
    val indexTime: Long = 0L,
    val indexSize: Int = 0,
    /** Android 15+: first index position covered by the photo window (see PhotoWindow). */
    val windowStart: Int = 0,
)

object Store {
    private const val PREFS = "widgets"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context, id: Int) = prefs(ctx).contains("$id.intervalSec")

    fun load(ctx: Context, id: Int): WidgetConfig {
        val p = prefs(ctx)
        val d = WidgetConfig()
        return WidgetConfig(
            folders = p.getStringSet("$id.folders", null)?.toSet() ?: d.folders,
            photos = p.getStringSet("$id.photos", null)?.toSet() ?: d.photos,
            includeSubfolders = p.getBoolean("$id.subfolders", d.includeSubfolders),
            intervalSeconds = p.getInt("$id.intervalSec", d.intervalSeconds),
            // Backward compatibility: previously there was only "crop" (true) or "whole photo" (false).
            fit = p.getInt("$id.fit", if (p.getBoolean("$id.crop", true)) WidgetConfig.FIT_CROP else WidgetConfig.FIT_WHOLE),
            anchor = p.getInt("$id.anchor", d.anchor),
            cornerDp = p.getInt("$id.corner", d.cornerDp),
            borderDp = p.getInt("$id.border", d.borderDp),
            borderColor = p.getInt("$id.borderColor", d.borderColor),
            pauseOnBatterySaver = p.getBoolean("$id.pauseSaver", d.pauseOnBatterySaver),
        )
    }

    fun save(ctx: Context, id: Int, c: WidgetConfig) = prefs(ctx).edit {
        putStringSet("$id.folders", c.folders)
        putStringSet("$id.photos", c.photos)
        putBoolean("$id.subfolders", c.includeSubfolders)
        putInt("$id.intervalSec", c.intervalSeconds)
        putInt("$id.fit", c.fit)
        putInt("$id.anchor", c.anchor)
        remove("$id.crop")
        putInt("$id.corner", c.cornerDp)
        putInt("$id.border", c.borderDp)
        putInt("$id.borderColor", c.borderColor)
        putBoolean("$id.pauseSaver", c.pauseOnBatterySaver)
        // Sources changed: invalidate the index so it's rebuilt on the next change.
        putLong("$id.indexTime", 0L)
    }

    fun loadState(ctx: Context, id: Int): WidgetState {
        val p = prefs(ctx)
        return WidgetState(
            currentUri = p.getString("$id.current", null),
            lastChange = p.getLong("$id.lastChange", 0L),
            position = p.getInt("$id.pos", 0),
            indexTime = p.getLong("$id.indexTime", 0L),
            indexSize = p.getInt("$id.indexSize", 0),
            windowStart = p.getInt("$id.windowStart", 0),
        )
    }

    fun saveState(ctx: Context, id: Int, s: WidgetState) = prefs(ctx).edit {
        putString("$id.current", s.currentUri)
        putLong("$id.lastChange", s.lastChange)
        putInt("$id.pos", s.position)
        putLong("$id.indexTime", s.indexTime)
        putInt("$id.indexSize", s.indexSize)
        putInt("$id.windowStart", s.windowStart)
    }

    fun delete(ctx: Context, id: Int) {
        val p = prefs(ctx)
        p.edit { p.all.keys.filter { it.startsWith("$id.") }.forEach { remove(it) } }
        PhotoIndex.file(ctx, id).delete()
    }

    /** IDs of widgets with a saved configuration. */
    fun configuredIds(ctx: Context): List<Int> =
        prefs(ctx).all.keys.filter { it.endsWith(".intervalSec") }
            .mapNotNull { it.substringBefore('.').toIntOrNull() }
}

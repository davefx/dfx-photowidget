package com.davefx.photowidget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Configuración de una instancia del widget. */
data class WidgetConfig(
    /** URIs de árbol (SAF) de las carpetas elegidas. */
    val folders: Set<String> = emptySet(),
    /** URIs de fotos sueltas elegidas con el selector. */
    val photos: Set<String> = emptySet(),
    val includeSubfolders: Boolean = true,
    /** Segundos entre cambios. 0 = solo cambia al tocar el widget. */
    val intervalSeconds: Int = 3600,
    /** Cómo ocupa la foto el widget: FIT_CROP (llenarlo recortando) o FIT_WHOLE (foto entera). */
    val fit: Int = FIT_CROP,
    /** Qué parte de la foto se conserva al recortar: ANCHOR_*. */
    val anchor: Int = ANCHOR_CENTER,
    /** Radio de las esquinas en dp. */
    val cornerDp: Int = 16,
    /** Grosor del marco en dp (0 = sin marco). */
    val borderDp: Int = 0,
    val borderColor: Int = 0xFFFFFFFF.toInt(),
    /** No cambiar de foto automáticamente con el ahorro de batería activo. */
    val pauseOnBatterySaver: Boolean = true,
) {
    val hasSources get() = folders.isNotEmpty() || photos.isNotEmpty()

    /**
     * Intervalos cortos (< 15 min): pase de diapositivas animado por el launcher, que solo avanza
     * mientras el escritorio está a la vista. Intervalos largos: alarma no despertadora.
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

/** Estado de ejecución de un widget (no lo edita el usuario). */
data class WidgetState(
    val currentUri: String? = null,
    val lastChange: Long = 0L,
    val position: Int = 0,
    val indexTime: Long = 0L,
    val indexSize: Int = 0,
    /** Android 15+: primera posición del índice que contiene la ventana de fotos (ver PhotoWindow). */
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
            // Compatibilidad: antes solo había «recortar» (true) o «foto entera» (false).
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
        // Fuentes cambiadas: invalida el índice para reconstruirlo en el siguiente cambio.
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

    /** IDs de widget con configuración guardada. */
    fun configuredIds(ctx: Context): List<Int> =
        prefs(ctx).all.keys.filter { it.endsWith(".intervalSec") }
            .mapNotNull { it.substringBefore('.').toIntOrNull() }
}

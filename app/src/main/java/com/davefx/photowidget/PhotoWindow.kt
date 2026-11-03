package com.davefx.photowidget

import android.content.Context
import android.os.Build
import kotlin.math.sqrt

/**
 * Ventana de fotos para Android 15 y posteriores.
 *
 * Hasta Android 14, el launcher pide cada foto al SlideshowService solo cuando la va a dibujar.
 * Desde Android 15, AppWidgetManager convierte el adaptador en una lista cerrada: en cada
 * actualización completa (o notifyAppWidgetViewDataChanged) llama a getViewAt() para TODAS las
 * posiciones, en nuestro proceso, y envía todos los bitmaps juntos. Si suman más que el límite
 * del sistema (unas 1,5 pantallas en ARGB), la actualización se rechaza y el widget queda vacío.
 * Visto en un Xiaomi con Android 16 y Nova Launcher.
 *
 * Por eso, en esas versiones el adaptador solo expone unas pocas fotos (la ventana), que siempre
 * caben. La ventana avanza por el índice: en el pase, con una alarma no despertadora; en el modo
 * de alarma o toque, cuando la siguiente foto queda fuera.
 */
object PhotoWindow {
    val enabled get() = Build.VERSION.SDK_INT >= 35

    /** Memoria máxima de cada foto: la caché del launcher es limitada. */
    private const val MAX_PHOTO_BYTES = 2_000_000

    /** Fracción del límite del sistema que usamos, con margen. */
    private const val BUDGET_FRACTION = 0.8

    const val MIN_SIZE = 2
    const val MAX_SIZE = 12

    /** Tamaño en píxeles de cada foto del adaptador. */
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
     * Cada cuánto pasar a la siguiente ventana en el pase: justo el tiempo que tarda el launcher en
     * mostrar todas sus fotos. La alarma inexacta nunca se adelanta, así que ninguna foto se salta:
     * como mucho, alguna se repite. Android no admite alarmas repetitivas de menos de 1 min.
     * Coste: una decodificación por foto mostrada, como el launcher antes de Android 15.
     */
    fun advanceIntervalMs(ctx: Context, id: Int, cfg: WidgetConfig): Long =
        maxOf(60_000L, size(ctx, id) * cfg.intervalSeconds * 1000L)

    /** Cuántas fotos caben en la ventana sin acercarse al límite del sistema. */
    fun size(ctx: Context, id: Int): Int {
        val dm = ctx.resources.displayMetrics
        // AppWidgetServiceImpl: 1,5 × ancho × alto de la pantalla × 4 bytes.
        val systemCap = 6L * dm.widthPixels * dm.heightPixels
        val (w, h) = photoSize(ctx, id)
        val perPhoto = 4L * w * h
        return ((systemCap * BUDGET_FRACTION) / perPhoto).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
    }
}

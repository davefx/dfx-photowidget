package com.davefx.photowidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodificación y composición de las fotos.
 *
 * Para ahorrar batería y memoria:
 *  - Se decodifica directamente al tamaño del widget (el decodificador JPEG/HEIF submuestrea
 *    sin llegar a cargar la foto de 12-50 MP a resolución completa).
 *  - Nunca se amplía una foto, y el lado mayor queda limitado a MAX_SIDE px.
 *  - Solo se llama cuando el launcher pide una foto para dibujarla (escritorio a la vista).
 *  - Esquinas y marco se pintan una vez en el bitmap: el launcher solo muestra una imagen fija.
 */
object Renderer {
    private const val MAX_SIDE = 1080

    /** Tamaño del widget en píxeles, limitado a MAX_SIDE. */
    fun targetSize(ctx: Context, id: Int): Pair<Int, Int> {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val dm = ctx.resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        // Retrato: ancho mínimo × alto máximo. Apaisado: ancho máximo × alto mínimo.
        var wDp = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        var hDp = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (wDp <= 0) wDp = 180
        if (hDp <= 0) hDp = 180
        var w = (wDp * dm.density).roundToInt()
        var h = (hDp * dm.density).roundToInt()
        val scale = min(1f, MAX_SIDE.toFloat() / max(w, h))
        w = max(1, (w * scale).roundToInt())
        h = max(1, (h * scale).roundToInt())
        return w to h
    }

    /**
     * Decodifica [uri] para un widget de [w]×[h] px: recortada para llenarlo (con el encuadre
     * elegido) o entera. El decodificador submuestrea y recorta a la vez, así que nunca se carga
     * la foto completa ni se hacen copias intermedias. Nunca se amplía por encima del original.
     */
    fun decode(ctx: Context, uri: Uri, w: Int, h: Int, cfg: WidgetConfig): Bitmap {
        val crop = cfg.fit == WidgetConfig.FIT_CROP
        val (ax, ay) = when (cfg.anchor) {
            WidgetConfig.ANCHOR_TOP -> 0.5f to 0f
            WidgetConfig.ANCHOR_BOTTOM -> 0.5f to 1f
            WidgetConfig.ANCHOR_LEFT -> 0f to 0.5f
            WidgetConfig.ANCHOR_RIGHT -> 1f to 0.5f
            else -> 0.5f to 0.5f
        }
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val iw = info.size.width.toFloat()
            val ih = info.size.height.toFloat()
            // Zona de la foto que se va a ver, en píxeles originales.
            var rw = iw
            var rh = ih
            if (crop) {
                val target = w.toFloat() / h
                if (iw / ih > target) rw = ih * target else rh = iw / target
            }
            val rx = (iw - rw) * ax
            val ry = (ih - rh) * ay
            // Escala de decodificación: cubrir (recortar) o caber (entera), nunca ampliar.
            val f = if (crop) min(1f, max(w / rw, h / rh)) else min(1f, min(w / rw, h / rh))
            val sw = max(1, (iw * f).roundToInt())
            val sh = max(1, (ih * f).roundToInt())
            decoder.setTargetSize(sw, sh)
            if (crop) {
                val left = (rx * f).roundToInt().coerceIn(0, sw - 1)
                val top = (ry * f).roundToInt().coerceIn(0, sh - 1)
                val right = ((rx + rw) * f).roundToInt().coerceIn(left + 1, sw)
                val bottom = ((ry + rh) * f).roundToInt().coerceIn(top + 1, sh)
                if (left > 0 || top > 0 || right < sw || bottom < sh) decoder.crop = Rect(left, top, right, bottom)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // los bitmaps de hardware no viajan en RemoteViews
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }

    /** Aplica esquinas redondeadas y marco. Si no hay nada que aplicar, devuelve la misma foto. */
    fun frame(ctx: Context, photo: Bitmap, cfg: WidgetConfig, widgetW: Int): Bitmap {
        if (cfg.cornerDp <= 0 && cfg.borderDp <= 0) return photo
        // Escala los dp al tamaño real del bitmap (puede estar reducido respecto al widget).
        val density = ctx.resources.displayMetrics.density
        val reduction = if (cfg.crop) photo.width.toFloat() / widgetW else 1f
        val px = density * min(1f, reduction)
        val radius = cfg.cornerDp * px
        val border = cfg.borderDp * px

        val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (border > 0f) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = border
                color = cfg.borderColor
            }
            val half = border / 2
            rect.inset(half, half)
            val r = max(0f, radius - half)
            canvas.drawRoundRect(rect, r, r, stroke)
        }
        return out
    }
}

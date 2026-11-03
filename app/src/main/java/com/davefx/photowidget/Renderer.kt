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
 * Photo decoding and compositing.
 *
 * To save battery and memory:
 *  - Photos are decoded straight to the widget size (the JPEG/HEIF decoder subsamples
 *    without ever loading the 12-50 MP photo at full resolution).
 *  - A photo is never upscaled, and the longer side is capped at MAX_SIDE px.
 *  - Only called when the launcher requests a photo to draw it (home screen visible).
 *  - Corners and frame are painted into the bitmap once: the launcher just shows a static image.
 */
object Renderer {
    private const val MAX_SIDE = 1080

    /** Widget size in pixels, capped at MAX_SIDE. */
    fun targetSize(ctx: Context, id: Int): Pair<Int, Int> {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val dm = ctx.resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        // Portrait: min width × max height. Landscape: max width × min height.
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
     * Decodes [uri] for a [w]×[h] px widget: cropped to fill it (with the chosen framing) or
     * whole. The decoder subsamples and crops in one pass, so the full photo is never loaded
     * and no intermediate copies are made. Never upscaled beyond the original.
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
            // Visible region of the photo, in original pixels.
            var rw = iw
            var rh = ih
            if (crop) {
                val target = w.toFloat() / h
                if (iw / ih > target) rw = ih * target else rh = iw / target
            }
            val rx = (iw - rw) * ax
            val ry = (ih - rh) * ay
            // Decode scale: cover (crop) or fit (whole), never upscale.
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
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // hardware bitmaps can't be sent through RemoteViews
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }

    /** Applies rounded corners and frame. If there's nothing to apply, returns the same photo. */
    fun frame(ctx: Context, photo: Bitmap, cfg: WidgetConfig, widgetW: Int): Bitmap {
        if (cfg.cornerDp <= 0 && cfg.borderDp <= 0) return photo
        // Scale the dp values to the bitmap's actual size (it may be smaller than the widget).
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

package com.davefx.photowidget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davefx.photowidget.TestUtil.ID
import com.davefx.photowidget.TestUtil.ctx
import com.davefx.photowidget.TestUtil.photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class RendererTest {
    private val big = photo("root/sub/c_4000x3000.jpg")
    private val small = photo("root/sub/deeper/d_300x200.jpg")

    private val CROP = WidgetConfig(fit = WidgetConfig.FIT_CROP)
    private val WHOLE = WidgetConfig(fit = WidgetConfig.FIT_WHOLE)

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.blue(c) < 60
    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60

    @Test fun cropFillsTargetExactly() {
        val b = Renderer.decode(ctx, big, 300, 300, cfg = CROP)
        assertEquals(300, b.width); assertEquals(300, b.height)
        // Centered crop: left half red, right half blue.
        assertTrue(isRed(b.getPixel(10, 150))); assertTrue(isBlue(b.getPixel(290, 150)))
    }

    @Test fun fitKeepsWholePhoto() {
        val b = Renderer.decode(ctx, big, 300, 300, cfg = WHOLE)
        assertEquals(300, b.width); assertEquals(225, b.height)
    }

    @Test fun bigPhotoIsSubsampledNotFullSize() {
        val b = Renderer.decode(ctx, big, 540, 960, cfg = CROP)
        assertTrue("${b.byteCount} bytes", b.byteCount <= 540 * 960 * 4)
    }

    @Test fun neverUpscales() {
        val fit = Renderer.decode(ctx, small, 600, 600, cfg = WHOLE)
        assertEquals(300, fit.width); assertEquals(200, fit.height)
        val crop = Renderer.decode(ctx, small, 600, 600, cfg = CROP)
        assertEquals(200, crop.width); assertEquals(200, crop.height)
    }

    @Test fun softwareBitmapForRemoteViews() {
        val b = Renderer.decode(ctx, big, 200, 200, cfg = CROP)
        assertTrue(b.config != Bitmap.Config.HARDWARE)
    }

    @Test fun noFrameReturnsSameBitmap() {
        val b = Renderer.decode(ctx, big, 200, 200, cfg = CROP)
        assertSame(b, Renderer.frame(ctx, b, WidgetConfig(cornerDp = 0, borderDp = 0), 200))
    }

    @Test fun roundedCornersAreTransparent() {
        val b = Renderer.decode(ctx, big, 300, 300, cfg = CROP)
        val f = Renderer.frame(ctx, b, WidgetConfig(cornerDp = 28, borderDp = 0), 300)
        assertEquals(0, Color.alpha(f.getPixel(0, 0)))
        assertEquals(0, Color.alpha(f.getPixel(299, 299)))
        assertEquals(255, Color.alpha(f.getPixel(150, 150)))
    }

    @Test fun borderIsPaintedWithChosenColor() {
        val b = Renderer.decode(ctx, big, 300, 300, cfg = CROP)
        val f = Renderer.frame(ctx, b, WidgetConfig(cornerDp = 0, borderDp = 8, borderColor = Color.WHITE), 300)
        val px = (8 * ctx.resources.displayMetrics.density / 2).roundToInt()
        val edge = f.getPixel(150, px)
        assertTrue("border: ${Integer.toHexString(edge)}", Color.red(edge) > 230 && Color.green(edge) > 230 && Color.blue(edge) > 230)
        assertTrue(isRed(f.getPixel(100, 150)))
    }

    @Test fun cropAnchorWithoutZoom() {
        // 4:3 photo in a 1:2 widget: cropped horizontally; anchored left, everything is red.
        val b = Renderer.decode(ctx, big, 300, 600, cfg = CROP.copy(anchor = WidgetConfig.ANCHOR_LEFT))
        assertEquals(300, b.width); assertEquals(600, b.height)
        assertTrue(isRed(b.getPixel(5, 300))); assertTrue(isRed(b.getPixel(295, 300)))
    }

    @Test fun cropAnchorRightAndTop() {
        val right = Renderer.decode(ctx, big, 300, 600, cfg = CROP.copy(anchor = WidgetConfig.ANCHOR_RIGHT))
        assertTrue(isBlue(right.getPixel(5, 300))); assertTrue(isBlue(right.getPixel(295, 300)))
        // Landscape photo in a wider landscape widget: cropped vertically; top/bottom must not fail.
        val top = Renderer.decode(ctx, big, 600, 200, cfg = CROP.copy(anchor = WidgetConfig.ANCHOR_TOP))
        assertEquals(600, top.width); assertEquals(200, top.height)
    }

    @Test fun anchorIsIgnoredForWholePhoto() {
        val b = Renderer.decode(ctx, big, 300, 300, cfg = WHOLE.copy(anchor = WidgetConfig.ANCHOR_LEFT))
        assertEquals(300, b.width); assertEquals(225, b.height)
        assertTrue(isRed(b.getPixel(10, 100))); assertTrue(isBlue(b.getPixel(290, 100)))
    }

    @Test fun targetSizeIsCapped() {
        val (w, h) = Renderer.targetSize(ctx, ID) // widget not placed: default size
        assertTrue(w in 1..1080 && h in 1..1080)
    }
}

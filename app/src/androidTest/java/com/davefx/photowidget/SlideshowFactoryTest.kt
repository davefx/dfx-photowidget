package com.davefx.photowidget

import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davefx.photowidget.TestUtil.EXPECTED_RECURSIVE
import com.davefx.photowidget.TestUtil.ID
import com.davefx.photowidget.TestUtil.ctx
import com.davefx.photowidget.TestUtil.tree
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** El único sitio donde se trabaja: lo que hace cuando el launcher pide fotos. */
@RunWith(AndroidJUnit4::class)
class SlideshowFactoryTest {
    private val cfg = WidgetConfig(folders = setOf(tree.toString()), intervalSeconds = 30, cornerDp = 16, borderDp = 2)
    private lateinit var f: SlideshowService.Factory

    @Before fun setUp() {
        Scheduler.cancel(ctx, ID)
        TestUtil.snapshotAlarms()
        Store.save(ctx, ID, cfg)
        f = SlideshowService.Factory(ctx, ID).also { it.onCreate() }
    }

    @After fun clean() { f.onDestroy(); Scheduler.cancel(ctx, ID); Store.delete(ctx, ID) }

    @Test fun firstRequestBuildsIndex() {
        f.onDataSetChanged()
        assertEquals(expectedCount(), f.count)
        val st = Store.loadState(ctx, ID)
        assertEquals(EXPECTED_RECURSIVE.size, st.indexSize)
        assertTrue(st.indexTime > 0)
    }

    @Test fun freshIndexIsNotRescanned() {
        f.onDataSetChanged()
        val before = FakePhotoProvider.queries.get()
        repeat(5) { f.onDataSetChanged() }
        assertEquals("se recorrieron carpetas sin necesidad", before, FakePhotoProvider.queries.get())
    }

    @Test fun staleIndexIsRescanned() {
        f.onDataSetChanged()
        Store.saveState(ctx, ID, Store.loadState(ctx, ID).copy(indexTime = System.currentTimeMillis() - PhotoIndex.MAX_AGE_MS - 1))
        val before = FakePhotoProvider.queries.get()
        f.onDataSetChanged()
        assertTrue(FakePhotoProvider.queries.get() > before)
    }

    @Test fun deletedIndexFileIsRebuilt() {
        f.onDataSetChanged()
        PhotoIndex.file(ctx, ID).delete()
        f.onDataSetChanged()
        assertEquals(expectedCount(), f.count)
    }

    /** Android 15+ solo expone la ventana. */
    private fun expectedCount() =
        if (PhotoWindow.enabled) minOf(EXPECTED_RECURSIVE.size, PhotoWindow.size(ctx, ID)) else EXPECTED_RECURSIVE.size

    @Test fun windowFitsSystemBitmapLimit() {
        // Android 15+ envía todas las fotos del adaptador juntas: deben caber en el límite del sistema.
        f.onDataSetChanged()
        val dm = ctx.resources.displayMetrics
        val total = (0 until f.count).sumOf { f.getViewAt(it).estimateTotalBitmapMemoryUsageCompat() }
        assertTrue("$total bytes", !PhotoWindow.enabled || total < 6L * dm.widthPixels * dm.heightPixels)
    }

    @Test fun windowStartShiftsPhotos() {
        org.junit.Assume.assumeTrue(PhotoWindow.enabled)
        f.onDataSetChanged()
        val uris = PhotoIndex.readAll(ctx, ID)
        Store.saveState(ctx, ID, Store.loadState(ctx, ID).copy(windowStart = uris.size - 1))
        f.onDataSetChanged()
        assertEquals(expectedCount(), f.count)
        // Fuera del índice: vuelve al principio.
        Store.saveState(ctx, ID, Store.loadState(ctx, ID).copy(windowStart = uris.size + 5))
        f.onDataSetChanged()
        assertEquals(expectedCount(), f.count)
    }

    private fun android.widget.RemoteViews.estimateTotalBitmapMemoryUsageCompat(): Long {
        var bytes = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val img = apply(ctx, FrameLayout(ctx)).findViewById<ImageView>(R.id.item_photo)
            bytes = ((img.drawable as? BitmapDrawable)?.bitmap?.allocationByteCount ?: 0).toLong()
        }
        return bytes
    }

    @Test fun everyPhotoRendersWithinMemoryBudget() {
        f.onDataSetChanged()
        val inst = InstrumentationRegistry.getInstrumentation()
        for (i in 0 until f.count) {
            val rv = f.getViewAt(i)
            inst.runOnMainSync {
                val v = rv.apply(ctx, FrameLayout(ctx))
                val img = v.findViewById<ImageView>(R.id.item_photo)
                val bmp = (img.drawable as? BitmapDrawable)?.bitmap
                assertNotNull("posición $i sin foto", bmp)
                assertTrue("posición $i: ${bmp!!.byteCount} bytes", bmp.byteCount in 1..2_000_000)
            }
        }
    }

    @Test fun outOfRangeIsHarmless() {
        f.onDataSetChanged()
        assertNotNull(f.getViewAt(f.count + 10))
        assertNotNull(f.loadingView)
    }

    @Test fun singlePhotoCancelsDailyAlarm() {
        val one = WidgetConfig(photos = setOf(TestUtil.photo("root/a_800x600.jpg").toString()), intervalSeconds = 30)
        Store.save(ctx, ID, one)
        Scheduler.schedule(ctx, ID, one, -1)
        f.onDataSetChanged()
        assertEquals(1, f.count)
        assertEquals(emptyList<TestUtil.Alarm>(), TestUtil.pendingAlarms())
    }
}

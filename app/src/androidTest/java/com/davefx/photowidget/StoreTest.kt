package com.davefx.photowidget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davefx.photowidget.TestUtil.ID
import com.davefx.photowidget.TestUtil.ID2
import com.davefx.photowidget.TestUtil.ctx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreTest {
    @After fun clean() { Store.delete(ctx, ID); Store.delete(ctx, ID2) }

    @Test fun configRoundTrip() {
        val c = WidgetConfig(
            folders = setOf("content://x/tree/1"), photos = setOf("content://x/doc/2"),
            includeSubfolders = false, intervalSeconds = 30, fit = WidgetConfig.FIT_WHOLE, anchor = WidgetConfig.ANCHOR_TOP, cornerDp = 28,
            borderDp = 4, borderColor = 0xFF000000.toInt(), pauseOnBatterySaver = false,
        )
        assertFalse(Store.isConfigured(ctx, ID))
        Store.save(ctx, ID, c)
        assertTrue(Store.isConfigured(ctx, ID))
        assertEquals(c, Store.load(ctx, ID))
        assertTrue(ID in Store.configuredIds(ctx))
    }

    @Test fun legacyCropSettingIsMigrated() {
        ctx.getSharedPreferences("widgets", android.content.Context.MODE_PRIVATE).edit()
            .putInt("$ID.intervalSec", 3600).putBoolean("$ID.crop", false).commit()
        assertEquals(WidgetConfig.FIT_WHOLE, Store.load(ctx, ID).fit)
        ctx.getSharedPreferences("widgets", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("$ID.crop", true).commit()
        assertEquals(WidgetConfig.FIT_CROP, Store.load(ctx, ID).fit)
    }

    @Test fun defaultsWhenMissing() = assertEquals(WidgetConfig(), Store.load(ctx, ID))

    @Test fun stateRoundTrip() {
        val s = WidgetState("content://a", 123L, 7, 456L, 42)
        Store.saveState(ctx, ID, s)
        assertEquals(s, Store.loadState(ctx, ID))
    }

    @Test fun saveInvalidatesIndex() {
        Store.saveState(ctx, ID, WidgetState(indexTime = 999L, indexSize = 5))
        Store.save(ctx, ID, WidgetConfig(photos = setOf("p")))
        assertEquals(0L, Store.loadState(ctx, ID).indexTime)
    }

    @Test fun deleteRemovesOnlyThatWidget() {
        Store.save(ctx, ID, WidgetConfig(photos = setOf("p")))
        Store.save(ctx, ID2, WidgetConfig(photos = setOf("q")))
        PhotoIndex.file(ctx, ID).writeText("x\n")
        Store.delete(ctx, ID)
        assertFalse(Store.isConfigured(ctx, ID))
        assertFalse(PhotoIndex.file(ctx, ID).exists())
        assertFalse(ID in Store.configuredIds(ctx))
        assertEquals(setOf("q"), Store.load(ctx, ID2).photos)
    }

    @Test fun slideshowThreshold() {
        assertFalse(WidgetConfig(intervalSeconds = 0).slideshow)
        assertTrue(WidgetConfig(intervalSeconds = 30).slideshow)
        assertTrue(WidgetConfig(intervalSeconds = 300).slideshow)
        assertFalse(WidgetConfig(intervalSeconds = 900).slideshow)
        assertFalse(WidgetConfig(intervalSeconds = 86400).slideshow)
    }

    @Test fun intervalOptionsAreSeconds() {
        // Regression: the array used to be in minutes, and "30 seconds" was saved as 15.
        val labels = ctx.resources.getStringArray(R.array.interval_labels)
        assertEquals(labels.size, ConfigActivity.INTERVALS.size)
        assertEquals(0, ConfigActivity.INTERVALS[0])
        assertEquals(30, ConfigActivity.INTERVALS[1])
        assertEquals(86400, ConfigActivity.INTERVALS.last())
        assertTrue(ConfigActivity.INTERVALS.toList().zipWithNext().all { (a, b) -> a < b })
    }
}

package com.davefx.photowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davefx.photowidget.TestUtil.ID
import com.davefx.photowidget.TestUtil.ctx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Comprueba en el AlarmManager real que las alarmas nunca despiertan el teléfono. */
@RunWith(AndroidJUnit4::class)
class SchedulerTest {
    private val photos = setOf("content://a/1", "content://a/2", "content://a/3")

    @Before fun setUp() { Scheduler.cancel(ctx, ID); TestUtil.snapshotAlarms() }
    @After fun clean() = Scheduler.cancel(ctx, ID)

    private fun pending(): PendingIntent? = PendingIntent.getBroadcast(
        ctx, ID,
        Intent(ctx, PhotoWidgetProvider::class.java).setAction(Scheduler.ACTION_ALARM)
            .setData(Uri.parse("dfxphoto://widget/$ID")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ID),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )

    private fun alarms() = TestUtil.pendingAlarms()

    @Test fun longIntervalUsesNonWakeupRepeatingAlarm() {
        Scheduler.schedule(ctx, ID, WidgetConfig(photos = photos, intervalSeconds = 3600), 3)
        assertNotNull(pending())
        val a = alarms()
        assertEquals(1, a.size)
        assertFalse("alarma despertadora: ${a[0]}", a[0].wakeup)
        assertEquals(3_600_000L, a[0].interval)
    }

    @Test fun slideshowOnlyNeedsDailyReshuffleOrWindowAdvance() {
        val cfg = WidgetConfig(photos = photos, intervalSeconds = 30)
        Scheduler.schedule(ctx, ID, cfg, 3)
        val a = alarms()
        assertEquals(1, a.size)
        assertFalse(a[0].wakeup)
        // Android 15+: cuando el launcher ha mostrado toda la ventana, para pasar a la siguiente.
        val expected = if (PhotoWindow.enabled) PhotoWindow.advanceIntervalMs(ctx, ID, cfg) else 86_400_000L
        assertEquals(expected, a[0].interval)
        assertTrue(expected >= 60_000L)
    }

    @Test fun tapOnlyHasNoAlarm() {
        Scheduler.schedule(ctx, ID, WidgetConfig(photos = photos, intervalSeconds = 0), 3)
        assertNull(pending()); assertTrue(alarms().isEmpty())
    }

    @Test fun singlePhotoHasNoAlarm() {
        Scheduler.schedule(ctx, ID, WidgetConfig(photos = setOf("content://a/1"), intervalSeconds = 3600), 1)
        assertNull(pending()); assertTrue(alarms().isEmpty())
    }

    @Test fun noSourcesHasNoAlarm() {
        Scheduler.schedule(ctx, ID, WidgetConfig(intervalSeconds = 3600), -1)
        assertNull(pending())
    }

    @Test fun unknownCountStillSchedules() {
        Scheduler.schedule(ctx, ID, WidgetConfig(folders = setOf("content://t"), intervalSeconds = 3600), -1)
        assertNotNull(pending())
    }

    @Test fun cancelRemovesAlarm() {
        Scheduler.schedule(ctx, ID, WidgetConfig(photos = photos, intervalSeconds = 3600), 3)
        Scheduler.cancel(ctx, ID)
        assertNull(pending()); assertTrue(alarms().isEmpty())
    }

    @Test fun rescheduleReplacesInsteadOfDuplicating() {
        repeat(3) { Scheduler.schedule(ctx, ID, WidgetConfig(photos = photos, intervalSeconds = 3600), 3) }
        assertEquals(1, alarms().size)
    }
}

package com.davefx.photowidget

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davefx.photowidget.TestUtil.ID
import com.davefx.photowidget.TestUtil.ctx
import com.davefx.photowidget.TestUtil.shell
import com.davefx.photowidget.WidgetUpdater.Trigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * State machine for photo changes. The widget is not placed, so AppWidgetManager calls have no
 * effect: only what it decides and what it saves are checked.
 */
@RunWith(AndroidJUnit4::class)
class WidgetUpdaterTest {
    private val photos = setOf("content://a/1", "content://a/2", "content://a/3")
    private val hourly = WidgetConfig(photos = photos, intervalSeconds = 3600)
    private val old = System.currentTimeMillis() - 10 * 3600_000L

    @Before fun setUp() {
        Scheduler.cancel(ctx, ID)
        TestUtil.snapshotAlarms()
        Store.save(ctx, ID, hourly)
        Store.saveState(ctx, ID, WidgetState(position = 0, indexSize = 3, indexTime = 1L, lastChange = old))
    }

    @After fun clean() {
        Scheduler.cancel(ctx, ID)
        Store.delete(ctx, ID)
    }

    private fun state() = Store.loadState(ctx, ID)

    @Test fun alarmAdvancesOnePosition() {
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(1, state().position)
    }

    @Test fun alarmRightAfterAChangeIsIgnored() {
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(1, state().position)
    }

    @Test fun tapAlwaysAdvancesInManualMode() {
        WidgetUpdater.handle(ctx, ID, Trigger.TAP)
        WidgetUpdater.handle(ctx, ID, Trigger.TAP)
        assertEquals(2, state().position)
    }

    @Test fun fullCycleWrapsAndMarksIndexForReshuffle() {
        Store.saveState(ctx, ID, state().copy(position = 2))
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(0, state().position)
        assertEquals(0L, state().indexTime)
    }

    @Test fun singlePhotoCancelsAlarmAndDoesNothing() {
        Scheduler.schedule(ctx, ID, hourly, 3)
        Store.saveState(ctx, ID, state().copy(indexSize = 1))
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(0, state().position)
        assertEquals(emptyList<TestUtil.Alarm>(), TestUtil.pendingAlarms())
    }

    @Test fun indexNotBuiltYetDoesNothing() {
        Store.saveState(ctx, ID, state().copy(indexSize = 0))
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(0, state().position)
    }

    @Test fun slideshowAlarmOnlyMarksIndexStale() {
        assumeFalse(PhotoWindow.enabled)
        Store.save(ctx, ID, hourly.copy(intervalSeconds = 30))
        Store.saveState(ctx, ID, state().copy(position = 2, indexTime = 5L))
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(2, state().position)
        assertEquals(0L, state().indexTime)
    }

    // Android 15+: photo window (PhotoWindow).

    private fun windowed(size: Int = 20) {
        assumeTrue(PhotoWindow.enabled)
        assumeTrue("screen must be on", ctx.getSystemService(PowerManager::class.java).isInteractive)
        Store.saveState(ctx, ID, state().copy(indexSize = size, indexTime = System.currentTimeMillis()))
    }

    @Test fun stepInsideWindowKeepsWindow() {
        windowed()
        WidgetUpdater.handle(ctx, ID, Trigger.TAP)
        assertEquals(1, state().position)
        assertEquals(0, state().windowStart)
    }

    @Test fun stepPastWindowMovesWindow() {
        windowed()
        val k = PhotoWindow.size(ctx, ID)
        Store.saveState(ctx, ID, state().copy(position = k - 1))
        WidgetUpdater.handle(ctx, ID, Trigger.TAP)
        assertEquals(k, state().position)
        assertEquals(k, state().windowStart)
    }

    @Test fun slideshowAlarmAdvancesWindowAndWraps() {
        windowed()
        Store.save(ctx, ID, hourly.copy(intervalSeconds = 30))
        Store.saveState(ctx, ID, state().copy(indexSize = 20))
        val k = PhotoWindow.size(ctx, ID)
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(k, state().windowStart)
        Store.saveState(ctx, ID, state().copy(windowStart = 20 - 1))
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(0, state().windowStart)
        assertEquals("full cycle: reshuffle", 0L, state().indexTime)
    }

    @Test fun tapOnMiddlePhotoKeepsWindow() {
        windowed()
        Store.save(ctx, ID, hourly.copy(intervalSeconds = 30))
        WidgetUpdater.handle(ctx, ID, Trigger.TAP, shown = 0)
        assertEquals(0, state().windowStart)
    }

    @Test fun tapOnLastPhotoLoadsNextWindowAndWraps() {
        windowed()
        Store.save(ctx, ID, hourly.copy(intervalSeconds = 30))
        val k = PhotoWindow.size(ctx, ID)
        WidgetUpdater.handle(ctx, ID, Trigger.TAP, shown = k - 1)
        assertEquals(k, state().windowStart)
        Store.saveState(ctx, ID, state().copy(windowStart = 20 - 1))
        WidgetUpdater.handle(ctx, ID, Trigger.TAP, shown = k - 1)
        assertEquals(0, state().windowStart)
        assertEquals("full cycle: reshuffle", 0L, state().indexTime)
    }

    @Test fun tapOnLastPhotoWhenAllFitDoesNotTouchState() {
        windowed(size = 2)
        Store.save(ctx, ID, hourly.copy(intervalSeconds = 30))
        Store.saveState(ctx, ID, state().copy(indexSize = 2))
        val before = state()
        WidgetUpdater.handle(ctx, ID, Trigger.TAP, shown = 1)
        assertEquals(before, state())
    }

    @Test fun slideshowAlarmWithEverythingInWindowDoesNothing() {
        windowed(size = 2)
        Store.save(ctx, ID, hourly.copy(intervalSeconds = 30))
        Store.saveState(ctx, ID, state().copy(indexSize = 2))
        val before = state()
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(before, state())
    }

    @Test fun configuredResetsState() {
        Store.saveState(ctx, ID, state().copy(position = 2, indexSize = 3))
        WidgetUpdater.handle(ctx, ID, Trigger.CONFIGURED)
        assertEquals(WidgetState(), state())
    }

    @Test fun batterySaverPausesAlarmChanges() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        shell("dumpsys battery unplug")
        shell("settings put global low_power 1")
        try {
            Thread.sleep(500)
            assumeTrue("could not enable battery saver", pm.isPowerSaveMode)
            WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
            assertEquals(0, state().position)
            // Tapping still works with battery saver on.
            WidgetUpdater.handle(ctx, ID, Trigger.TAP)
            assertEquals(1, state().position)
            // If the user disables the pause, the alarm does advance.
            Store.save(ctx, ID, hourly.copy(pauseOnBatterySaver = false))
            Store.saveState(ctx, ID, state().copy(lastChange = old))
            WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
            assertNotEquals(1, state().position)
        } finally {
            shell("settings put global low_power 0")
            shell("dumpsys battery reset")
        }
    }

    @Test fun noSourcesCancelsAlarm() {
        Scheduler.schedule(ctx, ID, hourly, 3)
        Store.save(ctx, ID, WidgetConfig())
        WidgetUpdater.handle(ctx, ID, Trigger.ALARM)
        assertEquals(emptyList<TestUtil.Alarm>(), TestUtil.pendingAlarms())
    }
}

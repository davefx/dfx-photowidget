package com.davefx.photowidget

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val a = WidgetConfig(
        folders = setOf("content://com.android.externalstorage.documents/tree/primary%3ADCIM"),
        photos = setOf("content://media/picker/0/1", "content://media/picker/0/2"),
        includeSubfolders = false, intervalSeconds = 30, fit = WidgetConfig.FIT_WHOLE,
        anchor = WidgetConfig.ANCHOR_TOP, cornerDp = 28, borderDp = 4,
        borderColor = 0xFFF5ECD7.toInt(), pauseOnBatterySaver = false,
    )

    @Test fun roundTripKeepsEverySetting() {
        val configs = listOf(a, WidgetConfig(photos = setOf("content://x/1")))
        assertEquals(configs, Backup.fromJson(Backup.toJson(configs)))
    }

    @Test fun emptyBackup() {
        assertEquals(emptyList<WidgetConfig>(), Backup.fromJson(Backup.toJson(emptyList())))
    }

    @Test fun missingFieldsTakeDefaults() {
        val got = Backup.fromJson("""{"format":"dfx-photowidget-backup","version":1,"widgets":[{"photos":["content://x/1"]}]}""")
        assertEquals(listOf(WidgetConfig(photos = setOf("content://x/1"))), got)
    }

    @Test fun rejectsOtherFiles() {
        assertNull(Backup.fromJson("not json"))
        assertNull(Backup.fromJson("""{"widgets":[]}"""))
        assertNull(Backup.fromJson("""{"format":"dfx-photowidget-backup","widgets":"oops"}"""))
    }
}

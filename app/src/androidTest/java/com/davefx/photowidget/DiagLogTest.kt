package com.davefx.photowidget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davefx.photowidget.TestUtil.ctx
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagLogTest {
    @Test fun reportHasDeviceInfoAndLoggedLines() {
        val marker = "diag-test-${System.nanoTime()}"
        DiagLog.log(ctx, marker)
        val r = DiagLog.report(ctx)
        assertTrue(r.startsWith("DFX Photo Widget"))
        assertTrue("logged line missing", r.contains(marker))
        assertTrue(r.contains("== Recent logcat (this app) =="))
    }

    @Test fun logStaysBounded() {
        val line = "x".repeat(1000)
        repeat(700) { DiagLog.log(ctx, line) }
        val f = java.io.File(ctx.filesDir, "diag.log")
        assertTrue("log is ${f.length()} bytes", f.length() < 600_000)
    }
}

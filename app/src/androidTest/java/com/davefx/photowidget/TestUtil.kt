package com.davefx.photowidget

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry

object TestUtil {
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Root folder of the fake provider, as the folder picker would return it. */
    val tree: Uri = DocumentsContract.buildTreeDocumentUri(FakePhotoProvider.AUTHORITY, "root")

    /** URI of a fake-provider photo, tree-based (the same way the index generates them). */
    fun photo(path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, path)

    fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    data class Alarm(val header: String, val body: String) {
        val key get() = Regex("Alarm\\{(\\w+)").find(header)?.groupValues?.get(1) ?: header
        val wakeup get() = header.contains("WAKEUP") || Regex("type[ =]0\\b|type[ =]2\\b").containsMatchIn(header + body)
        val interval get() = Regex("repeatInterval=(\\d+)").find(body)?.groupValues?.get(1)?.toLong()
    }

    /**
     * The app's PENDING alarms according to dumpsys (not the stats for already-fired alarms,
     * which Android 14 also lists under the same label).
     */
    fun pendingAlarms(all: Boolean = false): List<Alarm> {
        val out = shell("dumpsys alarm").lines()
        val res = ArrayList<Alarm>()
        var i = 0
        while (i < out.size) {
            val l = out[i]
            if (l.contains("Alarm{") && l.contains(ctx.packageName)) {
                val body = StringBuilder()
                var j = i + 1
                while (j < out.size && !out[j].contains("Alarm{") && out[j].startsWith("      ")) body.appendLine(out[j++])
                if (body.contains(Scheduler.ACTION_ALARM)) res += Alarm(l.trim(), body.toString())
                i = j
            } else i++
        }
        return if (all) res else res.filter { it.key !in baseline }
    }

    /** Pre-existing alarms (e.g. from real widgets on the device) that the tests ignore. */
    private var baseline: Set<String> = emptySet()

    fun snapshotAlarms() { baseline = pendingAlarms(all = true).map { it.key }.toSet() }

    /** Photos the scan must find in the fake tree when including subfolders. */
    val EXPECTED_RECURSIVE: Set<String> = buildSet {
        add("root/a_800x600.jpg")
        add("root/b_600x800.png")
        add("root/sub/c_4000x3000.jpg")
        add("root/sub/deeper/d_300x200.jpg")
        var p = "root"
        for (i in 1..6) { // max depth 6
            p += "/l$i"
            add("$p/deep${i}_200x200.jpg")
        }
    }.map { photo(it).toString() }.toSet()

    val EXPECTED_FLAT: Set<String> =
        setOf("root/a_800x600.jpg", "root/b_600x800.png").map { photo(it).toString() }.toSet()

    /** Fake widget IDs (not placed on any launcher), far from real ones. */
    const val ID = 900_001
    const val ID2 = 900_002
}

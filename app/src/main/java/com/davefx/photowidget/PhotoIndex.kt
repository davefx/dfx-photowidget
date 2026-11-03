package com.davefx.photowidget

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * A widget's shuffled photo list, stored on disk (one URI per line).
 *
 * To save battery:
 *  - Folders are only scanned when the configuration changes or, at most, once a day,
 *    and always piggybacking on a photo change (never on our own initiative).
 *  - Advancing to the next photo reads the file line by line up to the requested position,
 *    without loading the whole list into memory.
 *  - The list is shuffled once: it is walked through entirely before any photo repeats.
 */
object PhotoIndex {
    private const val TAG = "PhotoIndex"
    private const val MAX_PHOTOS = 20_000
    private const val MAX_DEPTH = 6
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun file(ctx: Context, id: Int) = File(ctx.filesDir, "index_$id.txt")

    /** Scans the sources and writes a shuffled index. Returns the number of photos. */
    fun rebuild(ctx: Context, id: Int, cfg: WidgetConfig, avoidFirst: String? = null): Int {
        val all = LinkedHashSet<String>()
        all.addAll(cfg.photos)
        for (tree in cfg.folders) {
            if (all.size >= MAX_PHOTOS) break
            try {
                val treeUri = Uri.parse(tree)
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                scan(ctx, treeUri, rootId, cfg.includeSubfolders, 0, all)
            } catch (e: Exception) {
                Log.w(TAG, "Can't read folder $tree", e)
            }
        }
        val list = all.toMutableList().apply { shuffle() }
        // When reshuffling, avoid starting with the photo currently on display.
        if (list.size > 1 && list[0] == avoidFirst) {
            list[0] = list[1].also { list[1] = list[0] }
        }
        // Atomic write: the slideshow may be reading the index at the same time.
        val tmp = File(ctx.filesDir, "index_$id.tmp")
        tmp.bufferedWriter().use { w -> list.forEach { w.write(it); w.newLine() } }
        tmp.renameTo(file(ctx, id))
        return list.size
    }

    private fun scan(
        ctx: Context, treeUri: Uri, parentId: String, recursive: Boolean, depth: Int,
        out: MutableSet<String>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val subdirs = ArrayList<String>()
        ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX_PHOTOS) {
                val docId = c.getString(0) ?: continue
                val mime = c.getString(1) ?: continue
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> subdirs += docId
                    mime.startsWith("image/") && mime != "image/gif" ->
                        out += DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                }
            }
        }
        if (recursive && depth < MAX_DEPTH) {
            for (d in subdirs) {
                if (out.size >= MAX_PHOTOS) return
                // Skip hidden folders (.thumbnails, .trash...), which are full of thumbnails.
                if (d.substringAfterLast('/').startsWith('.')) continue
                scan(ctx, treeUri, d, true, depth + 1, out)
            }
        }
    }

    /** Full index (used by the slideshow, which needs random access). */
    fun readAll(ctx: Context, id: Int): List<String> {
        val f = file(ctx, id)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /** URI at the given position, or null if the index doesn't exist or is shorter. */
    fun at(ctx: Context, id: Int, position: Int): String? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        f.bufferedReader().use { r ->
            var i = 0
            while (true) {
                val line = r.readLine() ?: return null
                if (i == position) return line
                i++
            }
        }
    }
}

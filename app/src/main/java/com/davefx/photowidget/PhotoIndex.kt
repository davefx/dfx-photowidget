package com.davefx.photowidget

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Lista barajada de fotos de un widget, guardada en disco (una URI por línea).
 *
 * Para ahorrar batería:
 *  - Las carpetas solo se recorren cuando cambia la configuración o, como mucho, una vez al día,
 *    y siempre aprovechando un cambio de foto (nunca por iniciativa propia).
 *  - Avanzar a la siguiente foto lee el fichero línea a línea hasta la posición pedida,
 *    sin cargar la lista entera en memoria.
 *  - La lista se baraja una vez: se recorre entera antes de repetir ninguna foto.
 */
object PhotoIndex {
    private const val TAG = "PhotoIndex"
    private const val MAX_PHOTOS = 20_000
    private const val MAX_DEPTH = 6
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun file(ctx: Context, id: Int) = File(ctx.filesDir, "index_$id.txt")

    /** Recorre las fuentes y escribe un índice barajado. Devuelve el número de fotos. */
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
                Log.w(TAG, "No se puede leer la carpeta $tree", e)
            }
        }
        val list = all.toMutableList().apply { shuffle() }
        // Evita que, al rebarajar, salga la misma foto que se está mostrando.
        if (list.size > 1 && list[0] == avoidFirst) {
            list[0] = list[1].also { list[1] = list[0] }
        }
        // Escritura atómica: el pase de diapositivas puede estar leyendo el índice a la vez.
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
                // Ignora carpetas ocultas (.thumbnails, .trash...), llenas de miniaturas.
                if (d.substringAfterLast('/').startsWith('.')) continue
                scan(ctx, treeUri, d, true, depth + 1, out)
            }
        }
    }

    /** Índice completo (lo usa el pase de diapositivas, que necesita acceso aleatorio). */
    fun readAll(ctx: Context, id: Int): List<String> {
        val f = file(ctx, id)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /** URI en la posición dada, o null si el índice no existe o es más corto. */
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

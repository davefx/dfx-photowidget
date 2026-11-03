package com.davefx.photowidget

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proveedor de documentos falso, SOLO en la variante debug, para los tests instrumentados.
 *
 * Simula una carpeta de fotos con subcarpetas, carpetas ocultas, GIF y ficheros que no son fotos.
 * Los ID de documento son rutas («root/sub/d.jpg»), igual que en el almacenamiento real.
 * Las fotos se generan al vuelo: nombre «w x h» → JPEG de ese tamaño, mitad izquierda roja y
 * mitad derecha azul, para poder comprobar recortes.
 */
class FakePhotoProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.davefx.photowidget.fake"
        /** Consultas recibidas: permite comprobar que no se recorren carpetas sin necesidad. */
        val queries = AtomicInteger()

        /** Árbol: ruta → es carpeta. */
        val TREE: List<String> = buildList {
            add("root/")
            add("root/a_800x600.jpg")
            add("root/b_600x800.png")
            add("root/anim_100x100.gif")
            add("root/notes.txt")
            add("root/sub/")
            add("root/sub/c_4000x3000.jpg")
            add("root/sub/deeper/")
            add("root/sub/deeper/d_300x200.jpg")
            add("root/.thumbnails/")
            add("root/.thumbnails/t_100x100.jpg")
            // Cadena de 8 niveles: el escaneo se detiene en la profundidad 6.
            var p = "root"
            for (i in 1..8) {
                p += "/l$i"
                add("$p/")
                add("$p/deep${i}_200x200.jpg")
            }
        }

        private fun isDir(path: String) = TREE.contains("$path/")
        private fun exists(path: String) = isDir(path) || TREE.contains(path)
        private fun mime(path: String) = when {
            isDir(path) -> Document.MIME_TYPE_DIR
            path.endsWith(".jpg") -> "image/jpeg"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            else -> "text/plain"
        }

        private val DOC_COLS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "fake").add(Root.COLUMN_DOCUMENT_ID, "root")
                .add(Root.COLUMN_TITLE, "Fake").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
        }

    private fun addRow(c: MatrixCursor, path: String) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, path).add(Document.COLUMN_MIME_TYPE, mime(path))
            .add(Document.COLUMN_DISPLAY_NAME, path.substringAfterLast('/'))
            .add(Document.COLUMN_FLAGS, 0).add(Document.COLUMN_SIZE, null)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (!exists(documentId)) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: DOC_COLS).also { addRow(it, documentId) }
    }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        queries.incrementAndGet()
        val c = MatrixCursor(projection ?: DOC_COLS)
        val prefix = "$parentId/"
        for (entry in TREE) {
            val path = entry.removeSuffix("/")
            if (path.startsWith(prefix) && !path.removePrefix(prefix).contains('/')) addRow(c, path)
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (!exists(documentId) || isDir(documentId)) throw FileNotFoundException(documentId)
        val name = documentId.substringAfterLast('/')
        val dims = Regex("_(\\d+)x(\\d+)\\.").find(name) ?: throw FileNotFoundException(name)
        val f = File(context!!.cacheDir, "fake_" + documentId.replace('/', '_'))
        if (!f.exists()) {
            val w = dims.groupValues[1].toInt()
            val h = dims.groupValues[2].toInt()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(b).apply {
                drawColor(Color.BLUE)
                drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
            }
            val fmt = if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            f.outputStream().use { b.compress(fmt, 90, it) }
            b.recycle()
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

package com.davefx.photowidget

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Manages the persisted permissions on the chosen folders and photos. */
object Grants {
    fun take(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        false
    }

    /** Releases permissions no longer used by any widget. */
    fun releaseUnused(ctx: Context) {
        val used = HashSet<String>()
        for (id in Store.configuredIds(ctx)) {
            val c = Store.load(ctx, id)
            used += c.folders
            used += c.photos
        }
        for (p in ctx.contentResolver.persistedUriPermissions) {
            if (p.uri.toString() !in used) {
                try {
                    ctx.contentResolver.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
            }
        }
    }
}

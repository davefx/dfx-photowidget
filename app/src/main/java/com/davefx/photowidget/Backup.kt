package com.davefx.photowidget

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Settings backup: a JSON file with the configuration of every widget, written and read through
 * the system file picker (no storage permission needed).
 *
 * Folder and photo URIs are stored as they are, but the permissions on them aren't part of the
 * backup: after reinstalling or on another phone, folders have to be allowed again (the
 * configuration screen asks for each one) and individually picked photos have to be picked again.
 */
object Backup {
    const val MIME = "application/json"
    const val FILE_NAME = "dfx-photowidget-backup.json"
    private const val FORMAT = "dfx-photowidget-backup"
    private const val VERSION = 1

    fun toJson(configs: List<WidgetConfig>): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("widgets", JSONArray(configs.map { c ->
            JSONObject()
                .put("folders", JSONArray(c.folders.sorted()))
                .put("photos", JSONArray(c.photos.sorted()))
                .put("includeSubfolders", c.includeSubfolders)
                .put("intervalSeconds", c.intervalSeconds)
                .put("fit", c.fit)
                .put("anchor", c.anchor)
                .put("cornerDp", c.cornerDp)
                .put("borderDp", c.borderDp)
                .put("borderColor", c.borderColor)
                .put("pauseOnBatterySaver", c.pauseOnBatterySaver)
        }))
        .toString(2)

    /** The widgets in the backup, or null if the text isn't a backup of this app. */
    fun fromJson(text: String): List<WidgetConfig>? = try {
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) {
            null
        } else {
            val d = WidgetConfig()
            val list = root.getJSONArray("widgets")
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                WidgetConfig(
                    folders = strings(o.optJSONArray("folders")),
                    photos = strings(o.optJSONArray("photos")),
                    includeSubfolders = o.optBoolean("includeSubfolders", d.includeSubfolders),
                    intervalSeconds = o.optInt("intervalSeconds", d.intervalSeconds).coerceAtLeast(0),
                    fit = o.optInt("fit", d.fit),
                    anchor = o.optInt("anchor", d.anchor),
                    cornerDp = o.optInt("cornerDp", d.cornerDp),
                    borderDp = o.optInt("borderDp", d.borderDp),
                    borderColor = o.optInt("borderColor", d.borderColor),
                    pauseOnBatterySaver = o.optBoolean("pauseOnBatterySaver", d.pauseOnBatterySaver),
                )
            }
        }
    } catch (e: JSONException) {
        null
    }

    private fun strings(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()
}

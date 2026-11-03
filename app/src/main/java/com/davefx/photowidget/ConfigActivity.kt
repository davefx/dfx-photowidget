package com.davefx.photowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * Widget configuration (when placing it, or when reconfiguring via long press on Android 12+).
 * When opened from the app icon, it lists the existing widgets so they can also be edited
 * on Android 10 and 11, which don't support reconfiguring from the home screen.
 */
class ConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var cfg: WidgetConfig
    private lateinit var sourcesBox: LinearLayout

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && Grants.take(this, uri)) {
            cfg = cfg.copy(folders = cfg.folders + uri.toString())
            refreshSources()
        }
    }

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val granted = uris.filter { Grants.take(this, it) }.map { it.toString() }
        if (granted.size < uris.size) {
            Toast.makeText(this, getString(R.string.some_photos_failed, uris.size - granted.size), Toast.LENGTH_LONG).show()
        }
        if (granted.isNotEmpty()) {
            cfg = cfg.copy(photos = cfg.photos + granted)
            refreshSources()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // If the user leaves without saving, the launcher discards the newly placed widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) showConfigScreen()
    }

    override fun onResume() {
        super.onResume()
        // App screen: rebuilt on return so newly added widgets show up.
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) showLauncherScreen()
    }

    // ---------- Start screen (app icon) ----------

    private fun showLauncherScreen() {
        val col = column()
        col.addView(title(getString(R.string.app_name)))
        col.addView(text(getString(R.string.launcher_intro)))

        val ids = PhotoWidgetProvider.allIds(this)
        if (ids.isNotEmpty()) {
            col.addView(header(getString(R.string.your_widgets)))
            ids.forEachIndexed { i, id ->
                val c = Store.load(this, id)
                val label = getString(R.string.widget_n, i + 1, describeSources(c))
                col.addView(button(label) {
                    startActivity(Intent(this, ConfigActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                })
            }
        }
        val awm = AppWidgetManager.getInstance(this)
        if (awm.isRequestPinAppWidgetSupported) {
            col.addView(button(getString(R.string.add_widget)) {
                awm.requestPinAppWidget(ComponentName(this, PhotoWidgetProvider::class.java), null, pinnedCallback())
            })
        }
        setScreen(col)
    }

    /**
     * When a widget is placed from the app, the launcher doesn't open the configuration: this callback does.
     * The system adds the new widget's ID to the intent, which is why it has to be mutable.
     */
    private fun pinnedCallback(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, ConfigActivity::class.java).setData(Uri.parse("dfxphoto://pinned")),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
    )

    // ---------- Widget configuration ----------

    private fun showConfigScreen() {
        cfg = Store.load(this, widgetId)
        val col = column()

        col.addView(header(getString(R.string.section_photos)))
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sourcesBox)
        refreshSources()
        col.addView(row(
            button(getString(R.string.add_folder)) { pickFolder.launch(DCIM_URI) },
            button(getString(R.string.pick_photos)) {
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        ))
        col.addView(check(getString(R.string.include_subfolders), cfg.includeSubfolders) {
            cfg = cfg.copy(includeSubfolders = it)
        })

        col.addView(header(getString(R.string.section_change)))
        val intervalLabels = resources.getStringArray(R.array.interval_labels)
        col.addView(spinner(intervalLabels, INTERVALS.indexOf(cfg.intervalSeconds).coerceAtLeast(0)) {
            cfg = cfg.copy(intervalSeconds = INTERVALS[it])
        })
        col.addView(check(getString(R.string.pause_battery_saver), cfg.pauseOnBatterySaver) {
            cfg = cfg.copy(pauseOnBatterySaver = it)
        })
        col.addView(hint(getString(R.string.battery_note)))

        col.addView(header(getString(R.string.section_look)))
        col.addView(label(getString(R.string.fit_label)))
        col.addView(spinner(resources.getStringArray(R.array.fit_labels), cfg.fit.coerceIn(0, 1)) {
            cfg = cfg.copy(fit = it)
        })
        col.addView(label(getString(R.string.anchor_label)))
        col.addView(spinner(resources.getStringArray(R.array.anchor_labels), cfg.anchor.coerceIn(0, 4)) {
            cfg = cfg.copy(anchor = it)
        })
        col.addView(label(getString(R.string.corners_label)))
        col.addView(spinner(resources.getStringArray(R.array.corner_labels), nearest(CORNERS, cfg.cornerDp)) {
            cfg = cfg.copy(cornerDp = CORNERS[it])
        })
        col.addView(label(getString(R.string.border_label)))
        col.addView(spinner(resources.getStringArray(R.array.border_labels), nearest(BORDERS, cfg.borderDp)) {
            cfg = cfg.copy(borderDp = BORDERS[it])
        })
        col.addView(label(getString(R.string.border_color_label)))
        col.addView(spinner(resources.getStringArray(R.array.border_color_labels),
            BORDER_COLORS.indexOf(cfg.borderColor).coerceAtLeast(0)) {
            cfg = cfg.copy(borderColor = BORDER_COLORS[it])
        })

        col.addView(button(getString(R.string.save)) { save() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        setScreen(col)
    }

    private fun refreshSources() {
        sourcesBox.removeAllViews()
        if (!cfg.hasSources) {
            sourcesBox.addView(hint(getString(R.string.no_sources)))
            return
        }
        for (f in cfg.folders.sorted()) {
            sourcesBox.addView(sourceRow("📁  " + folderName(f)) {
                cfg = cfg.copy(folders = cfg.folders - f)
                refreshSources()
            })
        }
        if (cfg.photos.isNotEmpty()) {
            sourcesBox.addView(sourceRow("🖼  " + resources.getQuantityString(R.plurals.n_photos, cfg.photos.size, cfg.photos.size)) {
                cfg = cfg.copy(photos = emptySet())
                refreshSources()
            })
        }
    }

    private fun save() {
        if (!cfg.hasSources) {
            Toast.makeText(this, R.string.no_sources, Toast.LENGTH_SHORT).show()
            return
        }
        Store.save(this, widgetId, cfg)
        Grants.releaseUnused(this)
        PhotoWidgetProvider.notifyConfigured(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun describeSources(c: WidgetConfig): String {
        val parts = c.folders.map { folderName(it) }.toMutableList()
        if (c.photos.isNotEmpty()) parts += resources.getQuantityString(R.plurals.n_photos, c.photos.size, c.photos.size)
        return if (parts.isEmpty()) getString(R.string.not_configured) else parts.joinToString(", ")
    }

    private fun folderName(tree: String): String = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfterLast(':').ifEmpty { "/" }
    } catch (e: Exception) {
        tree
    }

    // ---------- UI building ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(24))
    }

    private fun setScreen(content: View) {
        val scroll = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 24f; setTypeface(typeface, Typeface.BOLD); layoutParams = lp()
    }

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 18f; setTypeface(typeface, Typeface.BOLD); layoutParams = lp(24)
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 14f; layoutParams = lp(12) }

    private fun text(s: String) = TextView(this).apply { text = s; textSize = 15f; layoutParams = lp(12) }

    private fun hint(s: String) = TextView(this).apply {
        text = s; textSize = 13f; alpha = 0.7f; layoutParams = lp(8)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; layoutParams = lp(8); setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(4)
        views.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) }
            addView(v)
        }
    }

    private fun sourceRow(s: String, onRemove: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(4)
        addView(TextView(context).apply {
            text = s; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        addView(ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = getString(R.string.remove)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { onRemove() }
        })
    }

    private fun check(s: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = s; isChecked = value; layoutParams = lp(8)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun spinner(items: Array<String>, selected: Int, onSelect: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected)
        layoutParams = lp(4)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun nearest(options: IntArray, value: Int) =
        options.indices.minByOrNull { kotlin.math.abs(options[it] - value) } ?: 0

    companion object {
        /** The folder picker opens in DCIM, where camera photos usually live. */
        private val DCIM_URI: Uri =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM")

        /**
         * Seconds. 0 = tap only. Up to 5 min: slideshow animated by the launcher.
         * From 15 min: non-wakeup alarm that only says which photo is next.
         */
        val INTERVALS = intArrayOf(0, 30, 60, 120, 300, 900, 1800, 3600, 10800, 21600, 43200, 86400)
        val CORNERS = intArrayOf(0, 8, 16, 28)
        val BORDERS = intArrayOf(0, 2, 4, 8)
        val BORDER_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF9E9E9E.toInt(), 0xFFF5ECD7.toInt(),
        )

        fun pendingIntent(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, ConfigActivity::class.java)
                .setData(Uri.parse("dfxphoto://config/$id"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

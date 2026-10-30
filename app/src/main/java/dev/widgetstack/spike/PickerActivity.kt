package dev.widgetstack.spike

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Two-level widget picker: apps first, then that app's widgets with previews.
 * Returns the chosen provider (component + profile) to the caller.
 */
class PickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_PROFILE = "profile"
    }

    private data class AppGroup(val packageName: String, val label: String, val icon: Drawable?, val widgets: List<AppWidgetProviderInfo>)

    private lateinit var list: ListView
    private lateinit var title: TextView
    private var groups: List<AppGroup> = emptyList()
    private var current: AppGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        list = findViewById(R.id.picker_list)
        title = findViewById(R.id.picker_title)

        val pm = packageManager
        val providers = AppWidgetManager.getInstance(this).installedProviders
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .filter { it.provider.packageName != packageName } // never stack a stack
        groups = providers.groupBy { it.provider.packageName }.map { (pkg, infos) ->
            val app = try { pm.getApplicationInfo(pkg, 0) } catch (t: Throwable) { null }
            AppGroup(
                pkg,
                app?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                app?.let { pm.getApplicationIcon(it) },
                infos.sortedBy { it.loadLabel(pm).lowercase() },
            )
        }.sortedBy { it.label.lowercase() }
        SpikeLog.i("picker", "apps with widgets=${groups.size} providers=${providers.size}")
        showApps()

        onBackPressedDispatcher.addCallback(this) {
            if (current != null) showApps() else { setResult(Activity.RESULT_CANCELED); finish() }
        }
    }

    private fun showApps() {
        current = null
        title.text = "Choose an app"
        list.adapter = object : BaseAdapter() {
            override fun getCount() = groups.size
            override fun getItem(i: Int) = groups[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: LayoutInflater.from(parent.context).inflate(R.layout.row_app, parent, false)
                val g = groups[i]
                v.findViewById<ImageView>(R.id.app_icon).setImageDrawable(g.icon)
                v.findViewById<TextView>(R.id.app_name).text = g.label
                v.findViewById<TextView>(R.id.app_count).text = if (g.widgets.size == 1) "1 widget" else "${g.widgets.size} widgets"
                return v
            }
        }
        list.setOnItemClickListener { _, _, i, _ -> showWidgets(groups[i]) }
    }

    private fun showWidgets(g: AppGroup) {
        current = g
        title.text = g.label
        val pm = packageManager
        val previewWidth = (resources.displayMetrics.widthPixels * 0.4f).toInt()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = g.widgets.size
            override fun getItem(i: Int) = g.widgets[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: LayoutInflater.from(parent.context).inflate(R.layout.row_widget, parent, false)
                val info = g.widgets[i]
                val (cw, ch) = WidgetPreviews.cells(parent.context, info)
                v.findViewById<TextView>(R.id.widget_name).text = info.loadLabel(pm)
                v.findViewById<TextView>(R.id.widget_size).text = "${cw} x ${ch}" + (info.loadDescription(parent.context)?.let { "  ·  $it" } ?: "")
                v.findViewById<ImageView>(R.id.widget_preview).setImageDrawable(WidgetPreviews.load(parent.context, info, previewWidth))
                return v
            }
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val info = g.widgets[i]
            SpikeLog.i("picker", "chose ${info.provider}")
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PROVIDER, info.provider).putExtra(EXTRA_PROFILE, info.profile))
            finish()
        }
    }
}

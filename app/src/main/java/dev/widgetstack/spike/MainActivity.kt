package dev.widgetstack.spike

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private companion object { const val REQ_CONFIGURE = 1002 }

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val mgr by lazy { AppWidgetManager.getInstance(this) }
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        SpikeLog.i("bind", "ACTION_APPWIDGET_BIND result=${res.resultCode} (RESULT_OK=${Activity.RESULT_OK}) pendingId=$pendingId")
        if (res.resultCode == Activity.RESULT_OK) afterBound(pendingId) else discardPending("user cancelled bind dialog")
    }

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) { SpikeLog.i("bind", "picker cancelled"); return@registerForActivityResult }
        @Suppress("DEPRECATION")
        val provider = res.data?.getParcelableExtra<android.content.ComponentName>(PickerActivity.EXTRA_PROVIDER)
        @Suppress("DEPRECATION")
        val profile = res.data?.getParcelableExtra<android.os.UserHandle>(PickerActivity.EXTRA_PROFILE)
        val info = mgr.getInstalledProvidersForProfile(profile).firstOrNull { it.provider == provider }
        if (info == null) { SpikeLog.w("bind", "picked provider $provider not found"); return@registerForActivityResult }
        bind(info)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        SpikeLog.i("ui", "POST_NOTIFICATIONS granted=$it")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)

        findViewById<Button>(R.id.btn_add).setOnClickListener { pickProvider() }
        findViewById<Button>(R.id.btn_mode).setOnClickListener {
            val all = Mode.values()
            StackRepository.setMode(all[(StackRepository.mode().ordinal + 1) % all.size])
            refreshStatus()
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { StackRepository.removeAll(); refreshStatus() }
        findViewById<Button>(R.id.btn_shizuku).setOnClickListener {
            ShizukuBind.grantBind(packageName) { msg -> runOnUiThread { toast(msg); refreshStatus() } }
        }
        findViewById<Button>(R.id.btn_push).setOnClickListener { StackRepository.requestPush("manual re-push") }
        findViewById<Button>(R.id.btn_share).setOnClickListener { shareLog() }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleConfigureIntent()
        SpikeLog.i("ui", "MainActivity created")
    }

    /**
     * When opened as the stack widget's configure activity (launcher long-press
     * settings, or first placement), the launcher expects RESULT_OK carrying the
     * widget id, otherwise it deletes the widget. Set it immediately so leaving
     * with Back still counts as "configured".
     */
    private fun handleConfigureIntent() {
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        SpikeLog.i("ui", "opened as configure/settings for stack id=$id (action=${intent?.action})")
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
    }

    override fun onResume() {
        super.onResume()
        SpikeLog.addListener(logListener)
        refreshStatus(); refreshLog()
        if (StackRepository.ids().isNotEmpty()) StackRepository.startListening()
    }

    override fun onPause() { SpikeLog.removeListener(logListener); super.onPause() }

    // ---- add widget flow: pick -> allocate -> bind -> configure -> add ----

    private fun pickProvider() {
        pickerLauncher.launch(Intent(this, PickerActivity::class.java))
    }

    private fun bind(info: AppWidgetProviderInfo) {
        val id = StackRepository.host.allocateAppWidgetId()
        pendingId = id
        SpikeLog.i("bind", "allocated id=$id for ${info.provider} configure=${info.configure}")
        val allowed = try {
            mgr.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (t: Throwable) {
            SpikeLog.e("bind", "bindAppWidgetIdIfAllowed threw", t); false
        }
        SpikeLog.i("bind", "bindAppWidgetIdIfAllowed=$allowed (true means allow-listed via dialog checkbox or `appwidget grantbind`)")
        if (allowed) {
            afterBound(id)
        } else {
            SpikeLog.i("bind", "launching system ACTION_APPWIDGET_BIND dialog")
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            bindLauncher.launch(intent)
        }
    }

    private fun afterBound(id: Int) {
        val info = mgr.getAppWidgetInfo(id)
        if (info == null) { discardPending("bound but getAppWidgetInfo returned null"); return }
        if (info.configure != null) {
            SpikeLog.i("bind", "provider has configure activity ${info.configure}; launching")
            try {
                StackRepository.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (t: Throwable) {
                SpikeLog.e("bind", "startAppWidgetConfigureActivityForResult failed; adding unconfigured", t)
            }
        }
        finishAdd(id)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIGURE) return
        SpikeLog.i("bind", "configure result=$resultCode pendingId=$pendingId")
        if (resultCode == Activity.RESULT_OK) finishAdd(pendingId) else discardPending("configure cancelled")
    }

    private fun finishAdd(id: Int) {
        StackRepository.add(id)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        refreshStatus()
        toast("Added widget $id. Place the 'Widget Stack' widget on your home screen if you have not yet.")
    }

    private fun discardPending(reason: String) {
        SpikeLog.w("bind", "discarding id=$pendingId: $reason")
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) StackRepository.host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    // ---- UI helpers ------------------------------------------------------

    private fun refreshStatus() {
        val stackIds = mgr.getAppWidgetIds(android.content.ComponentName(this, StackWidgetProvider::class.java))
        val inner = StackRepository.ids().map { id -> mgr.getAppWidgetInfo(id)?.provider?.shortClassName ?: "$id(unbound)" }
        status.text = """
            mode: ${StackRepository.mode()}   page: ${StackRepository.page()}
            stack widgets on launcher: ${stackIds.size}
            inner widgets: ${if (inner.isEmpty()) "none" else inner.joinToString()}
            ${ShizukuBind.status()}
        """.trimIndent()
    }

    private fun refreshLog() {
        log.text = SpikeLog.snapshot()
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shareLog() {
        val f = SpikeLog.logFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share spike log"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

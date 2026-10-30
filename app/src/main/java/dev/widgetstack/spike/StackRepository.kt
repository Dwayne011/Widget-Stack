package dev.widgetstack.spike

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import java.util.concurrent.ConcurrentHashMap

/**
 * RELAY  : ViewFlipper of the inner widgets' own RemoteViews; prev/next buttons.
 * SWIPE  : StackView (flick to page) of the same RemoteViews via RemoteCollectionItems.
 *          Tests whether swipe is usable and whether inner clicks survive being
 *          collection items.
 * BITMAP : ViewFlipper of off-screen renders; the fallback.
 */
enum class Mode { RELAY, SWIPE, BITMAP }

/**
 * Process-wide state: which inner widget ids are in the stack, the host, the
 * live host views, the latest RemoteViews per id, and the display mode.
 *
 * Persistence is deliberately tiny (SharedPreferences) because this is a spike.
 */
object StackRepository {
    private const val PREFS = "stack"
    private const val KEY_IDS = "ids"
    private const val KEY_MODE = "mode"
    private const val KEY_PAGE = "page"
    private const val DEBOUNCE_MS = 150L

    lateinit var appContext: Context
        private set
    lateinit var host: StackHost
        private set

    val views = ConcurrentHashMap<Int, RelayHostView>()
    val latest = ConcurrentHashMap<Int, RemoteViews>()

    private val main = Handler(Looper.getMainLooper())
    private var pushQueued = false
    private var listening = false

    fun init(context: Context) {
        appContext = context.applicationContext
        host = StackHost(appContext)
        val ids = ids()
        SpikeLog.i("repo", "init: stored ids=$ids mode=${mode()} page=${page()}")
        ids.forEach { createViewFor(it) }
        if (ids.isNotEmpty()) startListening()
    }

    // ---- stored config -------------------------------------------------

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ids(): List<Int> = prefs().getString(KEY_IDS, "")!!.split(',').filter { it.isNotBlank() }.map { it.toInt() }

    private fun saveIds(ids: List<Int>) { prefs().edit().putString(KEY_IDS, ids.joinToString(",")).apply() }

    fun mode(): Mode = Mode.valueOf(prefs().getString(KEY_MODE, Mode.RELAY.name)!!)

    fun setMode(m: Mode) {
        prefs().edit().putString(KEY_MODE, m.name).apply()
        SpikeLog.i("repo", "mode -> $m")
        requestPush("mode change")
    }

    fun page(): Int = prefs().getInt(KEY_PAGE, 0)

    fun setPage(p: Int) {
        val n = ids().size
        val clamped = if (n == 0) 0 else ((p % n) + n) % n
        prefs().edit().putInt(KEY_PAGE, clamped).apply()
        SpikeLog.i("repo", "page -> $clamped of $n")
        requestPush("page change")
    }

    // ---- inner widget lifecycle ---------------------------------------

    fun add(appWidgetId: Int) {
        val ids = ids().toMutableList()
        if (appWidgetId !in ids) ids += appWidgetId
        saveIds(ids)
        createViewFor(appWidgetId)
        startListening()
        SpikeLog.i("repo", "added inner id=$appWidgetId -> ids=$ids")
        requestPush("add $appWidgetId")
    }

    fun removeAll() {
        val ids = ids()
        SpikeLog.i("repo", "removing all inner ids=$ids")
        ids.forEach { id ->
            try { host.deleteAppWidgetId(id) } catch (t: Throwable) { SpikeLog.w("repo", "deleteAppWidgetId($id) failed", t) }
        }
        views.clear(); latest.clear()
        saveIds(emptyList()); prefs().edit().putInt(KEY_PAGE, 0).apply()
        stopListening()
        requestPush("clear")
    }

    private fun createViewFor(id: Int) {
        if (views.containsKey(id)) return
        val mgr = AppWidgetManager.getInstance(appContext)
        val info = mgr.getAppWidgetInfo(id)
        if (info == null) {
            SpikeLog.w("repo", "no provider info for id=$id (unbound or provider uninstalled); skipping view")
            return
        }
        try {
            val v = host.createView(appContext, id, info) as RelayHostView
            views[id] = v
            SpikeLog.i("repo", "created host view id=$id provider=${info.provider} min=${info.minWidth}x${info.minHeight} resize=${info.resizeMode}")
            // Tell the provider how big it will be drawn so size-mapped RemoteViews pick the right layout.
            StackRenderer.applyStackSizeTo(v)
        } catch (t: Throwable) {
            SpikeLog.e("repo", "createView failed id=$id", t)
        }
    }

    fun startListening() {
        if (listening) return
        try {
            host.startListening()
            listening = true
            SpikeLog.i("repo", "host.startListening()")
        } catch (t: Throwable) { SpikeLog.e("repo", "startListening failed", t) }
        HostService.ensureRunning(appContext)
    }

    fun stopListening() {
        if (!listening) return
        try { host.stopListening(); SpikeLog.i("repo", "host.stopListening()") } catch (t: Throwable) { SpikeLog.w("repo", "stopListening failed", t) }
        listening = false
    }

    /** Called from [RelayHostView] on every push from an inner provider. */
    fun onInnerUpdate(id: Int, rv: RemoteViews?) {
        if (rv != null) latest[id] = rv
        requestPush("inner update id=$id")
    }

    // ---- publishing ----------------------------------------------------

    /** Debounced: many inner widgets update in bursts right after startListening. */
    fun requestPush(reason: String) {
        SpikeLog.d("repo", "push requested: $reason (queued=$pushQueued)")
        if (pushQueued) return
        pushQueued = true
        main.postDelayed({
            pushQueued = false
            StackRenderer.pushAll(appContext)
        }, DEBOUNCE_MS)
    }
}

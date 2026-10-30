package dev.widgetstack.spike

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViews.RemoteCollectionItems

/**
 * Builds the Stack widget's RemoteViews and pushes it to every placed instance.
 *
 * RELAY mode  : nest each inner widget's own RemoteViews inside our ViewFlipper.
 *               This is the experiment. If AppWidgetService or the launcher
 *               rejects cross-package children, the exception lands here.
 * BITMAP mode : draw each inner AppWidgetHostView off-screen and ship a bitmap.
 *               Known-good fallback, used to compare behaviour.
 */
object StackRenderer {
    private const val S = "render"

    fun pushAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val stackIds = mgr.getAppWidgetIds(ComponentName(context, StackWidgetProvider::class.java))
        SpikeLog.i(S, "pushAll: stack instances=${stackIds.toList()} mode=${StackRepository.mode()} inner=${StackRepository.ids()} latest=${StackRepository.latest.keys}")
        if (stackIds.isEmpty()) return
        stackIds.forEach { pushOne(context, mgr, it) }
    }

    fun pushOne(context: Context, mgr: AppWidgetManager, stackId: Int) {
        val options = mgr.getAppWidgetOptions(stackId)
        val size = sizeOf(options)
        SpikeLog.d(S, "stack id=$stackId options=${describeOptions(options)} -> cell size ${size.width}x${size.height} dp")

        val rv = try {
            build(context, stackId, size)
        } catch (t: Throwable) {
            SpikeLog.e(S, "build() threw for stack id=$stackId; publishing error page", t)
            errorRoot(context, "build failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        try {
            SpikeLog.timed(S, "updateAppWidget(stack=$stackId)") { mgr.updateAppWidget(stackId, rv) }
            SpikeLog.i(S, "updateAppWidget accepted by AppWidgetService for stack id=$stackId. If the launcher shows 'Problem loading widget', the rejection happened on the launcher side: check `adb logcat -s AppWidgetHostView RemoteViews`.")
        } catch (t: Throwable) {
            SpikeLog.e(S, "AppWidgetService REJECTED update for stack id=$stackId (this is the key relay result)", t)
            try { mgr.updateAppWidget(stackId, errorRoot(context, "rejected: ${t.javaClass.simpleName}: ${t.message}")) } catch (t2: Throwable) {
                SpikeLog.e(S, "even the error page was rejected", t2)
            }
        }
    }

    private fun build(context: Context, stackId: Int, size: SizeF): RemoteViews {
        if (StackRepository.mode() == Mode.SWIPE) return buildSwipe(context, stackId)
        val root = RemoteViews(context.packageName, R.layout.widget_stack)
        root.removeAllViews(R.id.flipper)
        val ids = StackRepository.ids()
        val mode = StackRepository.mode()
        val page = StackRepository.page()

        root.setViewVisibility(R.id.empty, if (ids.isEmpty()) View.VISIBLE else View.GONE)
        root.setTextViewText(R.id.page_label, if (ids.isEmpty()) "0/0" else "${page + 1}/${ids.size} ${mode.name.lowercase()}")
        root.setOnClickPendingIntent(R.id.prev, broadcast(context, StackWidgetProvider.ACTION_PREV, stackId))
        root.setOnClickPendingIntent(R.id.next, broadcast(context, StackWidgetProvider.ACTION_NEXT, stackId))
        root.setOnClickPendingIntent(R.id.settings, settingsIntent(context, stackId))

        ids.forEachIndexed { index, innerId ->
            val child = try {
                when (mode) {
                    Mode.RELAY, Mode.SWIPE -> relayPage(innerId, size)
                    Mode.BITMAP -> bitmapPage(context, innerId, size)
                }
            } catch (t: Throwable) {
                SpikeLog.e(S, "page $index (inner id=$innerId, $mode) failed", t)
                errorPage(context, "inner $innerId: ${t.javaClass.simpleName}: ${t.message}")
            }
            SpikeLog.timed(S, "root.addView(flipper, page $index inner=$innerId pkg=${child.`package`})") { root.addView(R.id.flipper, child) }
        }
        if (ids.isNotEmpty()) root.setDisplayedChild(R.id.flipper, page)
        return root
    }

    // ---- SWIPE (StackView) --------------------------------------------

    private fun buildSwipe(context: Context, stackId: Int): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.widget_stack_sv)
        val ids = StackRepository.ids()
        root.setViewVisibility(R.id.empty, if (ids.isEmpty()) View.VISIBLE else View.GONE)
        root.setTextViewText(R.id.page_label, "${ids.size} swipe")
        root.setOnClickPendingIntent(R.id.settings, settingsIntent(context, stackId))

        val builder = RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            // Every inner widget has its own layout, so each page is its own view type.
            .setViewTypeCount(ids.size.coerceAtLeast(1))
        ids.forEachIndexed { index, innerId ->
            val page = try { relayPage(innerId, SizeF(0f, 0f)) } catch (t: Throwable) {
                SpikeLog.e(S, "swipe page $index (inner id=$innerId) failed", t)
                errorPage(context, "inner $innerId: ${t.javaClass.simpleName}: ${t.message}")
            }
            SpikeLog.d(S, "swipe: item $index inner=$innerId pkg=${page.`package`} layout=0x${Integer.toHexString(page.layoutId)}")
            builder.addItem(innerId.toLong(), page)
        }
        SpikeLog.timed(S, "setRemoteAdapter(stack, ${ids.size} items)") { root.setRemoteAdapter(R.id.stack, builder.build()) }
        // Collection items may only fire fill-in intents against this template; this logs any that reach us.
        root.setPendingIntentTemplate(R.id.stack, broadcast(context, StackWidgetProvider.ACTION_TAP, stackId))
        SpikeLog.i(S, "swipe: if inner buttons do nothing in this mode, Android dropped their PendingIntents because they are collection items (expected risk)")
        return root
    }

    private fun settingsIntent(context: Context, stackId: Int): PendingIntent = PendingIntent.getActivity(
        context, stackId,
        Intent(context, MainActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, stackId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---- RELAY ---------------------------------------------------------

    private fun relayPage(innerId: Int, size: SizeF): RemoteViews {
        val latest = StackRepository.latest[innerId]
        if (latest == null) {
            SpikeLog.w(S, "relay: no RemoteViews received yet for inner id=$innerId (host not listening, or provider has not pushed). Showing placeholder.")
            return errorPage(StackRepository.appContext, "waiting for widget $innerId to send its first update")
        }
        SpikeLog.d(S, "relay: inner id=$innerId ${RelayHostView.describe(latest)}")
        // clone(): addView() reconfigures the child as non-root; keep our stored copy pristine.
        return latest.clone()
    }

    // ---- BITMAP --------------------------------------------------------

    private fun bitmapPage(context: Context, innerId: Int, size: SizeF): RemoteViews {
        val view = StackRepository.views[innerId]
            ?: throw IllegalStateException("no host view for inner id=$innerId")
        val density = context.resources.displayMetrics.density
        val w = (size.width * density).toInt().coerceAtLeast(1)
        val h = (size.height * density).toInt().coerceAtLeast(1)

        applyStackSizeTo(view)
        val bmp = SpikeLog.timed(S, "offscreen render inner=$innerId ${w}x${h}px") {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            b
        }
        SpikeLog.d(S, "bitmap inner=$innerId bytes=${bmp.byteCount} (RemoteViews budget is about 1.5x screen pixels x 4 bytes per update)")
        val page = RemoteViews(context.packageName, R.layout.widget_bitmap_page)
        page.setImageViewBitmap(R.id.page_image, bmp)
        page.setOnClickPendingIntent(R.id.page_image, broadcast(context, StackWidgetProvider.ACTION_TAP, innerId))
        return page
    }

    /** Pushes the Stack widget's current cell size to an inner host view (also drives size-mapped RemoteViews). */
    fun applyStackSizeTo(view: RelayHostView) {
        val mgr = AppWidgetManager.getInstance(StackRepository.appContext)
        val stackIds = mgr.getAppWidgetIds(ComponentName(StackRepository.appContext, StackWidgetProvider::class.java))
        val options = stackIds.firstOrNull()?.let { mgr.getAppWidgetOptions(it) } ?: Bundle()
        val size = sizeOf(options)
        try {
            view.updateAppWidgetSize(Bundle(), listOf(size))
            SpikeLog.d(S, "updateAppWidgetSize(inner) -> ${size.width}x${size.height} dp")
        } catch (t: Throwable) {
            SpikeLog.w(S, "updateAppWidgetSize failed", t)
        }
    }

    // ---- helpers -------------------------------------------------------

    private fun sizeOf(options: Bundle): SizeF {
        @Suppress("DEPRECATION") // typed getParcelableArrayList needs API 33; minSdk is 31
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        if (!sizes.isNullOrEmpty()) return sizes.first()
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        return SizeF(w.toFloat(), h.toFloat())
    }

    @Suppress("DEPRECATION")
    private fun describeOptions(b: Bundle): String =
        b.keySet().joinToString(", ") { k -> "$k=${b.get(k)}" }

    private fun broadcast(context: Context, action: String, id: Int): PendingIntent {
        val intent = Intent(action).setClass(context, StackWidgetProvider::class.java).putExtra(StackWidgetProvider.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, action.hashCode() + id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun errorPage(context: Context, msg: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_error_page).apply { setTextViewText(R.id.error_text, msg) }

    private fun errorRoot(context: Context, msg: String): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.widget_stack)
        root.setViewVisibility(R.id.empty, View.VISIBLE)
        root.setTextViewText(R.id.empty, msg)
        root.setTextViewText(R.id.page_label, "error")
        return root
    }
}

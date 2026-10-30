package dev.widgetstack.spike

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.database.DataSetObserver
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.RemoteViews

/**
 * Host view for one inner widget. Its only job beyond the stock behaviour is to
 * intercept every RemoteViews the provider pushes, describe it in the log, keep
 * the latest copy, and ask the renderer to republish the stack.
 */
class RelayHostView(context: Context, private val id: Int) : AppWidgetHostView(context) {

    /**
     * True when the inflated widget contains a collection view (ListView, GridView,
     * StackView...) backed by the provider's RemoteViewsService. Such a page cannot
     * be relayed: the launcher may only reach that service for a widget whose
     * provider is the inner app, and inside the stack the provider is us, so the
     * whole stack would fail to inflate. The renderer mirrors these pages as bitmaps.
     */
    @Volatile var isCollection: Boolean = false
        private set

    private var observedAdapter: Adapter? = null
    private val observer = object : DataSetObserver() {
        override fun onChanged() {
            SpikeLog.d("hostview", "collection data changed id=$id -> re-push")
            StackRepository.requestPush("collection data id=$id")
        }
        override fun onInvalidated() = onChanged()
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        SpikeLog.i("hostview", "updateAppWidget id=$id ${describe(remoteViews)}")
        try {
            super.updateAppWidget(remoteViews)
        } catch (t: Throwable) {
            // Keep going: we still want to relay the RemoteViews even if the
            // local inflate fails (bitmap mode will then also fail and say so).
            SpikeLog.e("hostview", "local inflate failed id=$id", t)
        }
        inspectForCollections()
        StackRepository.onInnerUpdate(id, remoteViews)
    }

    private fun inspectForCollections() {
        val adapterView = findAdapterView(this)
        isCollection = adapterView != null
        if (adapterView == null) return
        val adapter = adapterView.adapter
        SpikeLog.i("hostview", "id=$id contains ${adapterView.javaClass.simpleName} adapter=${adapter?.javaClass?.name} count=${adapter?.count}; page will be mirrored as a bitmap")
        if (adapter != null && adapter !== observedAdapter) {
            try { observedAdapter?.unregisterDataSetObserver(observer) } catch (ignored: Throwable) { }
            adapter.registerDataSetObserver(observer)
            observedAdapter = adapter
        }
    }

    private fun findAdapterView(v: View): AdapterView<*>? {
        if (v is AdapterView<*>) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findAdapterView(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SpikeLog.d("hostview", "attached id=$id")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        SpikeLog.d("hostview", "detached id=$id")
    }

    companion object {
        /** Best-effort description; hidden fields are read reflectively and failures are reported, not thrown. */
        fun describe(rv: RemoteViews?): String {
            if (rv == null) return "rv=null"
            val sb = StringBuilder("rv pkg=${rv.`package`} layout=0x${Integer.toHexString(rv.layoutId)}")
            try {
                val f = RemoteViews::class.java.getDeclaredField("mActions")
                f.isAccessible = true
                val actions = f.get(rv) as? List<*>
                sb.append(" actions=${actions?.size ?: 0}")
            } catch (t: Throwable) {
                sb.append(" actions=hidden(${t.javaClass.simpleName})")
            }
            try {
                val f = RemoteViews::class.java.getDeclaredField("mSizedRemoteViews")
                f.isAccessible = true
                val sized = f.get(rv) as? List<*>
                if (sized != null) sb.append(" sizedVariants=${sized.size}")
            } catch (ignored: Throwable) { }
            try {
                val f = RemoteViews::class.java.getDeclaredField("mLandscape")
                f.isAccessible = true
                if (f.get(rv) != null) sb.append(" hasLandscape/Portrait")
            } catch (ignored: Throwable) { }
            return sb.toString()
        }
    }
}

package dev.widgetstack.spike

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.widget.RemoteViews

/**
 * Host view for one inner widget. Its only job beyond the stock behaviour is to
 * intercept every RemoteViews the provider pushes, describe it in the log, keep
 * the latest copy, and ask the renderer to republish the stack.
 */
class RelayHostView(context: Context, private val id: Int) : AppWidgetHostView(context) {

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        SpikeLog.i("hostview", "updateAppWidget id=$id ${describe(remoteViews)}")
        try {
            super.updateAppWidget(remoteViews)
        } catch (t: Throwable) {
            // Keep going: we still want to relay the RemoteViews even if the
            // local inflate fails (bitmap mode will then also fail and say so).
            SpikeLog.e("hostview", "local inflate failed id=$id", t)
        }
        StackRepository.onInnerUpdate(id, remoteViews)
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

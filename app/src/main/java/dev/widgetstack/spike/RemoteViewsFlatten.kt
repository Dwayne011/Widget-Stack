package dev.widgetstack.spike

import android.content.Context
import android.content.res.Configuration
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.math.abs

/**
 * Reduces a RemoteViews that carries several layouts (per-size map on Android 12+,
 * or landscape/portrait) to the single variant that fits [size].
 *
 * RemoteCollectionItems refuses multi-layout RemoteViews, and the public API has
 * no way to pick a variant, so this reads RemoteViews' private fields. Every step
 * is logged and any failure returns null so the caller can fall back to a bitmap.
 */
object RemoteViewsFlatten {
    private const val S = "flatten"

    fun pick(context: Context, root: RemoteViews, size: SizeF): RemoteViews? = try {
        var rv: RemoteViews = root
        var hops = 0
        while (hops++ < 4) {
            val sized = field(rv, "mSizedRemoteViews") as? List<*>
            if (!sized.isNullOrEmpty()) {
                val best = sized.filterIsInstance<RemoteViews>().minByOrNull { candidate ->
                    val ideal = field(candidate, "mIdealSize") as? SizeF
                    if (ideal == null) Float.MAX_VALUE else abs(ideal.width - size.width) + abs(ideal.height - size.height)
                } ?: return null
                SpikeLog.d(S, "sized variants=${sized.size}; chose ideal=${field(best, "mIdealSize")} for stack ${size.width}x${size.height}")
                rv = best
                continue
            }
            val land = field(rv, "mLandscape") as? RemoteViews
            val port = field(rv, "mPortrait") as? RemoteViews
            if (land != null || port != null) {
                val isLand = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                rv = (if (isLand) land else port) ?: port ?: land!!
                SpikeLog.d(S, "landscape/portrait variants; chose ${if (isLand) "landscape" else "portrait"}")
                continue
            }
            return rv
        }
        null
    } catch (t: Throwable) {
        SpikeLog.w(S, "could not flatten RemoteViews (hidden API blocked?)", t)
        null
    }

    private fun field(rv: RemoteViews, name: String): Any? {
        val f = RemoteViews::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(rv)
    }
}

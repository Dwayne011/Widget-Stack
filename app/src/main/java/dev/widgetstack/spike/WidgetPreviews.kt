package dev.widgetstack.spike

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import kotlin.math.ceil

/** Builds a preview for a provider: previewImage if declared, else a render of previewLayout, else the app icon. */
object WidgetPreviews {

    fun cells(context: Context, info: AppWidgetProviderInfo): Pair<Int, Int> {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) return info.targetCellWidth to info.targetCellHeight
        val density = context.resources.displayMetrics.density
        fun toCells(px: Int) = ceil(((px / density) + 30f) / 70f).toInt().coerceAtLeast(1)
        return toCells(info.minWidth) to toCells(info.minHeight)
    }

    fun load(context: Context, info: AppWidgetProviderInfo, maxWidthPx: Int): Drawable? {
        try {
            info.loadPreviewImage(context, 0)?.let { return it }
        } catch (t: Throwable) {
            SpikeLog.w("preview", "loadPreviewImage failed for ${info.provider}", t)
        }
        if (info.previewLayout != 0) {
            try {
                val (cw, ch) = cells(context, info)
                val density = context.resources.displayMetrics.density
                val w = minOf((cw * 70 * density).toInt(), maxWidthPx).coerceAtLeast(1)
                val h = (w * ch.toFloat() / cw).toInt().coerceAtLeast(1)
                val rv = RemoteViews(info.provider.packageName, info.previewLayout)
                val parent = FrameLayout(context)
                val v = rv.apply(context, parent)
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                return BitmapDrawable(context.resources, bmp)
            } catch (t: Throwable) {
                SpikeLog.w("preview", "previewLayout render failed for ${info.provider}", t)
            }
        }
        return try { info.loadIcon(context, 0) } catch (t: Throwable) { null }
    }
}

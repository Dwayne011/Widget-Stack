package dev.widgetstack.spike

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class StackWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NEXT = "dev.widgetstack.spike.PAGE_NEXT"
        const val ACTION_PREV = "dev.widgetstack.spike.PAGE_PREV"
        const val ACTION_TAP = "dev.widgetstack.spike.PAGE_TAP"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        SpikeLog.d("provider", "onReceive ${intent.action} extras=${intent.extras?.keySet()?.joinToString()}")
        when (intent.action) {
            ACTION_NEXT -> StackRepository.setPage(StackRepository.page() + 1)
            ACTION_PREV -> StackRepository.setPage(StackRepository.page() - 1)
            ACTION_TAP -> {
                // Bitmap-mode pages are pictures; a tap here is where "expand to live view" would go.
                SpikeLog.i("provider", "tap on mirrored page inner id=${intent.getIntExtra(EXTRA_ID, -1)} (no action in spike)")
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SpikeLog.i("provider", "onUpdate stack ids=${appWidgetIds.toList()}")
        if (StackRepository.ids().isNotEmpty()) StackRepository.startListening()
        appWidgetIds.forEach { StackRenderer.pushOne(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        SpikeLog.i("provider", "onAppWidgetOptionsChanged stack id=$appWidgetId (resized)")
        StackRepository.views.values.forEach { StackRenderer.applyStackSizeTo(it) }
        StackRenderer.pushOne(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) { SpikeLog.i("provider", "onEnabled: first stack instance placed") }

    override fun onDisabled(context: Context) {
        SpikeLog.i("provider", "onDisabled: last stack instance removed; stop listening to save battery")
        StackRepository.stopListening()
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) { SpikeLog.i("provider", "onDeleted stack ids=${appWidgetIds.toList()}") }
}

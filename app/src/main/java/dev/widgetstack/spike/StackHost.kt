package dev.widgetstack.spike

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/**
 * The app's AppWidgetHost. HOST_ID must stay constant for the life of the app
 * install, otherwise previously bound widget ids become orphans.
 */
class StackHost(context: Context) : AppWidgetHost(context, HOST_ID) {

    companion object { const val HOST_ID = 0x5741 /* 'WA' */ }

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView {
        SpikeLog.d("host", "onCreateView id=$appWidgetId provider=${appWidget?.provider}")
        return RelayHostView(context, appWidgetId)
    }

    override fun onProvidersChanged() {
        SpikeLog.i("host", "onProvidersChanged (a widget provider app was installed/updated/removed)")
    }

    override fun onProviderChanged(appWidgetId: Int, appWidget: AppWidgetProviderInfo?) {
        SpikeLog.i("host", "onProviderChanged id=$appWidgetId provider=${appWidget?.provider}")
        super.onProviderChanged(appWidgetId, appWidget)
    }
}

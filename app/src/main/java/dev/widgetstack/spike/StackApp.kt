package dev.widgetstack.spike

import android.app.Application
import android.os.Build

class StackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        SpikeLog.i("app", "process start. device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        // Default uncaught handler so a crash in the host path lands in our log too.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            SpikeLog.e("app", "UNCAUGHT on ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/widget/RemoteViews;")
            SpikeLog.i("app", "hidden API exemption added for RemoteViews")
        } catch (t: Throwable) {
            SpikeLog.w("app", "HiddenApiBypass failed; RemoteViews internals may be unreadable", t)
        }
        StackRepository.init(this)
    }
}

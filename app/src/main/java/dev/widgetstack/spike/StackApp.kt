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
        StackRepository.init(this)
    }
}

package dev.widgetstack.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the process (and so the listening AppWidgetHost)
 * alive. Without it Android kills the process minutes after the app leaves the
 * foreground and inner widgets stop updating. A shipped app would gate this more
 * carefully; the spike just needs updates to keep flowing while testing.
 */
class HostService : Service() {
    companion object {
        private const val CHANNEL = "host"
        private const val NOTIF_ID = 1

        fun ensureRunning(context: Context) {
            try {
                context.startForegroundService(Intent(context, HostService::class.java))
            } catch (t: Throwable) {
                // Typical cause: ForegroundServiceStartNotAllowedException when started from background on 12+.
                SpikeLog.w("service", "startForegroundService failed (updates may stop when app is backgrounded)", t)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            SpikeLog.i("service", "foreground service started (sdk=${Build.VERSION.SDK_INT})")
        } catch (t: Throwable) {
            SpikeLog.e("service", "startForeground failed", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() { SpikeLog.i("service", "service destroyed"); super.onDestroy() }

    override fun onBind(intent: Intent?): IBinder? = null
}

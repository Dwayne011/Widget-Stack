package dev.widgetstack.spike

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader

/**
 * Optional privileged path. Runs `appwidget grantbind --package <us> --user current`
 * as the shell uid through Shizuku, which allow-lists this package so
 * bindAppWidgetIdIfAllowed() succeeds without the system dialog.
 *
 * Shizuku.newProcess is not public API; it is reached by reflection and every
 * failure is logged rather than thrown.
 */
object ShizukuBind {
    private const val S = "shizuku"
    private const val REQ = 7001

    fun status(): String = try {
        if (!Shizuku.pingBinder()) "Shizuku not running"
        else if (Shizuku.isPreV11()) "Shizuku too old (pre v11)"
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku ready (uid=${Shizuku.getUid()}, v${Shizuku.getVersion()})"
        else "Shizuku running, permission not granted"
    } catch (t: Throwable) { "Shizuku error: ${t.javaClass.simpleName}: ${t.message}" }

    /** Requests permission if needed, then runs grantbind. [onDone] receives a human-readable result. */
    fun grantBind(packageName: String, onDone: (String) -> Unit) {
        SpikeLog.i(S, "grantBind requested. ${status()}")
        if (!Shizuku.pingBinder()) { onDone("Shizuku is not running. Start it from the Shizuku app first."); return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            run(packageName, onDone); return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            onDone("Shizuku permission was denied earlier; re-enable it in the Shizuku app."); return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQ) return
                Shizuku.removeRequestPermissionResultListener(this)
                SpikeLog.i(S, "permission result=$grantResult")
                if (grantResult == PackageManager.PERMISSION_GRANTED) run(packageName, onDone) else onDone("Shizuku permission denied")
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQ)
    }

    private fun run(packageName: String, onDone: (String) -> Unit) {
        val cmd = arrayOf("appwidget", "grantbind", "--package", packageName, "--user", "current")
        Thread {
            val result = try {
                SpikeLog.i(S, "exec: ${cmd.joinToString(" ")}")
                val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                m.isAccessible = true
                val p = m.invoke(null, cmd, null, null) as Process
                val out = p.inputStream.bufferedReader().use(BufferedReader::readText)
                val err = p.errorStream.bufferedReader().use(BufferedReader::readText)
                val code = p.waitFor()
                SpikeLog.i(S, "exit=$code stdout='${out.trim()}' stderr='${err.trim()}'")
                if (code == 0) "grantbind OK (exit 0). Try 'Add widget': no system dialog should appear."
                else "grantbind exit $code: ${(err + out).trim()}"
            } catch (t: Throwable) {
                SpikeLog.e(S, "grantbind failed", t)
                "grantbind failed: ${t.cause?.message ?: t.message}"
            }
            onDone(result)
        }.start()
    }
}

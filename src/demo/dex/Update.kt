package demo.dex

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Updates from [BASE], where publish.sh puts each release: `version` holds its versionCode, `dexsearch.apk` the app.
 * The home screen reads `version` (a few bytes) once per launch and offers a newer build. Installing streams the APK
 * into a PackageInstaller session through one 64 KB buffer, so no copy is kept on disk. Once an update has come from
 * here the app is its own installer, and the next ones skip the system's confirm.
 */
object Update {
    /** GitHub Pages of this repository. */
    const val BASE = "https://d3andark.github.io/dexsearch/"

    /** Runs `found` on the UI thread with the published versionCode when it is newer than this build's. */
    fun check(a: Activity, found: (Int) -> Unit) {
        Thread {
            val code = try {
                get("version").inputStream.use { it.reader().readText().trim().toIntOrNull() } ?: 0
            } catch (e: IOException) {
                0 // Offline, or nothing published yet: the next launch asks again.
            }
            if (code > a.packageManager.getPackageInfo(a.packageName, 0).longVersionCode) {
                a.runOnUiThread { if (!a.isDestroyed) found(code) }
            }
        }.start()
    }

    private fun get(path: String): HttpURLConnection {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        return c
    }

    /**
     * A sheet with the download's progress and Cancel while the APK goes into a session, which is then committed.
     * The session reports back to a receiver that lives as long as the sheet: the system's confirm screen to open, or
     * why it failed. Success replaces this process, so it shows nothing.
     */
    fun install(a: Activity, code: Int) {
        val sheet = Ui.Sheet(a, L.f("Version %d", code))
        val status = Ui.muted(a, L.f("Downloading: %d%%", 0), 14)
        sheet.body.addView(status)
        val stop = AtomicBoolean()
        sheet.button(L.t("Cancel")) { stop.set(true) }
        sheet.setOnCancelListener { stop.set(true) }
        // Unguessable: before Android 13 a runtime receiver is open to every app, and this one starts the intent it gets.
        val action = a.packageName + ".INSTALL." + UUID.randomUUID()
        val back = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let { a.startActivity(it) }
                    PackageInstaller.STATUS_SUCCESS -> {}
                    else -> status.text = L.f("Update failed: %s", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
                }
            }
        }
        a.registerReceiver(back, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        sheet.setOnDismissListener { a.unregisterReceiver(back) }
        sheet.show()
        val show = { s: String -> a.runOnUiThread { status.text = s } }
        val installer = a.packageManager.packageInstaller
        Thread {
            var id = -1
            var committed = false
            try {
                // A try the process did not live to finish keeps its partial APK in the system until abandoned.
                for (old in installer.mySessions) if (!old.isCommitted) installer.abandonSession(old.sessionId)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(a.packageName)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    if (copy(s, stop) { pct -> show(L.f("Downloading: %d%%", pct)) }) {
                        show(L.t("Installing"))
                        // Mutable: the system adds the status extras.
                        val report = PendingIntent.getBroadcast(a, id, Intent(action).setPackage(a.packageName),
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        s.commit(report.intentSender)
                        committed = true
                    }
                }
            } catch (e: IOException) {
                show(L.f("Update failed: %s", e.message ?: e.toString()))
            } catch (e: SecurityException) {
                show(L.f("Update failed: %s", e.message ?: e.toString()))
            }
            if (!committed && id >= 0) installer.abandonSession(id)
        }.start()
    }

    /** Copies the APK into `s`, reporting whole percents; false when `stop` was set first. */
    private fun copy(s: PackageInstaller.Session, stop: AtomicBoolean, progress: (Int) -> Unit): Boolean {
        val http = get("dexsearch.apk")
        val total = http.contentLengthLong
        http.inputStream.use { input ->
            s.openWrite("base.apk", 0, total).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = 0
                while (true) {
                    if (stop.get()) return false
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != shown) {
                        shown = pct
                        progress(pct)
                    }
                }
                s.fsync(out)
            }
        }
        return true
    }
}

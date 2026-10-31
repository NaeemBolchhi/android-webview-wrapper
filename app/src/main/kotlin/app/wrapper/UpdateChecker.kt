package app.wrapper

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the GitHub releases of the repo this app was built in (BuildConfig.UPDATE_REPO).
 *
 * Release tags and names are ignored. Instead every asset of every published release is matched
 * against the APK naming scheme produced by the build: `<AppName>-v<versionName>-<build>.apk`.
 * An asset belongs to this app when `<AppName>` equals BuildConfig.APK_PREFIX; it is an update when
 * its versionName is higher than the installed one (or equal with a higher build number).
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS = "update_checker"
    private const val KEY_LAST = "last_check"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // only ask GitHub (60 req/h unauthenticated) every 6 h
    private const val APK_MIME = "application/vnd.android.package-archive"

    private class Update(val name: String, val version: String, val build: Long, val url: String, val size: Long)

    private val TOKEN = Regex("[0-9]+|[A-Za-z]+")
    private val UNSAFE = Regex("[^A-Za-z0-9._-]+")

    /** Call once from the main activity. Silent unless a newer version exists. */
    fun start(a: ComponentActivity) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return

        File(a.cacheDir, "updates").deleteRecursively() // leftovers of an earlier update

        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val age = now - prefs.getLong(KEY_LAST, 0L)
        if (age in 0 until CHECK_INTERVAL_MS) return

        Thread {
            val update = try {
                findUpdate(repo).also { prefs.edit().putLong(KEY_LAST, now).apply() }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                null
            }
            if (update != null) a.runOnUiThread { whenResumed(a) { prompt(a, update) } }
        }.start()
    }

    // ---- release lookup ------------------------------------------------------------------

    private fun findUpdate(repo: String): Update? {
        val json = open("https://api.github.com/repos/$repo/releases?per_page=30", "application/vnd.github+json")
            .let { c -> try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() } }
        val releases = JSONArray(json)
        val pattern = Regex("^" + Regex.escape(BuildConfig.APK_PREFIX) + "-v(.+)-(\\d+)\\.apk$")

        var best: Update? = null
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                val m = pattern.matchEntire(name) ?: continue
                if (asset.optString("state", "uploaded") != "uploaded") continue
                val url = asset.optString("browser_download_url")
                val build = m.groupValues[2].toLongOrNull() ?: continue
                if (url.isBlank()) continue
                val cand = Update(name, m.groupValues[1], build, url, asset.optLong("size"))
                if (best == null || isNewer(cand.version, cand.build, best.version, best.build)) best = cand
            }
        }
        return best?.takeIf {
            isNewer(it.version, it.build, UNSAFE.replace(BuildConfig.VERSION_NAME, "_").trim('_'), BuildConfig.VERSION_CODE.toLong())
        }
    }

    private fun isNewer(ver: String, build: Long, thanVer: String, thanBuild: Long): Boolean {
        val c = compareVersions(ver, thanVer)
        return c > 0 || (c == 0 && build > thanBuild)
    }

    /** Numeric parts compare as numbers (1.10 > 1.9); a missing part counts as 0; "1.0-beta" < "1.0". */
    private fun compareVersions(a: String, b: String): Int {
        val x = TOKEN.findAll(a).map { it.value }.toList()
        val y = TOKEN.findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = compareToken(x.getOrNull(i), y.getOrNull(i))
            if (c != 0) return c
        }
        return 0
    }

    private fun compareToken(p: String?, q: String?): Int = when {
        p == null -> -compareToken(q, null)
        q == null -> if (!p[0].isDigit()) -1 else if (p.all { it == '0' }) 0 else 1
        p[0].isDigit() && q[0].isDigit() -> {
            val s = p.trimStart('0')
            val t = q.trimStart('0')
            if (s.length != t.length) s.length.compareTo(t.length) else s.compareTo(t)
        }
        p[0].isDigit() -> 1
        q[0].isDigit() -> -1
        else -> p.compareTo(q, ignoreCase = true)
    }

    // ---- UI ------------------------------------------------------------------------------

    private fun prompt(a: ComponentActivity, u: Update) {
        val size = if (u.size > 0) "\n\nDownload size: ${Formatter.formatFileSize(a, u.size)}" else ""
        AlertDialog.Builder(a)
            .setTitle("Update available")
            .setMessage("Version ${u.version} is available. You have ${BuildConfig.VERSION_NAME}.$size")
            .setPositiveButton("Update") { _, _ -> beginUpdate(a, u) }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Android requires a per-app "install unknown apps" switch; send the user there first if needed. */
    private fun beginUpdate(a: ComponentActivity, u: Update) {
        if (a.packageManager.canRequestPackageInstalls()) { download(a, u); return }
        val label = a.applicationInfo.loadLabel(a.packageManager)
        AlertDialog.Builder(a)
            .setTitle("Allow installing updates")
            .setMessage("Android needs your permission before $label can install updates. Open settings, switch on \"Allow from this source\", then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                } catch (e: Exception) {
                    Toast.makeText(a, "Could not open settings", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                afterReturning(a) {
                    if (a.packageManager.canRequestPackageInstalls()) download(a, u)
                    else Toast.makeText(a, "Permission not granted, update cancelled", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun download(a: ComponentActivity, u: Update) {
        val dir = File(a.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, u.name + ".part")
        val dest = File(dir, u.name)
        val cancelled = AtomicBoolean(false)

        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val label = TextView(a).apply { text = "Starting…"; setPadding(0, 0, 0, pad / 2) }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(a)
            .setTitle("Downloading update")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()

        fun closeDialog() { try { dialog.dismiss() } catch (_: Exception) { } }

        Thread {
            try {
                val c = open(u.url, "*/*")
                try {
                    val total = if (u.size > 0) u.size else c.contentLengthLong
                    var done = 0L
                    var lastUi = 0L
                    c.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled.get()) throw IOException("Cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val t = System.nanoTime()
                                if (t - lastUi > 200_000_000L) {
                                    lastUi = t
                                    val d = done
                                    a.runOnUiThread {
                                        if (total > 0) {
                                            bar.isIndeterminate = false
                                            bar.progress = (d * 100 / total).toInt().coerceIn(0, 100)
                                            label.text = "${Formatter.formatFileSize(a, d)} of ${Formatter.formatFileSize(a, total)}"
                                        } else label.text = Formatter.formatFileSize(a, d)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (u.size > 0 && part.length() != u.size) throw IOException("Download incomplete")
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("Could not store the update")

                val info = a.packageManager.getPackageArchiveInfo(dest.path, 0)
                if (info == null || info.packageName != a.packageName) {
                    dest.delete()
                    throw IOException("The downloaded file is not an update for this app")
                }
                a.runOnUiThread { closeDialog(); whenResumed(a) { install(a, dest, u) } }
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                part.delete()
                a.runOnUiThread {
                    closeDialog()
                    if (!cancelled.get()) Toast.makeText(a, "Update failed: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Hands the APK to the system package installer. */
    private fun install(a: ComponentActivity, apk: File, u: Update) {
        try {
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.updates", apk)
            a.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the installer", e)
            Toast.makeText(a, "Could not start the installer, opening the download link instead", Toast.LENGTH_LONG).show()
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
            } catch (_: Exception) {
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "${BuildConfig.APK_PREFIX}-update-checker")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Encoding", "identity")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
    }

    /** Runs [block] now if the activity is in the foreground, otherwise when it next gets there. */
    private fun whenResumed(a: ComponentActivity, block: () -> Unit) {
        if (a.isDestroyed || a.isFinishing) return
        if (a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { block(); return }
        a.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                block()
            }
        })
    }

    /** Runs [block] once the activity comes back to the foreground after having been left. */
    private fun afterReturning(a: ComponentActivity, block: () -> Unit) {
        a.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var left = false
            override fun onPause(owner: LifecycleOwner) { left = true }
            override fun onResume(owner: LifecycleOwner) {
                if (!left) return
                owner.lifecycle.removeObserver(this)
                block()
            }
        })
    }
}

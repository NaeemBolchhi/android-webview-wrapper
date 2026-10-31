package app.wrapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {
    private val pool = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private val ids = AtomicInteger(100)
    @Volatile private var lastStart = 0
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val url = intent?.getStringExtra("url")
        if (url == null) { stopSelf(startId); return START_NOT_STICKY }
        val id = ids.incrementAndGet()
        pending.incrementAndGet()
        val guess = nameFromUrl(url)
        ServiceCompat.startForeground(this, id, progress(guess, 0, -1, -1, "Waiting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val ua = intent.getStringExtra("ua")
        val cd = intent.getStringExtra("cd")
        val ref = intent.getStringExtra("ref")
        pool.execute { run(id, url, ua, cd, ref, guess) }
        return START_NOT_STICKY
    }

    private fun run(id: Int, url: String, ua: String?, cd: String?, ref: String?, guess: String) {
        var uri: Uri? = null
        var name = guess
        try {
            ServiceCompat.startForeground(this, id, progress(name, 0, -1, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val conn = open(url, ua, ref)
            name = nameFromDisposition(conn.getHeaderField("Content-Disposition"))
                ?: nameFromDisposition(cd) ?: nameFromUrl(conn.url.toString())
            name = timestamped(name)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("Cannot create file")
            uri = out

            val total = conn.contentLengthLong
            var done = 0L
            var lastT = System.nanoTime()
            var lastDone = 0L
            var speed = 0.0
            conn.inputStream.use { input ->
                contentResolver.openOutputStream(out)!!.use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastT > 500_000_000L) {
                            val inst = (done - lastDone) / ((now - lastT) / 1e9)
                            speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                            lastT = now; lastDone = done
                            val eta = if (total > 0 && speed > 0) ((total - done) / speed).toLong() else -1L
                            nm.notify(id, progress(name, done, total, eta))
                        }
                    }
                }
            }
            contentResolver.update(out, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            finish(id)
            nm.notify(ids.incrementAndGet(), complete(name, done, out, mime))
        } catch (e: Exception) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            finish(id)
            nm.notify(
                ids.incrementAndGet(),
                Notification.Builder(this, CH_DONE).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(name).setContentText("Download failed: ${e.message ?: "unknown error"}")
                    .setAutoCancel(true).build()
            )
        } finally {
            if (pending.decrementAndGet() == 0) stopSelf(lastStart)
        }
    }

    private fun finish(id: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        nm.cancel(id)
    }

    /** Follows redirects manually (also across http/https). */
    private fun open(start: String, ua: String?, ref: String?): HttpURLConnection {
        var u = URL(start)
        repeat(8) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity")
            ua?.let { c.setRequestProperty("User-Agent", it) }
            ref?.let { c.setRequestProperty("Referer", it) }
            CookieManager.getInstance().getCookie(u.toString())?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                u = URL(u, loc)
                c.disconnect()
            } else if (code in 200..299) {
                return c
            } else throw IOException("HTTP $code")
        }
        throw IOException("Too many redirects")
    }

    private fun progress(name: String, done: Long, total: Long, eta: Long, note: String? = null): Notification {
        val b = Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val d = Formatter.formatFileSize(this, done)
        val etaText = if (eta >= 0) "${fmt(eta)} left" else "ETA unknown"
        if (total > 0) {
            val pct = (done * 100 / total).toInt()
            b.setProgress(100, pct, false)
            b.setContentText(note ?: "$d of ${Formatter.formatFileSize(this, total)} • $pct% • $etaText")
        } else {
            b.setProgress(0, 0, true)
            b.setContentText(note ?: "$d • $etaText")
        }
        return b.build()
    }

    private fun complete(name: String, size: Long, uri: Uri, mime: String): Notification {
        val open = Intent(this, OpenActivity::class.java).setData(uri).putExtra("mime", mime)
        val pi = PendingIntent.getActivity(this, ids.get(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name)
            .setContentText("Download complete • ${Formatter.formatFileSize(this, size)}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    }

    /** name.ext -> name.<timestamp>.ext (name.tar.gz -> name.<timestamp>.tar.gz) */
    private fun timestamped(name: String): String {
        val ts = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(Date())
        var base = name
        var ext = ""
        val dot = name.lastIndexOf('.')
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot) }
        if (ext.isNotEmpty() && base.endsWith(".tar", true)) { ext = base.takeLast(4) + ext; base = base.dropLast(4) }
        return "$base.$ts$ext"
    }

    private fun fmt(s: Long) = when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }

    companion object {
        private const val CH_PROGRESS = "download_progress"
        private const val CH_DONE = "download_done"
        private val RFC5987 = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]*)", RegexOption.IGNORE_CASE)
        private val QUOTED = Regex("filename\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE)
        private val PLAIN = Regex("filename\\s*=\\s*([^;\"]+)", RegexOption.IGNORE_CASE)

        fun enqueue(c: Context, url: String, ua: String?, cd: String?, ref: String?) {
            c.startForegroundService(
                Intent(c, DownloadService::class.java)
                    .putExtra("url", url).putExtra("ua", ua).putExtra("cd", cd).putExtra("ref", ref)
            )
        }

        /** Only strips path separators and control chars; keeps the name otherwise untouched. */
        private fun clean(n: String): String? {
            val s = n.substringAfterLast('/').substringAfterLast('\\')
                .filter { it.code >= 32 }.trim()
            return if (s.isEmpty() || s == "." || s == "..") null else s
        }

        fun nameFromDisposition(cd: String?): String? {
            if (cd.isNullOrBlank()) return null
            RFC5987.find(cd)?.let { m ->
                val cs = m.groupValues[1].ifBlank { "UTF-8" }
                val dec = runCatching {
                    URLDecoder.decode(m.groupValues[2].trim().trim('"').replace("+", "%2B"), cs)
                }.getOrNull()
                clean(dec ?: m.groupValues[2])?.let { return it }
            }
            QUOTED.find(cd)?.let { m -> clean(m.groupValues[1].replace("\\\"", "\""))?.let { return it } }
            PLAIN.find(cd)?.let { m -> clean(m.groupValues[1])?.let { return it } }
            return null
        }

        fun nameFromUrl(url: String): String =
            Uri.parse(url).lastPathSegment?.let { clean(it) } ?: "download"
    }
}

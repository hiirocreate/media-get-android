package com.example.mediaget

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs yt-dlp downloads sequentially in the background so they survive the
 * activity being backgrounded. Progress is reported through [DownloadRepository];
 * this service owns no UI state of its own besides the status-bar notification.
 */
class DownloadService : Service() {

    private data class Job(
        val id: String,
        val url: String,
        val mode: DownloadMode,
        val compressImages: Boolean,
        val processId: String,
        val playlistItems: String?,
        val directSource: DirectVideoSource?
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobChannel = Channel<Job>(Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    private var consumerStarted = false

    override fun onCreate() {
        super.onCreate()
        startForeground(
            SUMMARY_NOTIFICATION_ID,
            buildSummaryNotification("待機中", 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        ensureConsumer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val modeName = intent?.getStringExtra(EXTRA_MODE)
        val processId = intent?.getStringExtra(EXTRA_PROCESS_ID)
        val compress = intent?.getBooleanExtra(EXTRA_COMPRESS, false) ?: false
        val playlistItems = intent?.getStringExtra(EXTRA_PLAYLIST_ITEMS)
        val directVideoUrl = intent?.getStringExtra(EXTRA_DIRECT_VIDEO_URL)
        val directSource = if (directVideoUrl != null) {
            val headers = runCatching {
                val obj = JSONObject(intent.getStringExtra(EXTRA_DIRECT_HEADERS) ?: "{}")
                obj.keys().asSequence().associateWith { obj.getString(it) }
            }.getOrDefault(emptyMap())
            DirectVideoSource(directVideoUrl, headers)
        } else {
            null
        }

        if (id != null && url != null && modeName != null && processId != null) {
            pendingCount.incrementAndGet()
            jobChannel.trySend(
                Job(id, url, DownloadMode.valueOf(modeName), compress, processId, playlistItems, directSource)
            )
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun ensureConsumer() {
        if (consumerStarted) return
        consumerStarted = true
        scope.launch {
            for (job in jobChannel) {
                runCatching { processJob(job) }
                if (pendingCount.decrementAndGet() <= 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun processJob(job: Job) {
        DownloadRepository.update(job.id) { it.copy(status = DownloadStatus.RUNNING) }
        updateSummaryNotification(job.url, 0)

        val tmpDir = File(cacheDir, "dl/${job.id}").apply { mkdirs() }

        try {
            val directSource = job.directSource
            if (directSource != null) {
                // See DirectVideoSource's doc comment: this bypasses yt-dlp
                // entirely for a TikTok post whose video file the in-app
                // browser already requested and played successfully.
                downloadDirectFile(directSource, tmpDir)
            } else {
                // Links coming from an SNS app's own "共有" button are very
                // often that app's own shortened/redirect link (X's t.co,
                // TikTok's vt./vm.tiktok.com) rather than the canonical page
                // URL — a pasted link can be one too, since "Copy Link" on
                // some apps hands out the same short form. That breaks two
                // things at once: yt-dlp's per-site extractors mostly only
                // recognize the real domain (so a bare t.co link may not
                // match any extractor at all), and the WebView's login
                // cookies live under the real domain too (so even a
                // recognized short link looks logged-out). Resolving the
                // redirect once, up front, fixes both at the source.
                val resolvedUrl = resolveRedirect(job.url)

                try {
                    executeYoutubeDl(job, resolvedUrl, tmpDir, forceIpv4 = false, writeThumbnail = false)
                } catch (e: YoutubeDLException) {
                    // Mobile networks (especially carrier IPv6/VoLTE) sometimes hand
                    // out an IPv6-only DNS answer that yt-dlp's bundled Python
                    // networking can't resolve, even though the same host works
                    // fine in a normal browser (Android's own resolver falls back
                    // to IPv4 automatically; Python's doesn't here) — surfacing as
                    // "No address associated with hostname". This is a known,
                    // recurring issue for apps built on the same youtubedl-android
                    // library (see JunkFood02/Seal's own issue tracker), not
                    // something specific to a given site — worth one retry forcing
                    // IPv4 rather than failing outright.
                    if (e.message?.contains("No address associated with hostname") != true) throw e
                    tmpDir.listFiles()?.forEach { it.delete() }
                    executeYoutubeDl(job, resolvedUrl, tmpDir, forceIpv4 = true, writeThumbnail = false)
                }

                val producedFilesSoFar = tmpDir.listFiles()?.filter { isFinishedMediaFile(it) } ?: emptyList()

                // A plain photo item in an Instagram carousel/story has no video
                // formats at all — a confirmed, still-open yt-dlp bug (issues
                // #7569 and #12439). --ignore-no-formats-error above stops that
                // from throwing "No video formats found!" and aborting the job,
                // but yt-dlp still downloads nothing for a photo unless told to
                // also fetch the image — which is the maintainers' own documented
                // workaround: --write-thumbnail. This is only ever tried as a
                // fallback, and only when the normal attempt produced literally
                // no file AND the user didn't explicitly ask for "動画のみ"/
                // "音声のみ" (if they asked for video/audio specifically and this
                // post has none, reporting that honestly is correct — silently
                // handing back an unrelated photo would not be). A real video
                // download always succeeds on the first attempt above, so this
                // never runs for one and never adds an extra unwanted image file
                // alongside a video.
                if (producedFilesSoFar.isEmpty() && job.mode == DownloadMode.AUTO) {
                    tmpDir.listFiles()?.forEach { it.delete() }
                    runCatching {
                        executeYoutubeDl(job, resolvedUrl, tmpDir, forceIpv4 = false, writeThumbnail = true)
                    }
                }
            }

            DownloadRepository.update(job.id) { it.copy(status = DownloadStatus.SAVING) }

            val producedFiles = tmpDir.listFiles()?.filter { isFinishedMediaFile(it) } ?: emptyList()

            val savedUris = producedFiles.mapNotNull { original ->
                val finalFile = if (job.compressImages && ImageUtils.isImage(original)) {
                    ImageUtils.compressInPlace(original)
                } else {
                    original
                }
                MediaStoreUtils.saveToPublicDownloads(applicationContext, finalFile)
            }

            tmpDir.deleteRecursively()

            if (savedUris.isEmpty()) {
                val msg = "保存できるファイルが見つかりませんでした"
                DownloadRepository.update(job.id) {
                    it.copy(status = DownloadStatus.FAILED, errorMessage = msg)
                }
                notifyResult(job.id, success = false, job.url, msg)
            } else {
                DownloadRepository.update(job.id) {
                    it.copy(
                        status = DownloadStatus.DONE,
                        progressPercent = 100f,
                        savedFileUris = savedUris,
                        title = producedFiles.firstOrNull()?.nameWithoutExtension
                    )
                }
                notifyResult(job.id, success = true, job.url)
            }
        } catch (e: YoutubeDLException) {
            tmpDir.deleteRecursively()
            val msg = e.message ?: "ダウンロードに失敗しました"
            DownloadRepository.update(job.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = msg)
            }
            notifyResult(job.id, success = false, job.url, msg)
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            val msg = e.message ?: "予期しないエラーが発生しました"
            DownloadRepository.update(job.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = msg)
            }
            notifyResult(job.id, success = false, job.url, msg)
        }
    }

    private fun executeYoutubeDl(
        job: Job,
        resolvedUrl: String,
        tmpDir: File,
        forceIpv4: Boolean,
        writeThumbnail: Boolean
    ) {
        val request = YoutubeDLRequest(resolvedUrl).apply {
            addOption("-o", File(tmpDir, "%(title).100s-%(id)s.%(ext)s").absolutePath)
            addOption("--no-playlist")
            if (!job.playlistItems.isNullOrBlank()) {
                addOption("--playlist-items", job.playlistItems)
            }
            // A known yt-dlp bug (github.com/yt-dlp/yt-dlp/issues/7569) makes
            // its Instagram extractor try to resolve video formats for every
            // item in a carousel/story, including plain photos — which have
            // none — aborting the whole download instead of just skipping
            // those. This is the same flag used in MediaProbe.
            addOption("--ignore-no-formats-error")
            // See the big comment at this function's call site in processJob:
            // only set on the one-time fallback retry for a photo item that
            // has no video formats, so it can still hand back the image.
            if (writeThumbnail) addOption("--write-thumbnail")
            if (forceIpv4) addOption("--force-ipv4")
            // Same reasoning as MediaProbe's applyCookies() — without this,
            // a login-required post downloads as a logged-out request and
            // fails even though the browser tab shows you logged in.
            CookieExporter.exportForUrl(applicationContext, resolvedUrl)?.let { cookieFile ->
                addOption("--cookies", cookieFile.absolutePath)
            }
            when (job.mode) {
                DownloadMode.AUTO -> { /* let yt-dlp pick the best match for the link */ }
                DownloadMode.VIDEO -> {
                    addOption("-f", "bv*+ba/best")
                    addOption("--merge-output-format", "mp4")
                }
                DownloadMode.AUDIO_ONLY -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
            }
        }

        YoutubeDL.getInstance().execute(request, job.processId) { progress, _, _ ->
            val clamped = progress.coerceIn(0f, 100f)
            DownloadRepository.update(job.id) { it.copy(progressPercent = clamped) }
            updateSummaryNotification(job.url, clamped.toInt())
        }
    }

    // yt-dlp leaves partial/temp files behind under names like "*.part" or
    // "*.ytdl" while a download is in progress or if it was interrupted —
    // these are never something to save.
    private fun isFinishedMediaFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() !in setOf("part", "ytdl", "tmp")

    /**
     * Fetches [source]'s video URL exactly as the in-app browser's own
     * network stack requested it — same URL, same headers (including the
     * logged-in session's Cookie header), no yt-dlp involved. See
     * [DirectVideoSource]'s doc comment for why: it's specifically yt-dlp's
     * own HTTP requests that TikTok's anti-bot defenses have been blocking,
     * not this app's access to the video itself, so replaying the exact
     * request the WebView already made (and which already succeeded, since
     * that's how the video played on-screen) sidesteps the problem instead
     * of trying to out-guess it. This never contacts anything the WebView
     * didn't already contact on its own while the user was looking at the
     * post.
     */
    private fun downloadDirectFile(source: DirectVideoSource, tmpDir: File) {
        val connection = (URL(source.videoUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            source.headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("動画の取得に失敗しました (HTTP $code)")
            }
            val contentType = connection.contentType.orEmpty()
            val ext = when {
                contentType.contains("webm") -> "webm"
                contentType.contains("mp4") || contentType.contains("video") -> "mp4"
                else -> {
                    val fromUrl = source.videoUrl.substringBefore("?").substringAfterLast('.', "")
                    if (fromUrl.length in 2..4) fromUrl else "mp4"
                }
            }
            val outFile = File(tmpDir, "tiktok_${System.currentTimeMillis()}.$ext")
            connection.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    // Only ever follows a redirect chain that starts from a domain this app
    // doesn't already recognize as one of the SNS's own canonical hosts — a
    // link already on instagram.com/tiktok.com/etc. skips the network
    // round-trip entirely. Bounded hop count and short timeouts, and any
    // failure (offline, unexpected response, etc.) just falls back to the
    // original URL rather than blocking the download.
    private fun resolveRedirect(url: String): String {
        val canonicalHosts = setOf(
            "instagram.com", "tiktok.com", "youtube.com", "youtu.be",
            "x.com", "twitter.com", "threads.net", "threads.com"
        )
        val startHost = runCatching { URL(url).host }.getOrNull()?.lowercase()
        if (startHost == null || canonicalHosts.any { startHost == it || startHost.endsWith(".$it") }) {
            return url
        }

        return runCatching {
            var current = url
            repeat(5) {
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                } else {
                    return@runCatching current
                }
            }
            current
        }.getOrDefault(url)
    }

    private fun buildSummaryNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, MediaGetApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .build()
    }

    private fun updateSummaryNotification(url: String, progress: Int) {
        val notification = buildSummaryNotification("$progress% — $url", progress)
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun notifyResult(jobId: String, success: Boolean, url: String, detail: String? = null) {
        val bodyText = if (detail.isNullOrBlank()) url else "$url\n$detail"
        val notification = NotificationCompat.Builder(this, MediaGetApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(if (success) "保存しました" else "失敗しました")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setAutoCancel(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(jobId.hashCode(), notification)
    }

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 1
        private const val EXTRA_ID = "extra_id"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_COMPRESS = "extra_compress"
        private const val EXTRA_PROCESS_ID = "extra_process_id"
        private const val EXTRA_PLAYLIST_ITEMS = "extra_playlist_items"
        private const val EXTRA_DIRECT_VIDEO_URL = "extra_direct_video_url"
        private const val EXTRA_DIRECT_HEADERS = "extra_direct_headers"

        fun enqueue(context: Context, item: DownloadItem) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_URL, item.url)
                putExtra(EXTRA_MODE, item.mode.name)
                putExtra(EXTRA_COMPRESS, item.compressImages)
                putExtra(EXTRA_PROCESS_ID, item.processId)
                item.playlistItems?.let { putExtra(EXTRA_PLAYLIST_ITEMS, it) }
                item.directSource?.let { source ->
                    putExtra(EXTRA_DIRECT_VIDEO_URL, source.videoUrl)
                    val headersJson = JSONObject().apply {
                        source.headers.forEach { (key, value) -> put(key, value) }
                    }
                    putExtra(EXTRA_DIRECT_HEADERS, headersJson.toString())
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

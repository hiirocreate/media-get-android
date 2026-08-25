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
import java.io.File
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
        val playlistItems: String?
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

        if (id != null && url != null && modeName != null && processId != null) {
            pendingCount.incrementAndGet()
            jobChannel.trySend(
                Job(id, url, DownloadMode.valueOf(modeName), compress, processId, playlistItems)
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
            val request = YoutubeDLRequest(job.url).apply {
                addOption("-o", File(tmpDir, "%(title).100s-%(id)s.%(ext)s").absolutePath)
                addOption("--no-playlist")
                if (!job.playlistItems.isNullOrBlank()) {
                    addOption("--playlist-items", job.playlistItems)
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

            DownloadRepository.update(job.id) { it.copy(status = DownloadStatus.SAVING) }

            val producedFiles = tmpDir.listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase() !in setOf("part", "ytdl", "tmp")
            } ?: emptyList()

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
                DownloadRepository.update(job.id) {
                    it.copy(status = DownloadStatus.FAILED, errorMessage = "保存できるファイルが見つかりませんでした")
                }
                notifyResult(job.id, success = false, job.url)
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
            DownloadRepository.update(job.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = e.message ?: "ダウンロードに失敗しました")
            }
            notifyResult(job.id, success = false, job.url)
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            DownloadRepository.update(job.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = e.message ?: "予期しないエラーが発生しました")
            }
            notifyResult(job.id, success = false, job.url)
        }
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

    private fun notifyResult(jobId: String, success: Boolean, url: String) {
        val notification = NotificationCompat.Builder(this, MediaGetApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(if (success) "保存しました" else "失敗しました")
            .setContentText(url)
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

        fun enqueue(context: Context, item: DownloadItem) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_URL, item.url)
                putExtra(EXTRA_MODE, item.mode.name)
                putExtra(EXTRA_COMPRESS, item.compressImages)
                putExtra(EXTRA_PROCESS_ID, item.processId)
                item.playlistItems?.let { putExtra(EXTRA_PLAYLIST_ITEMS, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

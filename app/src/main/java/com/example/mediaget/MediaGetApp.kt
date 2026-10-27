package com.example.mediaget

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. yt-dlp (bundled by the youtubedl-android library)
 * and ffmpeg both ship their own binaries inside the APK and need a one-time
 * on-device initialization before the first download.
 */
class MediaGetApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "yt-dlp / ffmpeg initialization failed", e)
        }

        warmUpWebViewSubsystem()
        createDownloadNotificationChannel()

        // The yt-dlp binary bundled inside the APK is frozen at build time and
        // goes stale fast — Instagram/TikTok change their site internals often
        // enough that an old yt-dlp simply can't extract from them anymore
        // ("Your yt-dlp version is older than 90 days!"). Pulling the latest
        // release from GitHub on every app start keeps it working without
        // needing a fresh APK build each time.
        appScope.launch {
            try {
                // NIGHTLY (not STABLE): Instagram photo/carousel-image posts only
                // download at all when yt-dlp's --write-thumbnail +
                // --ignore-no-formats-error workaround is backed by a nightly
                // build — the yt-dlp maintainers have said the stable channel
                // will not reliably carry this fix (see yt-dlp issue #17133).
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    this@MediaGetApp,
                    YoutubeDL.UpdateChannel.NIGHTLY
                )
                Log.i(TAG, "yt-dlp update check: $status")
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update check failed (will keep using the current version)", e)
            }
        }
    }

    /**
     * The bug this fixes: sharing a link straight to MediaGet (ShareReceiverActivity,
     * which never opens any screen or creates a WebView) could fail with an
     * error, while pasting the exact same link into "リンクで取得" after having
     * opened the app normally worked fine. The difference was never the link
     * itself — it's that CookieExporter/HeadlessPageLoader read WebView's
     * CookieManager from DownloadService's background thread, and Android's
     * WebView subsystem needs its *first-ever* setup in a process to happen
     * on the main thread. When MainActivity's "ブラウザ" タブ had already
     * created a WebView at some point, that setup was already done; when the
     * share receiver was the very first thing to start the process, nothing
     * had ever touched WebView yet, and the background thread trying to do
     * that first-time setup itself is what could fail. Application.onCreate()
     * always runs on the main thread, so doing this tiny, harmless warm-up
     * here — regardless of which entry point (MainActivity or
     * ShareReceiverActivity) actually started the process — makes sure it's
     * always already done by the time any background code needs it.
     */
    private fun warmUpWebViewSubsystem() {
        runCatching {
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.WebView(this).destroy()
        }.onFailure { e ->
            Log.w(TAG, "WebView warm-up failed (cookie-dependent downloads may be affected)", e)
        }
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.download_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MediaGetApp"
        const val DOWNLOAD_CHANNEL_ID = "downloads"
    }
}

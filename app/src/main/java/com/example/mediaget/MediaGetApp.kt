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

        createDownloadNotificationChannel()

        // The yt-dlp binary bundled inside the APK is frozen at build time and
        // goes stale fast — Instagram/TikTok change their site internals often
        // enough that an old yt-dlp simply can't extract from them anymore
        // ("Your yt-dlp version is older than 90 days!"). Pulling the latest
        // release from GitHub on every app start keeps it working without
        // needing a fresh APK build each time.
        appScope.launch {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    this@MediaGetApp,
                    YoutubeDL.UpdateChannel.STABLE
                )
                Log.i(TAG, "yt-dlp update check: $status")
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update check failed (will keep using the current version)", e)
            }
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

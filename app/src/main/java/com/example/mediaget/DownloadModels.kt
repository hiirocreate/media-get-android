package com.example.mediaget

import android.net.Uri
import java.util.UUID

/** What the user asked yt-dlp to extract. */
enum class DownloadMode {
    AUTO,        // whatever the link points to (video, image post, gallery…)
    VIDEO,       // force best video+audio muxed to mp4
    AUDIO_ONLY;  // extract audio track only (mp3)

    fun label(): String = when (this) {
        AUTO -> "自動"
        VIDEO -> "動画 (MP4)"
        AUDIO_ONLY -> "音声のみ (MP3)"
    }
}

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    SAVING,
    DONE,
    FAILED,
    CANCELED
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val mode: DownloadMode,
    val compressImages: Boolean,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Float = 0f,
    val title: String? = null,
    val errorMessage: String? = null,
    val savedFileUris: List<Uri> = emptyList(),
    /** yt-dlp process id, used to support cancellation while RUNNING. */
    val processId: String = UUID.randomUUID().toString(),
    /**
     * 1-based indices (yt-dlp `--playlist-items` syntax, e.g. "1,3,4") of the
     * entries to fetch when the source page contains multiple media items and
     * the user picked only some of them. Null/blank means "download everything
     * yt-dlp finds at this URL" (the normal single-item case).
     */
    val playlistItems: String? = null
)

/** One media item detected at a URL before anything is downloaded. */
data class MediaEntry(
    val index: Int,
    val title: String
)

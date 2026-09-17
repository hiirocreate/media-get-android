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
    val playlistItems: String? = null,
    /**
     * When set, DownloadService fetches this URL directly instead of running
     * yt-dlp at all. See [DirectVideoSource] for why this exists.
     */
    val directSource: DirectVideoSource? = null
)

/**
 * A video file URL captured straight out of the in-app browser's own network
 * traffic while the user had a post open — see the WebViewClient override in
 * BrowserScreen.kt. This exists because yt-dlp's *own* HTTP requests (which
 * don't carry a real browser's TLS/header fingerprint) are what TikTok's
 * anti-bot defenses have been blocking — "the extractor is attempting
 * impersonation, but no impersonate target is available" and the resulting
 * HTTP 403s are a confirmed, still-open upstream limitation specifically on
 * Android (impersonation there needs curl_cffi, which isn't available to
 * youtubedl-android). The WebView's own request for the exact same file
 * succeeds because it *is* a real browser request — so instead of asking
 * yt-dlp to re-derive and re-request the video, this replays that same
 * already-made request (same URL, same headers, including the logged-in
 * session's cookies) directly. It only ever holds the one video URL the
 * WebView already requested on the page the user is currently looking at —
 * nothing here fetches, lists, or discovers anything beyond that.
 */
data class DirectVideoSource(
    val videoUrl: String,
    val headers: Map<String, String>
)

/** One media item detected at a URL before anything is downloaded. */
data class MediaEntry(
    val index: Int,
    val title: String
)

package com.example.mediaget

import android.content.Context

/**
 * Single entry point used by every UI surface (the paste-a-link screen, the
 * in-app browser, and the silent share receiver) to queue a download. Keeping
 * it in one place means all three paths get the same defaults and behavior.
 */
object DownloadActions {

    fun submit(
        context: Context,
        url: String,
        mode: DownloadMode = DownloadMode.AUTO,
        compressImages: Boolean = true,
        playlistItems: String? = null
    ): DownloadItem? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val item = DownloadItem(
            url = trimmed,
            mode = mode,
            compressImages = compressImages,
            playlistItems = playlistItems
        )
        DownloadRepository.add(item)
        DownloadService.enqueue(context, item)
        return item
    }
}

package com.example.mediaget

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Copies a file that yt-dlp produced in the app's private, permission-free
 * external cache into the public "Downloads" collection via MediaStore, so it
 * shows up in the user's Files app / Downloads folder like a normal download.
 *
 * Requires API 29+ (see minSdk in build.gradle.kts) — MediaStore.Downloads
 * did not exist before scoped storage.
 */
object MediaStoreUtils {

    fun guessMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "mp4", "mkv", "webm" -> "video/mp4"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
    }

    fun saveToPublicDownloads(context: Context, sourceFile: File, subFolder: String = "MediaGet"): Uri? {
        val mimeType = guessMimeType(sourceFile)
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$subFolder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        resolver.openOutputStream(itemUri)?.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
        } ?: return null

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        return itemUri
    }
}

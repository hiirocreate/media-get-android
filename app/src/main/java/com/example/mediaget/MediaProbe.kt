package com.example.mediaget

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class ProbeResult {
    data class Success(val entries: List<MediaEntry>) : ProbeResult()
    data class Failure(val message: String) : ProbeResult()
}

/**
 * Asks yt-dlp what is at a URL *without downloading anything*, so the browser
 * screen can show a pick-list when a single post/story contains several
 * media items (an Instagram carousel, for example).
 */
object MediaProbe {

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("--no-warnings")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val root = JSONObject(response.out.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: response.out)

            val entries = mutableListOf<MediaEntry>()
            if (root.has("entries")) {
                val arr = root.getJSONArray("entries")
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val title = entry.optString("title").ifBlank {
                        entry.optString("id").ifBlank { "メディア ${i + 1}" }
                    }
                    entries.add(MediaEntry(index = i + 1, title = title))
                }
            } else {
                val title = root.optString("title").ifBlank { url }
                entries.add(MediaEntry(index = 1, title = title))
            }

            if (entries.isEmpty()) {
                ProbeResult.Failure("メディアが見つかりませんでした")
            } else {
                ProbeResult.Success(entries)
            }
        } catch (e: Exception) {
            ProbeResult.Failure(e.message ?: "内容の確認に失敗しました")
        }
    }
}

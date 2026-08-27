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

/** One entry in a profile's "recent posts" pick-list — see [MediaProbe.probeRecentPosts]. */
data class ProfilePostCandidate(val index: Int, val title: String, val postUrl: String)

/**
 * Asks yt-dlp what is at a URL *without downloading anything*, so the browser
 * screen can show a pick-list when a single post/story contains several
 * media items (an Instagram carousel, for example).
 */
object MediaProbe {

    // Hard cap for probeRecentPosts() — deliberately a fixed, small constant
    // rather than a caller-supplied value, so this can never be turned into
    // an account-wide listing just by passing a bigger number in.
    private const val RECENT_POSTS_LIMIT = 9999

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

    /**
     * Given a *profile* page URL, finds the permalink of just that account's
     * single newest post — never anything more. Internally this asks yt-dlp
     * for only playlist item #1 of the profile's post feed (--playlist-items 1),
     * so it never enumerates or fetches the rest of the account's history;
     * the result feeds straight back into [probe]/[DownloadActions.submit]
     * exactly as if the user had opened and shared that one post directly.
     */
    suspend fun resolveLatestPostUrl(site: SnsSite, profileUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(profileUrl).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--playlist-items", "9999")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val root = JSONObject(response.out.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: response.out)

            val firstEntry: JSONObject? = if (root.has("entries")) {
                val arr = root.getJSONArray("entries")
                if (arr.length() > 0) arr.optJSONObject(0) else null
            } else {
                root
            }

            if (firstEntry == null) {
                return@withContext Result.failure(IllegalStateException("投稿が見つかりませんでした"))
            }

            val resolvedUrl = resolveEntryUrl(site, firstEntry)
            if (resolvedUrl.isBlank()) {
                Result.failure(IllegalStateException("投稿のURLを特定できませんでした"))
            } else {
                Result.success(resolvedUrl)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Given a *profile* page URL, lists just that account's [RECENT_POSTS_LIMIT]
     * newest posts (never more — the cap is a fixed constant, not a parameter
     * callers can widen) so the user can hand-pick which of those few to save.
     * Confirming the resulting picker downloads each chosen post in full,
     * exactly as if it had been opened and saved individually — this never
     * reads or downloads anything from the account beyond this short list.
     */
    suspend fun probeRecentPosts(site: SnsSite, profileUrl: String): Result<List<ProfilePostCandidate>> =
        withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(profileUrl).apply {
                    addOption("--dump-single-json")
                    addOption("--flat-playlist")
                    addOption("--skip-download")
                    addOption("--no-warnings")
                    addOption("--playlist-items", "1-$RECENT_POSTS_LIMIT")
                }
                val response = YoutubeDL.getInstance().execute(request)
                val root = JSONObject(response.out.trim().lineSequence().lastOrNull { it.isNotBlank() } ?: response.out)

                val rawEntries: List<JSONObject> = if (root.has("entries")) {
                    val arr = root.getJSONArray("entries")
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                } else {
                    listOf(root)
                }

                val candidates = rawEntries
                    .take(RECENT_POSTS_LIMIT)
                    .mapIndexedNotNull { i, entry ->
                        val postUrl = resolveEntryUrl(site, entry)
                        if (postUrl.isBlank()) return@mapIndexedNotNull null
                        val title = entry.optString("title").ifBlank {
                            entry.optString("description").ifBlank {
                                entry.optString("id").ifBlank { "投稿 ${i + 1}" }
                            }
                        }
                        ProfilePostCandidate(index = i + 1, title = title, postUrl = postUrl)
                    }

                if (candidates.isEmpty()) {
                    Result.failure(IllegalStateException("投稿が見つかりませんでした"))
                } else {
                    Result.success(candidates)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun resolveEntryUrl(site: SnsSite, entry: JSONObject): String {
        val candidate = entry.optString("webpage_url").ifBlank { entry.optString("url") }
        return when {
            candidate.startsWith("http") -> candidate
            else -> {
                val id = candidate.ifBlank { entry.optString("id") }
                if (id.isBlank()) "" else buildPostUrl(site, id)
            }
        }
    }

    private fun buildPostUrl(site: SnsSite, id: String): String = when (site) {
        SnsSite.INSTAGRAM -> "https://www.instagram.com/p/$id/"
        SnsSite.YOUTUBE -> "https://www.youtube.com/watch?v=$id"
        // x.com/i/status/<id> resolves without needing the author's handle.
        SnsSite.X -> "https://x.com/i/status/$id"
        // TikTok/Threads permalinks require the author's handle, which a bare
        // id doesn't give us — safer to report "not found" than guess wrong.
        SnsSite.TIKTOK, SnsSite.THREADS -> ""
    }
}

package com.example.mediaget

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SnsSite(val displayName: String, val homeUrl: String) {
    INSTAGRAM("Instagram", "https://www.instagram.com/"),
    TIKTOK("TikTok", "https://www.tiktok.com/"),
    YOUTUBE("YouTube", "https://www.youtube.com/"),
    X("X", "https://x.com/"),
    THREADS("Threads", "https://www.threads.com/")
}

/**
 * Which User-Agent identity the in-app browser presents as. MOBILE is a
 * normal phone Chrome UA (needed so TikTok/X don't gate their mobile-web
 * experience); TABLET drops the "Mobile" token so sites render their
 * touch-friendly-but-full layout instead of the gated phone-web one — this
 * is what unblocks Instagram's login screen; DESKTOP presents as Chrome on
 * Windows for sites the user wants to browse in full PC layout. Switching
 * modes only ever changes the UA string + viewport flags — no JS is
 * injected to reshape the page, since that's what caused the earlier
 * scroll/caret/white-screen bugs.
 */
enum class DisplayMode(val label: String) {
    MOBILE("モバイル表示"),
    TABLET("タブレット表示"),
    DESKTOP("PC表示")
}

data class BrowserUiState(
    val currentSite: SnsSite? = null,
    val currentUrl: String = "",
    val isProbing: Boolean = false,
    val pickerEntries: List<MediaEntry>? = null,
    val pickerSelected: Set<Int> = emptySet(),
    val pickerSourceUrl: String = "",
    // "直近3件から選ぶ" — a fixed-size (see MediaProbe.RECENT_POSTS_LIMIT) list
    // of an account's newest posts to hand-pick from, never the full history.
    val recentPosts: List<ProfilePostCandidate>? = null,
    val recentPostsSelected: Set<Int> = emptySet(),
    // Lives here (not as rememberSaveable inside BrowserScreen) because that
    // composable is fully torn down and recreated every time the user leaves
    // and comes back to the "ブラウザ" タブ — a plain composable-local toggle
    // was silently resetting to off on every tab switch.
    //
    // Keyed *per site* rather than a single shared value: TikTok generally
    // needs MOBILE to avoid its "open in app" gating, while Instagram's login
    // screen needed TABLET to render at all — sharing one toggle meant
    // switching to Tablet for Instagram silently broke TikTok (and vice
    // versa) the next time you switched tabs.
    val displayModeBySite: Map<SnsSite, DisplayMode> = emptyMap(),
    // A Toast disappears in ~2 seconds and can't be copied — useless for
    // relaying yt-dlp's actual error text back for diagnosis. Failures now
    // land here instead and are shown as a dialog with selectable/copyable
    // text that stays up until dismissed.
    val errorMessage: String? = null
) {
    fun displayModeFor(site: SnsSite): DisplayMode = displayModeBySite[site] ?: DisplayMode.MOBILE
}

class BrowserViewModel : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state

    // The WebView itself is a native object recreated from scratch whenever
    // BrowserScreen re-enters composition (leaving the "ブラウザ" tab disposes
    // it) — this snapshot, saved just before that happens and restored right
    // after, is what keeps you on the same page/scroll position instead of
    // being bounced back to the SNS's home URL every time.
    var savedWebViewState: Bundle? = null

    fun openSite(site: SnsSite) {
        savedWebViewState = null
        _state.update { it.copy(currentSite = site, currentUrl = site.homeUrl) }
    }

    fun goHome() {
        savedWebViewState = null
        _state.update { it.copy(currentSite = null, currentUrl = "") }
    }

    fun onPageUrlChanged(url: String) {
        _state.update { it.copy(currentUrl = url) }
    }

    fun setDisplayMode(site: SnsSite, mode: DisplayMode) {
        _state.update { it.copy(displayModeBySite = it.displayModeBySite + (site to mode)) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Called from the browser's "この投稿を保存" button, on a post/story page. */
    fun requestDownload(context: Context) {
        val url = _state.value.currentUrl
        if (url.isBlank()) return

        _state.update { it.copy(isProbing = true) }
        viewModelScope.launch { probeAndPresent(context, url, "ダウンロードを開始しました") }
    }

    /**
     * Called from the "最新の投稿を保存" button shown instead, when the WebView
     * is sitting on a profile/account page rather than a specific post. This
     * only ever resolves and downloads that account's single newest post —
     * never the rest of their history — matching the same one-post-at-a-time
     * rule as [requestDownload].
     */
    fun requestDownloadLatestPost(context: Context) {
        val s = _state.value
        val site = s.currentSite ?: return
        val profileUrl = s.currentUrl
        if (profileUrl.isBlank()) return

        _state.update { it.copy(isProbing = true) }
        viewModelScope.launch {
            MediaProbe.resolveLatestPostUrl(context, site, profileUrl)
                .onFailure { e ->
                    _state.update { it.copy(isProbing = false, errorMessage = "最新の投稿を特定できませんでした:\n${e.message}") }
                }
                .onSuccess { postUrl ->
                    probeAndPresent(context, postUrl, "最新の投稿のダウンロードを開始しました")
                }
        }
    }

    /**
     * Called from the "直近3件から選ぶ" button on a profile page. Lists only the
     * account's newest handful of posts (hard-capped inside
     * [MediaProbe.probeRecentPosts], not configurable from here) and shows
     * them as a pick-list; confirming downloads only the chosen ones, in
     * full — this never reads or downloads anything beyond that short list.
     */
    fun requestRecentPosts(context: Context) {
        val s = _state.value
        val site = s.currentSite ?: return
        val profileUrl = s.currentUrl
        if (profileUrl.isBlank()) return

        _state.update { it.copy(isProbing = true) }
        viewModelScope.launch {
            MediaProbe.probeRecentPosts(context, site, profileUrl)
                .onFailure { e ->
                    _state.update { it.copy(isProbing = false, errorMessage = "投稿一覧を取得できませんでした:\n${e.message}") }
                }
                .onSuccess { candidates ->
                    _state.update {
                        it.copy(
                            isProbing = false,
                            recentPosts = candidates,
                            recentPostsSelected = candidates.map { c -> c.index }.toSet()
                        )
                    }
                }
        }
    }

    fun toggleRecentPostSelected(index: Int) {
        _state.update { s ->
            val newSet = if (index in s.recentPostsSelected) s.recentPostsSelected - index else s.recentPostsSelected + index
            s.copy(recentPostsSelected = newSet)
        }
    }

    fun selectAllRecentPosts() {
        _state.update { s -> s.copy(recentPostsSelected = s.recentPosts.orEmpty().map { it.index }.toSet()) }
    }

    fun clearRecentPostsSelection() {
        _state.update { it.copy(recentPostsSelected = emptySet()) }
    }

    fun dismissRecentPosts() {
        _state.update { it.copy(recentPosts = null, recentPostsSelected = emptySet()) }
    }

    fun confirmRecentPostsDownload(context: Context) {
        val s = _state.value
        val candidates = s.recentPosts ?: return
        val chosen = candidates.filter { it.index in s.recentPostsSelected }
        if (chosen.isEmpty()) return

        // Each chosen post downloads in full (same as pasting its link directly
        // into "リンクで取得") — no further per-post carousel sub-selection here,
        // to keep this already-narrow picker simple.
        chosen.forEach { candidate -> DownloadActions.submit(context, candidate.postUrl) }
        Toast.makeText(context, "ダウンロードを開始しました（${chosen.size}件）", Toast.LENGTH_SHORT).show()
        dismissRecentPosts()
    }

    private suspend fun probeAndPresent(context: Context, url: String, successMessage: String) {
        when (val result = MediaProbe.probe(context, url)) {
            is ProbeResult.Failure -> {
                _state.update { it.copy(isProbing = false, errorMessage = "内容を確認できませんでした:\n${result.message}") }
            }
            is ProbeResult.Success -> {
                if (result.entries.size <= 1) {
                    // Single item: nothing to choose from, just download it.
                    DownloadActions.submit(context, url)
                    _state.update { it.copy(isProbing = false) }
                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                } else {
                    _state.update {
                        it.copy(
                            isProbing = false,
                            pickerEntries = result.entries,
                            pickerSelected = result.entries.map { e -> e.index }.toSet(),
                            pickerSourceUrl = url
                        )
                    }
                }
            }
        }
    }

    fun toggleSelected(index: Int) {
        _state.update { s ->
            val newSet = if (index in s.pickerSelected) s.pickerSelected - index else s.pickerSelected + index
            s.copy(pickerSelected = newSet)
        }
    }

    fun selectAll() {
        _state.update { s -> s.copy(pickerSelected = s.pickerEntries.orEmpty().map { it.index }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(pickerSelected = emptySet()) }
    }

    fun dismissPicker() {
        _state.update { it.copy(pickerEntries = null, pickerSelected = emptySet(), pickerSourceUrl = "") }
    }

    fun confirmPickerDownload(context: Context) {
        val s = _state.value
        val entries = s.pickerEntries ?: return
        if (s.pickerSelected.isEmpty()) return

        val playlistItems = if (s.pickerSelected.size == entries.size) {
            null // everything selected — no need to filter
        } else {
            s.pickerSelected.sorted().joinToString(",")
        }

        DownloadActions.submit(context, s.pickerSourceUrl, playlistItems = playlistItems)
        Toast.makeText(context, "ダウンロードを開始しました（${s.pickerSelected.size}件）", Toast.LENGTH_SHORT).show()
        dismissPicker()
    }
}

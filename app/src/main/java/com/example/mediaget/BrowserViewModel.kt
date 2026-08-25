package com.example.mediaget

import android.content.Context
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

data class BrowserUiState(
    val currentSite: SnsSite? = null,
    val currentUrl: String = "",
    val isProbing: Boolean = false,
    val pickerEntries: List<MediaEntry>? = null,
    val pickerSelected: Set<Int> = emptySet(),
    val pickerSourceUrl: String = ""
)

class BrowserViewModel : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state

    fun openSite(site: SnsSite) {
        _state.update { it.copy(currentSite = site, currentUrl = site.homeUrl) }
    }

    fun goHome() {
        _state.update { it.copy(currentSite = null, currentUrl = "") }
    }

    fun onPageUrlChanged(url: String) {
        _state.update { it.copy(currentUrl = url) }
    }

    /** Called from the browser's "この投稿をダウンロード" button. */
    fun requestDownload(context: Context) {
        val url = _state.value.currentUrl
        if (url.isBlank()) return

        _state.update { it.copy(isProbing = true) }
        viewModelScope.launch {
            when (val result = MediaProbe.probe(url)) {
                is ProbeResult.Failure -> {
                    _state.update { it.copy(isProbing = false) }
                    Toast.makeText(context, "内容を確認できませんでした: ${result.message}", Toast.LENGTH_SHORT).show()
                }
                is ProbeResult.Success -> {
                    if (result.entries.size <= 1) {
                        // Single item: nothing to choose from, just download it.
                        DownloadActions.submit(context, url)
                        _state.update { it.copy(isProbing = false) }
                        Toast.makeText(context, "ダウンロードを開始しました", Toast.LENGTH_SHORT).show()
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

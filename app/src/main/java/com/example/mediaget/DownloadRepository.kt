package com.example.mediaget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide, in-memory list of downloads. Both the foreground [DownloadService]
 * (producer) and the Compose UI (consumer) observe the same [StateFlow], so
 * progress keeps updating even if the activity is recreated or backgrounded.
 */
object DownloadRepository {

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads

    fun add(item: DownloadItem) {
        _downloads.update { it + item }
    }

    fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun remove(id: String) {
        _downloads.update { list -> list.filterNot { it.id == id } }
    }
}

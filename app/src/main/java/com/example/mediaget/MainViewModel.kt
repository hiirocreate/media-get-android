package com.example.mediaget

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {

    val downloads: StateFlow<List<DownloadItem>> = DownloadRepository.downloads

    fun startDownload(
        context: Context,
        url: String,
        mode: DownloadMode,
        compressImages: Boolean
    ) {
        DownloadActions.submit(context, url, mode, compressImages)
    }

    fun dismiss(id: String) {
        DownloadRepository.remove(id)
    }
}

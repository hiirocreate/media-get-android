package com.example.mediaget

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAdded: Long
)

/**
 * Reads back everything MediaGet has ever saved into Download/MediaGet via
 * MediaStore — unlike the in-memory download history on the "リンクで取得"
 * tab, this survives app restarts because it's just the real files on disk.
 */
class MediaLibraryViewModel : ViewModel() {

    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun refresh(context: Context) {
        viewModelScope.launch {
            _loading.value = true
            _items.value = withContext(Dispatchers.IO) { queryLibrary(context) }
            _loading.value = false
        }
    }

    fun delete(context: Context, uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            refresh(context)
        }
    }

    private fun queryLibrary(context: Context): List<LibraryItem> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Download/MediaGet%")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        val result = mutableListOf<LibraryItem>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result.add(
                    LibraryItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = cursor.getString(nameCol) ?: "",
                        mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        dateAdded = cursor.getLong(dateCol)
                    )
                )
            }
        }
        return result
    }
}

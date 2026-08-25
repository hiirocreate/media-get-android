package com.example.mediaget

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private fun iconFor(site: SnsSite): ImageVector = when (site) {
    SnsSite.INSTAGRAM -> Icons.Filled.PhotoCamera
    SnsSite.TIKTOK -> Icons.Filled.MusicNote
    SnsSite.YOUTUBE -> Icons.Filled.PlayCircleFilled
    SnsSite.X -> Icons.Filled.AlternateEmail
    SnsSite.THREADS -> Icons.Filled.Forum
}

@Composable
fun BrowserScreen(viewModel: BrowserViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = state.currentSite != null) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else viewModel.goHome()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val site = state.currentSite
        if (site == null) {
            BrowserHome(onSelect = { viewModel.openSite(it) })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = { viewModel.goHome() }) {
                        Icon(Icons.Filled.Home, contentDescription = "ホーム")
                    }
                    IconButton(onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                    Text(
                        text = site.displayName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(onClick = { viewModel.requestDownload(context) }, enabled = !state.isProbing) {
                        if (state.isProbing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.GetApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(" この投稿を保存")
                        }
                    }
                }

                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url != null) viewModel.onPageUrlChanged(url)
                                }
                            }
                            loadUrl(site.homeUrl)
                            webViewRef = this
                        }
                    }
                )

                BrowserControlBar(
                    current = site,
                    onSelect = { selected ->
                        viewModel.openSite(selected)
                        webViewRef?.loadUrl(selected.homeUrl)
                    }
                )
            }
        }

        if (state.pickerEntries != null) {
            MediaPickerDialog(
                entries = state.pickerEntries.orEmpty(),
                selected = state.pickerSelected,
                onToggle = viewModel::toggleSelected,
                onSelectAll = viewModel::selectAll,
                onClearAll = viewModel::clearSelection,
                onConfirm = { viewModel.confirmPickerDownload(context) },
                onDismiss = viewModel::dismissPicker
            )
        }
    }
}

@Composable
private fun BrowserHome(onSelect: (SnsSite) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "SNSを選んでください（ログイン状態は保持されます）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(SnsSite.values().toList()) { site ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(onClick = { onSelect(site) }) {
                            Icon(iconFor(site), contentDescription = site.displayName, modifier = Modifier.size(36.dp))
                        }
                        Text(site.displayName)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserControlBar(current: SnsSite, onSelect: (SnsSite) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SnsSite.values().forEach { site ->
            IconButton(onClick = { onSelect(site) }) {
                Icon(
                    iconFor(site),
                    contentDescription = site.displayName,
                    tint = if (site == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MediaPickerDialog(
    entries: List<MediaEntry>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ダウンロードするメディアを選択（${selected.size} / ${entries.size} 件）") },
        text = {
            Column {
                Row {
                    TextButton(onClick = onSelectAll) { Text("全選択") }
                    TextButton(onClick = onClearAll) { Text("全解除") }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(entries) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = entry.index in selected,
                                onCheckedChange = { onToggle(entry.index) }
                            )
                            Text(
                                text = entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = selected.isNotEmpty()) {
                Text("ダウンロード")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" キャンセル")
            }
        }
    )
}

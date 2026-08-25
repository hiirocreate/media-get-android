package com.example.mediaget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mediaget.ui.theme.MediaGetTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val browserViewModel: BrowserViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        val sharedUrl = extractSharedUrl(intent)

        setContent {
            MediaGetTheme {
                MediaGetApp(
                    mainViewModel = viewModel,
                    browserViewModel = browserViewModel,
                    initialUrl = sharedUrl
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new shared link while the app is already open just repopulates the
        // text field the next time the screen recomposes from a fresh launch;
        // handling live re-injection is out of scope for this MVP.
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun extractSharedUrl(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        if (intent.type != "text/plain") return ""
        return intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }
}

private enum class AppTab(val label: String) {
    LINK("リンクで取得"),
    BROWSER("ブラウザ")
}

@Composable
fun MediaGetApp(mainViewModel: MainViewModel, browserViewModel: BrowserViewModel, initialUrl: String) {
    var tab by rememberSaveable { mutableStateOf(AppTab.LINK) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.LINK,
                    onClick = { tab = AppTab.LINK },
                    icon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    label = { Text(AppTab.LINK.label) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.BROWSER,
                    onClick = { tab = AppTab.BROWSER },
                    icon = { Icon(Icons.Filled.Language, contentDescription = null) },
                    label = { Text(AppTab.BROWSER.label) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.LINK -> DownloadScreen(viewModel = mainViewModel, initialUrl = initialUrl)
                AppTab.BROWSER -> BrowserScreen(viewModel = browserViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: MainViewModel, initialUrl: String) {
    val downloads by viewModel.downloads.collectAsState()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var mode by rememberSaveable { mutableStateOf(DownloadMode.AUTO) }
    var compressImages by rememberSaveable { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResourceAppName()) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Instagram / YouTube / TikTok のリンク") },
                singleLine = true,
                trailingIcon = {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { url = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "クリア")
                        }
                    }
                }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DownloadMode.values().forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label()) }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(checked = compressImages, onCheckedChange = { compressImages = it })
                Text("画像は軽量化して保存する（サイズを縮小）")
            }

            Button(
                onClick = {
                    viewModel.startDownload(context, url, mode, compressImages)
                    url = ""
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ダウンロード")
            }

            Text(
                text = "自分に権利がある、または許諾を得たコンテンツのみをダウンロードしてください。各サービスの利用規約や著作権法を遵守する責任は利用者にあります。",
                style = MaterialTheme.typography.bodySmall
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(downloads.asReversed(), key = { it.id }) { item ->
                    DownloadRow(item = item, onDismiss = { viewModel.dismiss(item.id) })
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.title ?: item.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.status == DownloadStatus.DONE || item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELED) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "削除")
                    }
                }
            }

            Text(text = "${item.mode.label()} · ${statusLabel(item.status)}", style = MaterialTheme.typography.bodySmall)

            when (item.status) {
                DownloadStatus.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { item.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                DownloadStatus.QUEUED, DownloadStatus.SAVING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                DownloadStatus.FAILED -> {
                    Text(
                        text = item.errorMessage ?: "エラーが発生しました",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                DownloadStatus.DONE -> {
                    TextButton(onClick = {
                        item.savedFileUris.firstOrNull()?.let { uri -> openUri(context, uri) }
                    }) {
                        Text("保存先を開く")
                    }
                }
                DownloadStatus.CANCELED -> Unit
            }
        }
    }
}

private fun openUri(context: android.content.Context, uri: Uri) {
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(viewIntent) }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "待機中"
    DownloadStatus.RUNNING -> "ダウンロード中"
    DownloadStatus.SAVING -> "保存中"
    DownloadStatus.DONE -> "完了"
    DownloadStatus.FAILED -> "失敗"
    DownloadStatus.CANCELED -> "キャンセル"
}

@Composable
private fun stringResourceAppName(): String = androidx.compose.ui.res.stringResource(id = R.string.app_name)

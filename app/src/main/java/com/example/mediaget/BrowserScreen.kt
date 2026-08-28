package com.example.mediaget

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A stock modern-Chrome-on-Android UA string. Sites use the UA to detect
 * "in-app browsers" (WebViews embedded inside other apps) and deliberately
 * cripple them — this makes MediaGet's browser present as a normal phone
 * browser instead, which is what fixes TikTok/X's blocked scrolling and
 * "open in app" nags.
 */
private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

/**
 * Same idea as before (no "Mobile" token, so sites treat this as a tablet-
 * class touch browser rather than the phone-web experience they gate), but
 * the device name now matches the actual phone this app runs on (OUKITEL
 * C50) instead of a made-up Samsung tablet model. A UA claiming a device
 * that doesn't match the real hardware is itself one more signal a site's
 * fraud/device-recognition system can use to flag "unrecognized device" —
 * this narrows that gap while keeping the non-"Mobile" trick that gets the
 * login screen to render at all.
 */
private const val TABLET_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; OUKITEL C50) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/** Applies the phone/tablet presentation to this WebView. Returns true if anything changed. */
private fun WebView.applyDisplayMode(tabletMode: Boolean): Boolean {
    val desiredUa = if (tabletMode) TABLET_CHROME_USER_AGENT else MOBILE_CHROME_USER_AGENT
    val changed = settings.userAgentString != desiredUa
    settings.userAgentString = desiredUa
    // Lets WebView honor whatever <meta name="viewport"> each site ships on
    // its own tablet layout — we no longer inject or override one ourselves.
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    return changed
}

/**
 * Heuristic: is the WebView currently sitting on an account/profile page
 * (e.g. instagram.com/someuser/) rather than a specific post/reel/video?
 * Used only to decide the save button's label and target — it never triggers
 * any enumeration by itself, and profile pages that don't match still fall
 * back to treating the current page as a normal single post.
 */
private fun isProfileUrl(site: SnsSite, url: String): Boolean {
    if (url.isBlank()) return false
    val path = url.substringBefore("?").substringBefore("#").trimEnd('/')
    return when (site) {
        SnsSite.INSTAGRAM -> {
            val m = Regex("""^https?://(www\.)?instagram\.com/([A-Za-z0-9_.]+)$""").find(path)
            val reserved = setOf("explore", "accounts", "direct", "reels", "reel", "stories", "p", "tv", "about", "legal", "developer")
            m != null && m.groupValues[2].lowercase() !in reserved
        }
        SnsSite.TIKTOK -> Regex("""^https?://(www\.)?tiktok\.com/@[\w.\-]+$""").matches(path)
        SnsSite.YOUTUBE -> Regex("""^https?://(www\.)?youtube\.com/(@[\w.\-]+|channel/[\w\-]+|c/[\w\-]+)$""").matches(path)
        SnsSite.X -> {
            val m = Regex("""^https?://(www\.)?x\.com/([A-Za-z0-9_]+)$""").find(path)
            val reserved = setOf("home", "explore", "notifications", "messages", "i", "search", "settings", "compose")
            m != null && m.groupValues[2].lowercase() !in reserved
        }
        SnsSite.THREADS -> Regex("""^https?://(www\.)?threads\.(net|com)/@[\w.\-]+$""").matches(path)
    }
}

private fun drawableFor(site: SnsSite): Int = when (site) {
    SnsSite.INSTAGRAM -> R.drawable.ic_sns_instagram
    SnsSite.TIKTOK -> R.drawable.ic_sns_tiktok
    SnsSite.YOUTUBE -> R.drawable.ic_sns_youtube
    SnsSite.X, SnsSite.THREADS -> 0 // rendered as text glyphs instead, see SnsGlyph()
}

/**
 * X and Threads' actual marks are just a bold letterform ("X" / a stylized
 * "@"), so those two are drawn as plain text — simpler and always crisp at
 * any size. Instagram/TikTok/YouTube get a small custom vector icon instead
 * of a generic Material icon so they're recognizable at a glance.
 */
@Composable
private fun SnsGlyph(site: SnsSite, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    when (site) {
        SnsSite.X -> Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text("X", fontSize = (size.value * 0.75f).sp, fontWeight = FontWeight.Black)
        }
        SnsSite.THREADS -> Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text("@", fontSize = (size.value * 0.85f).sp, fontWeight = FontWeight.Black)
        }
        else -> Icon(
            painter = painterResource(id = drawableFor(site)),
            contentDescription = site.displayName,
            modifier = modifier.size(size),
            tint = androidx.compose.ui.graphics.Color.Unspecified
        )
    }
}

@Composable
fun BrowserScreen(viewModel: BrowserViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var tabletMode by rememberSaveable { mutableStateOf(false) }

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
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = { viewModel.goHome() }) {
                        Icon(Icons.Filled.Home, contentDescription = "ホーム")
                    }
                    IconButton(onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                    // Some flows (e.g. Instagram's "承認してください" other-device
                    // approval) update on the server side but don't push that
                    // change back into an already-open page — reloading is the
                    // manual way to pick up the new state without navigating away.
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "更新")
                    }
                    Text(
                        text = site.displayName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    FilterChip(
                        selected = tabletMode,
                        onClick = { tabletMode = !tabletMode },
                        label = { Text("タブレット表示") }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.setSupportMultipleWindows(true)
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        if (url != null) viewModel.onPageUrlChanged(url)
                                    }

                                    // TikTok (and some other sites) try to bounce the browser
                                    // straight to a custom app-deep-link scheme (tiktok://,
                                    // intent://, etc.) as their "open in app" mechanism. A plain
                                    // WebView tries to actually navigate to that scheme and fails
                                    // with net::ERR_UNKNOWN_URL_SCHEME, blanking the whole page —
                                    // this just ignores anything that isn't a normal web address
                                    // instead, so the page underneath keeps loading normally.
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: android.webkit.WebResourceRequest
                                    ): Boolean {
                                        val scheme = request.url.scheme?.lowercase()
                                        return scheme != "http" && scheme != "https"
                                    }
                                }
                                // Login flows (Google/Apple sign-in popups, etc.) often open
                                // via window.open() / target="_blank". A plain WebView drops
                                // those silently, so route the popup's URL back into this
                                // same WebView instead of losing it.
                                webChromeClient = object : WebChromeClient() {
                                    override fun onCreateWindow(
                                        view: WebView,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: android.os.Message
                                    ): Boolean {
                                        val popupWebView = WebView(view.context)
                                        popupWebView.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                popupView: WebView,
                                                request: android.webkit.WebResourceRequest
                                            ): Boolean {
                                                view.loadUrl(request.url.toString())
                                                return true
                                            }
                                        }
                                        val transport = resultMsg.obj as WebView.WebViewTransport
                                        transport.webView = popupWebView
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }
                                // TikTok/X/Threads detect the default WebView UA as an
                                // "in-app browser" and respond by disabling scrolling,
                                // gating features, or nagging to open their own app;
                                // Instagram/TikTok's login also often refuses to render
                                // at all in mobile-phone-web mode. applyDisplayMode() picks
                                // the phone- or tablet-Chrome UA per the toggle above.
                                applyDisplayMode(tabletMode)
                                loadUrl(site.homeUrl)
                                webViewRef = this
                            }
                        },
                        update = { webView ->
                            webViewRef = webView
                            if (webView.applyDisplayMode(tabletMode)) {
                                webView.reload()
                            }
                        }
                    )

                    val onProfilePage = remember(state.currentUrl, site) { isProfileUrl(site, state.currentUrl) }

                    if (onProfilePage) {
                        // Two bounded options — the single newest post, or a hand-pick
                        // from just the newest few — never a "get everything" button.
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            ExtendedFloatingActionButton(
                                onClick = { viewModel.requestRecentPosts(context) },
                                icon = {
                                    if (state.isProbing) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Filled.List, contentDescription = null)
                                    }
                                },
                                text = { Text("直近${MediaProbe.RECENT_POSTS_LIMIT}件から選ぶ") }
                            )
                            ExtendedFloatingActionButton(
                                onClick = { viewModel.requestDownloadLatestPost(context) },
                                icon = {
                                    if (state.isProbing) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Filled.GetApp, contentDescription = null)
                                    }
                                },
                                text = { Text("最新の投稿を保存") }
                            )
                        }
                    } else {
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.requestDownload(context) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            icon = {
                                if (state.isProbing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.GetApp, contentDescription = null)
                                }
                            },
                            text = { Text("この投稿を保存") }
                        )
                    }
                }

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

        if (state.recentPosts != null) {
            // Same picker UI, just fed the account's newest few posts instead
            // of one post's media items — still always a fixed, short list.
            MediaPickerDialog(
                title = "保存する投稿を選択",
                entries = state.recentPosts.orEmpty().map { MediaEntry(index = it.index, title = it.title) },
                selected = state.recentPostsSelected,
                onToggle = viewModel::toggleRecentPostSelected,
                onSelectAll = viewModel::selectAllRecentPosts,
                onClearAll = viewModel::clearRecentPostsSelection,
                onConfirm = { viewModel.confirmRecentPostsDownload(context) },
                onDismiss = viewModel::dismissRecentPosts
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
                            SnsGlyph(site, size = 36.dp)
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
            val isActive = site == current
            IconButton(onClick = { onSelect(site) }) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .then(
                            if (isActive) {
                                Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    SnsGlyph(site, size = 22.dp)
                }
            }
        }
    }
}

@Composable
private fun MediaPickerDialog(
    title: String = "ダウンロードするメディアを選択",
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
        title = { Text("$title（${selected.size} / ${entries.size} 件）") },
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

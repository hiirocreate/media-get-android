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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * the device name matches the actual phone this app runs on (OUKITEL C50)
 * instead of a made-up Samsung tablet model. A UA claiming a device that
 * doesn't match the real hardware is itself one more signal a site's
 * fraud/device-recognition system can use to flag "unrecognized device" —
 * this narrows that gap while keeping the non-"Mobile" trick that gets the
 * login screen to render at all.
 */
private const val TABLET_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; OUKITEL C50) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/**
 * A plain desktop-Chrome-on-Windows UA, offered as a manual option for sites
 * the user wants to browse in full PC layout. Unlike the earlier desktop-UA
 * attempt, switching to this never injects any reflow/caret-fix JavaScript —
 * that JS (not the UA itself) is what caused the scroll/white-screen bugs —
 * so this is just the UA string plus the same wide-viewport flags used for
 * tablet mode.
 */
private const val DESKTOP_CHROME_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/** Applies the chosen UA/viewport presentation to this WebView. Returns true if anything changed. */
private fun WebView.applyDisplayMode(mode: DisplayMode): Boolean {
    val desiredUa = when (mode) {
        DisplayMode.MOBILE -> MOBILE_CHROME_USER_AGENT
        DisplayMode.TABLET -> TABLET_CHROME_USER_AGENT
        DisplayMode.DESKTOP -> DESKTOP_CHROME_USER_AGENT
    }
    val changed = settings.userAgentString != desiredUa
    settings.userAgentString = desiredUa
    // Lets WebView honor whatever <meta name="viewport"> each site ships on
    // its own tablet/desktop layout — we no longer inject or override one
    // ourselves.
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    return changed
}

/**
 * Best-effort hiding of TikTok's "open in app" nag banner — pure CSS, no
 * behavior changes to the page (unlike the earlier scroll/caret JS patches
 * that caused more problems than they solved), so the worst case here is
 * simply "doesn't hide it" rather than breaking something else. Scoped to
 * tiktok.com only. The selectors are a guess at commonly-used patterns for
 * this kind of banner and may need adjusting once we see it live.
 */
private const val TIKTOK_HIDE_APP_BANNER_JS = """
(function() {
  try {
    var style = document.getElementById('mediaget-tiktok-banner-fix');
    if (!style) {
      style = document.createElement('style');
      style.id = 'mediaget-tiktok-banner-fix';
      document.head.appendChild(style);
    }
    style.textContent =
      '[data-e2e*="open-app"], [data-e2e*="download-guide"], [data-e2e*="feed-guide"], ' +
      '[data-e2e*="top-guide"], [class*="DivGuideContainer"], [class*="DivBanner"], ' +
      '[class*="download-btn"], [class*="app-banner"] { display: none !important; }';
  } catch (e) {}
})();
"""

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
    val displayMode = state.displayMode

    BackHandler(enabled = state.currentSite != null) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else viewModel.goHome()
    }

    // BrowserScreen (and the native WebView inside it) is fully torn down
    // and rebuilt every time the user leaves and returns to the "ブラウザ"
    // タブ — this snapshots the WebView's navigation/scroll state right
    // before that teardown so it can be restored instead of bouncing back
    // to the SNS's home page every time.
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                val bundle = android.os.Bundle()
                wv.saveState(bundle)
                viewModel.savedWebViewState = bundle
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val site = state.currentSite
        if (site == null) {
            BrowserHome(onSelect = { viewModel.openSite(it) })
        } else {
            // The browser itself now lives in a dedicated, empty middle region:
            // MediaGet's own chrome (top bar, save button(s)) reserves its own
            // space above/below instead of floating on top of the page, so
            // nothing the app draws can ever sit on top of the SNS's own
            // buttons/inputs and block them.
            Column(modifier = Modifier.fillMaxSize()) {
                val onProfilePage = remember(state.currentUrl, site) { isProfileUrl(site, state.currentUrl) }
                var menuExpanded by remember { mutableStateOf(false) }

                // Compact top bar: navigation + a single hamburger menu that
                // holds both "switch SNS" and "display mode" — replaces the
                // old always-visible row of 5 SNS icons and the separate
                // tablet-mode chip, so this row stays a single thin strip.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 2.dp)
                ) {
                    IconButton(onClick = { viewModel.goHome() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Home, contentDescription = "ホーム", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る", modifier = Modifier.size(20.dp))
                    }
                    // Some flows (e.g. Instagram's "承認してください" other-device
                    // approval) update on the server side but don't push that
                    // change back into an already-open page — reloading is the
                    // manual way to pick up the new state without navigating away.
                    IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "更新", modifier = Modifier.size(20.dp))
                    }
                    SnsGlyph(site, size = 18.dp, modifier = Modifier.padding(start = 4.dp))
                    Text(
                        text = site.displayName,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Menu, contentDescription = "メニュー", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            Text(
                                "SNSを切り替え",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            SnsSite.values().forEach { candidate ->
                                DropdownMenuItem(
                                    leadingIcon = { SnsGlyph(candidate, size = 18.dp) },
                                    trailingIcon = {
                                        if (candidate == site) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    text = { Text(candidate.displayName) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.openSite(candidate)
                                        webViewRef?.loadUrl(candidate.homeUrl)
                                    }
                                )
                            }
                            Divider()
                            Text(
                                "表示モード",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            DisplayMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    trailingIcon = {
                                        if (mode == displayMode) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    text = { Text(mode.label) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.setDisplayMode(mode)
                                    }
                                )
                            }
                        }
                    }
                }

                // The WebView fills every pixel left over between the top and
                // bottom bars — a real blank region with nothing drawn over it,
                // so sites that size themselves off the viewport (Instagram
                // Stories, for example) get accurate math for the space they
                // actually have.
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                        if (url != null && url.contains("tiktok.com")) {
                                            view?.evaluateJavascript(TIKTOK_HIDE_APP_BANNER_JS, null)
                                        }
                                    }

                                    // Instagram/X/TikTok are single-page apps: after the very
                                    // first full load, tapping into a profile or a post updates
                                    // the address bar via history.pushState() instead of doing a
                                    // real navigation — so onPageFinished() above never fires
                                    // again, and the app kept thinking the current page was
                                    // whatever URL first loaded (e.g. still the home feed). That
                                    // stale URL is exactly why a profile page sometimes showed
                                    // the single-post "保存" button instead of the profile
                                    // buttons, and why probing it failed with
                                    // "内容を確認できませんでした". doUpdateVisitedHistory() is
                                    // WebView's hook for these history-only URL changes, so this
                                    // keeps the current-page URL accurate for SPA navigation too.
                                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        viewModel.onPageUrlChanged(url)
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
                                // the phone/tablet/desktop UA per the menu selection above.
                                applyDisplayMode(displayMode)
                                val saved = viewModel.savedWebViewState
                                if (saved != null) {
                                    restoreState(saved)
                                    viewModel.savedWebViewState = null
                                } else {
                                    loadUrl(site.homeUrl)
                                }
                                webViewRef = this
                            }
                        },
                        update = { webView ->
                            webViewRef = webView
                            if (webView.applyDisplayMode(displayMode)) {
                                webView.reload()
                            }
                        }
                    )
                }

                // Compact bottom action row — fixed, reserved space below the
                // WebView (never floating on top of it), so it can never end up
                // sitting over the SNS's own on-page buttons either.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (onProfilePage) {
                        // Two bounded options — the single newest post, or a hand-pick
                        // from just the newest few — never a "get everything" button.
                        Button(
                            onClick = { viewModel.requestRecentPosts(context) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            colors = ButtonDefaults.buttonColors()
                        ) {
                            if (state.isProbing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                "直近${MediaProbe.RECENT_POSTS_LIMIT}件",
                                modifier = Modifier.padding(start = 4.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = { viewModel.requestDownloadLatestPost(context) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            if (state.isProbing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.GetApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                "最新の投稿",
                                modifier = Modifier.padding(start = 4.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Button(
                            onClick = { viewModel.requestDownload(context) },
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            if (state.isProbing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.GetApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text("この投稿を保存", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
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

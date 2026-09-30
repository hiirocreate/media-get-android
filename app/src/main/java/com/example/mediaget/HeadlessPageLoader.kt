package com.example.mediaget

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A link that arrives via Android's share sheet or the "リンクで取得" paste box
 * never gets the one thing that makes a private-account post or an
 * age/region-gated page work when the user opens the exact same link by hand
 * in the "ブラウザ" タブ: an actual page visit. CookieExporter already hands
 * yt-dlp whatever cookies already exist in WebView's shared, on-disk cookie
 * store (the same store the visible browser tab uses — logging in there
 * covers every entry point, not just that tab), but some gates are satisfied
 * only by that page's own JavaScript actually running once: a "sensitive
 * content" click-through, an age check, a private-but-approved-follower
 * check. Those can set a fresh cookie — or sometimes only local/session
 * storage — that a static, previously-saved cookie file doesn't carry if the
 * WebView has never actually visited this particular URL before.
 *
 * This loads a URL once, in a real but invisible WebView, using that exact
 * same shared login session, and waits for it to finish (or a timeout)
 * before returning — so by the time CookieExporter reads cookies for the
 * real yt-dlp request, whatever that visit settled is already in place. It
 * downloads nothing itself, renders nothing on screen, and never touches
 * anything beyond the one URL it's given — no following links, no scrolling,
 * no loading anything the page doesn't load on its own.
 */
object HeadlessPageLoader {

    private const val TIMEOUT_MS = 12_000L

    /**
     * Best-effort only: a failed, blocked, or timed-out load just means the
     * subsequent yt-dlp request proceeds with whatever cookies already
     * existed beforehand — exactly today's behavior — so this can never turn
     * a download that used to work into a new failure.
     */
    suspend fun warmUp(context: Context, url: String) {
        runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    val done = CompletableDeferred<Unit>()
                    val webView = WebView(context.applicationContext)
                    try {
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.userAgentString = UserAgents.MOBILE
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        }
                        webView.loadUrl(url)
                        done.await()
                        // Gives any last cookie-setting JS from onPageFinished's
                        // own microtask queue a brief moment to actually run
                        // before the WebView is torn down.
                        kotlinx.coroutines.delay(500)
                    } finally {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }
    }
}

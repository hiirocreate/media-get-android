package com.example.mediaget

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/**
 * yt-dlp runs as its own separate process with its own network stack — it
 * never automatically sees the in-app browser's login session, even though
 * the WebView (CookieManager) is logged in. This was the real reason
 * "Instagramにログインできているのにダウンロードだけ失敗する" — every probe
 * and download was going out as a logged-out request no matter what the
 * browser showed.
 *
 * This exports the WebView's current cookies for a URL's domain into a
 * Netscape-format cookie file, which yt-dlp accepts directly via --cookies,
 * so probing/downloading uses the exact same session the user just logged
 * into in the browser tab.
 */
object CookieExporter {

    /** Returns a Netscape cookie file for [url]'s domain, or null if there's nothing to export. */
    fun exportForUrl(context: Context, url: String): File? {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (cookieHeader.isNullOrBlank()) return null

        // A leading dot makes the cookie apply to the whole domain (and its
        // subdomains) rather than just the exact host WebView reported it for.
        val domain = host.removePrefix("www.")
        // WebView's CookieManager doesn't expose each cookie's real expiry —
        // pinning a year out just keeps yt-dlp from treating them as already
        // expired; it doesn't change how long they're actually valid for.
        val expiry = System.currentTimeMillis() / 1000 + 60L * 60 * 24 * 365

        return runCatching {
            val file = File(context.cacheDir, "mediaget_cookies_${domain.replace(".", "_")}.txt")
            file.bufferedWriter().use { writer ->
                writer.write("# Netscape HTTP Cookie File\n")
                cookieHeader.split(";").forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) return@forEach
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isBlank()) return@forEach
                    writer.write(".$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
                }
            }
            file
        }.getOrNull()
    }
}

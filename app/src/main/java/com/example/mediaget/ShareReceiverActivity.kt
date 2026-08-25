package com.example.mediaget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * The share-sheet target for "パターン2": picking MediaGet from another app's
 * share menu (Instagram/TikTok/YouTube/X/Threads → 共有 → MediaGet) never
 * opens any screen. It just pulls the URL out of the share payload, queues the
 * whole post/story for download in the background, and closes itself — all
 * progress after that shows up as a notification, same as every other
 * download in this app.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = extractUrl(intent)
        if (url != null) {
            DownloadActions.submit(applicationContext, url)
            Toast.makeText(this, "ダウンロードを開始しました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "リンクを認識できませんでした", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        val match = URL_REGEX.find(sharedText) ?: return null
        return match.value
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}

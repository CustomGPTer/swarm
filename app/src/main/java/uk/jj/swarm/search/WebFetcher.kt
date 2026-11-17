package uk.jj.swarm.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Loads a page in an off-screen browser view and returns its HTML. Used only when a site refuses a plain
 * request (anti-bot checks). Must be called from a background thread.
 */
object WebFetcher {
    @SuppressLint("SetJavaScriptEnabled")
    fun html(ctx: Context, url: String, timeoutSec: Long = 25, ready: ((String) -> Boolean)? = null): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var result: String? = null
        var web: WebView? = null
        var tries = 0
        main.post {
            try {
                val w = WebView(ctx.applicationContext)
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.blockNetworkImage = true
                lateinit var grab: Runnable
                grab = Runnable {
                    try {
                        w.evaluateJavascript("document.documentElement.outerHTML") { v ->
                            val html = try { JSONArray("[$v]").getString(0) } catch (e: Exception) { null }
                            if (html != null && !SiteReader.looksBlocked(200, html) && (ready == null || tries >= 4 || try { ready(html) } catch (e: Exception) { true })) { result = html; latch.countDown() }
                            else if (++tries < 8) main.postDelayed(grab, 2500)
                            else { result = html; latch.countDown() }
                        }
                    } catch (e: Exception) { latch.countDown() }
                }
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) { main.removeCallbacks(grab); main.postDelayed(grab, 1500) }
                }
                w.loadUrl(url)
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        main.post { try { web?.stopLoading(); web?.destroy() } catch (e: Exception) { } }
        return result
    }
}

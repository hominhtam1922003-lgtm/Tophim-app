package com.example.tophimapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

/**
 * Man hinh xac minh Cloudflare THU CONG 1 lan:
 * web nguon dung o tick "Xac minh ban la con nguoi" ma WebView tai ngam
 * khong tu qua duoc. O day nguoi dung tick truc tiep tren trang that,
 * cookie cf_clearance se duoc luu vao CookieManager dung chung cua app
 * -> moi lan tai sau (Jsoup gan cookie + WebFetcher) deu qua duoc ngay.
 * Khi phat hien trang phim that da hien, tu dong dong man hinh nay.
 */
class VerifyActivity : AppCompatActivity() {

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val CHALLENGE_MARKERS = listOf(
            "just a moment", "xác minh bạn là con người", "cf-chl",
            "attention required", "thực hiện xác minh bảo mật"
        )
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            checkCleared()
            handler.postDelayed(this, 3000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify)
        title = "Xác minh với trang web"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        webView = findViewById(R.id.wvVerify)
        cm.setAcceptThirdPartyCookies(webView, true)
        with(webView.settings) {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = DESKTOP_UA
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                return if (AdBlock.isAd(request?.url?.toString())) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            // Chi cho o lai tren trang nguon / Cloudflare, chan click-hijack
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host?.lowercase() ?: return false
                val allowed = host.endsWith("tophim.pro") ||
                    host.endsWith("cloudflare.com") ||
                    host == (try {
                        java.net.URI(TophimSource.BASE).host ?: ""
                    } catch (e: Exception) {
                        ""
                    })
                return !allowed
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                checkCleared()
            }
        }

        findViewById<Button>(R.id.btnVerifyDone).setOnClickListener { done() }
        webView.loadUrl(TophimSource.BASE + "/")
        handler.postDelayed(poll, 3000)
    }

    /** Trang phim that da hien (co link /phim/ va khong con dau hieu chan)? */
    private fun checkCleared() {
        if (finished) return
        webView.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML;})()"
        ) { result ->
            if (finished) return@evaluateJavascript
            val html = try {
                org.json.JSONTokener(result ?: "null").nextValue()?.toString() ?: ""
            } catch (e: Exception) {
                ""
            }
            val lower = html.lowercase()
            val isChallenge = CHALLENGE_MARKERS.any { lower.contains(it) }
            if (html.contains("/phim/") && !isChallenge) {
                done()
            }
        }
    }

    private fun done() {
        if (finished) return
        finished = true
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // bo qua
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try {
            webView.destroy()
        } catch (e: Exception) {
            // bo qua
        }
        super.onDestroy()
    }
}

package com.example.tophimapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Tai trang bang WebView that (du phong khi Jsoup bi Cloudflare chan).
 *
 * tophim.pro bat kiem tra Cloudflare o MOI lan tai trang. Trong WebView:
 * - Bat cookie (ke ca cookie ben thu ba) de luu "ve thong hanh" cf_clearance,
 *   cac lan tai sau trong app se qua nhanh hon.
 * - Khong bo cuoc sau vai giay: cu 2.5 giay lai doc thu HTML; neu van la
 *   trang kiem tra ("Just a moment"...) thi cho tiep, toi da ~50 giay.
 *   Khi thay noi dung that (co link /phim/) thi bam "Xem them" + cuon trang
 *   roi moi lay HTML cuoi.
 * Luon chay tren main thread.
 */
object WebFetcher {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val EXPAND_JS = """
        (function(){
          var texts = ['xem thêm','xem tất cả','xem toàn bộ','xem hết'];
          var els = document.querySelectorAll('a,button,div,span');
          for (var i=0;i<els.length;i++){
            var t = (els[i].textContent||'').trim().toLowerCase();
            if (texts.indexOf(t) >= 0){ try{ els[i].click(); }catch(e){} }
          }
          window.scrollTo(0, document.body.scrollHeight);
          return 'done';
        })()
    """

    private val CHALLENGE_MARKERS = listOf(
        "just a moment", "xác minh bạn là con người", "cf-chl", "attention required",
        "thực hiện xác minh bảo mật", "checking your browser"
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun fetchHtml(context: Context, url: String, callback: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            // Cookie la dieu kien de Cloudflare cho qua o cac lan tai sau
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)

            val webView = WebView(context.applicationContext)
            cm.setAcceptThirdPartyCookies(webView, true)
            webView.settings.javaScriptEnabled = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = false
            webView.settings.setSupportMultipleWindows(false)
            webView.settings.domStorageEnabled = true
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.userAgentString = DESKTOP_UA

            var done = false
            var expanded = false
            val startAt = System.currentTimeMillis()

            fun finish(html: String?) {
                if (done) return
                done = true
                try {
                    CookieManager.getInstance().flush()
                } catch (e: Exception) {
                    // bo qua
                }
                try {
                    webView.destroy()
                } catch (e: Exception) {
                    // bo qua
                }
                callback(html)
            }

            fun poll() {
                if (done) return
                val elapsed = System.currentTimeMillis() - startAt
                webView.evaluateJavascript(
                    "(function(){return document.documentElement.outerHTML;})()"
                ) { result ->
                    if (done) return@evaluateJavascript
                    val html = decode(result)
                    val lower = html?.lowercase() ?: ""
                    val isChallenge = CHALLENGE_MARKERS.any { lower.contains(it) }
                    val hasContent = html != null && html.contains("/phim/")
                    when {
                        // Da qua kiem tra va co noi dung phim
                        hasContent && !isChallenge -> {
                            if (!expanded) {
                                expanded = true
                                webView.evaluateJavascript(EXPAND_JS) {
                                    main.postDelayed({ poll() }, 1800)
                                }
                            } else {
                                finish(html)
                            }
                        }
                        // Van o trang kiem tra: cho tiep (toi da 50 giay)
                        elapsed < 50000 -> main.postDelayed({ poll() }, 2500)
                        // Het gio: tra HTML hien tai (co the van la trang chan)
                        else -> finish(html)
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    return if (AdBlock.isAd(request?.url?.toString())) {
                        WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    } else {
                        null
                    }
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    main.postDelayed({ poll() }, 1500)
                }
            }
            // Phong thu: timeout tong 60 giay
            main.postDelayed({ finish(null) }, 60000)
            webView.loadUrl(url)
        }
    }

    private fun decode(jsonString: String?): String? {
        if (jsonString == null || jsonString == "null") return null
        return try {
            org.json.JSONTokener(jsonString).nextValue()?.toString()
        } catch (e: Exception) {
            jsonString.trim('"')
        }
    }
}

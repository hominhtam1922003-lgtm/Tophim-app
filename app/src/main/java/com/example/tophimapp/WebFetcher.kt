package com.example.tophimapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Phuong an du phong khi Jsoup bi Cloudflare chan:
 * tai trang bang WebView that, gia lap TRINH DUYET DESKTOP (ban desktop cua
 * web chua san toan bo danh sach chuong trong HTML), tu bam cac nut
 * "Xem them" va cuon het cac hop cuon truoc khi lay HTML da render.
 * Luon chay tren main thread.
 */
object WebFetcher {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // JS: bam moi phan tu "Xem them / Xem tat ca" + cuon xuong cuoi trang va
    // cuon het cac hop co thanh cuon rieng (noi chua danh sach chuong)
    private const val EXPAND_JS = """
        (function(){
          var texts = ['xem thêm','xem tất cả','xem toàn bộ','xem hết'];
          var els = document.querySelectorAll('a,button,div,span');
          for (var i=0;i<els.length;i++){
            var t = (els[i].textContent||'').trim().toLowerCase();
            if (texts.indexOf(t) >= 0){ try{ els[i].click(); }catch(e){} }
          }
          window.scrollTo(0, document.body.scrollHeight);
          var all = document.querySelectorAll('*');
          for (var j=0;j<all.length;j++){
            var el = all[j];
            if (el.scrollHeight > el.clientHeight + 50 && el.clientHeight > 80){
              el.scrollTop = el.scrollHeight;
            }
          }
          return 'done';
        })()
    """

    @SuppressLint("SetJavaScriptEnabled")
    fun fetchHtml(context: Context, url: String, callback: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = false
            webView.settings.setSupportMultipleWindows(false)
            webView.settings.domStorageEnabled = true
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.userAgentString = DESKTOP_UA
            var done = false

            fun finish(html: String?) {
                if (done) return
                done = true
                try { webView.destroy() } catch (e: Exception) { /* bo qua */ }
                callback(html)
            }

            webView.webViewClient = object : WebViewClient() {

                // CHAN QUANG CAO: moi request toi cac mang quang cao/tracking
                // deu bi cat ngay trong WebView nay - quang cao cua web
                // khong bao gio cham duoc vao app
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val host = request?.url?.host?.lowercase() ?: return null
                    val blocked = AdBlock.isAd(request?.url?.toString())
                    return if (blocked) {
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    } else {
                        null
                    }
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    // Cho Cloudflare / JS render xong
                    main.postDelayed({
                        if (done) return@postDelayed
                        view?.evaluateJavascript(EXPAND_JS) {
                            // Cho noi dung mo rong tai xong roi moi lay HTML
                            main.postDelayed({
                                if (!done) {
                                    view?.evaluateJavascript(
                                        "(function(){return document.documentElement.outerHTML;})()"
                                    ) { result -> finish(decode(result)) }
                                }
                            }, 2500)
                        }
                    }, 3000)
                }
            }
            // Timeout tong 35 giay
            main.postDelayed({ finish(null) }, 35000)
            webView.loadUrl(url)
        }
    }

    /** Dau hieu nhan dien ten mien quang cao / tracking pho bien. */
    private val AD_HOST_MARKERS = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "adservice",
        "ads.", ".ads", "ad-", "-ad.", "advertising", "adnxs", "adskeeper",
        "popads", "popcash", "propellerads", "exoclick", "adsterra", "hilltopads",
        "mgid", "outbrain", "taboola", "criteo", "pubmatic", "rubiconproject",
        "smartadserver", "amazon-adsystem", "adform", "openx", "revcontent",
        "ziddu", "bidvertiser", "trafficjunky", "juicyads", "clickadu", "adcash"
    )

    private fun decode(jsonString: String?): String? {
        if (jsonString == null || jsonString == "null") return null
        return try {
            org.json.JSONTokener(jsonString).nextValue()?.toString()
        } catch (e: Exception) {
            jsonString.trim('"')
        }
    }
}

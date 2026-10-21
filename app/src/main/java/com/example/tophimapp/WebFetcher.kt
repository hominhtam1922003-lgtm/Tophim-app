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
 *   trang kiem tra ("Just a moment"...) thi cho tiep, toi da ~30 giay.
 *   Khi thay noi dung that (co link /phim/) thi bam "Xem them" + cuon trang
 *   roi moi lay HTML cuoi.
 * Luon chay tren main thread.
 */
object WebFetcher {


    private const val EXPAND_JS = """
        (function(){
          var texts = ['xem thêm','xem tất cả','xem toàn bộ','xem hết'];
          var els = document.querySelectorAll('a,button,div,span');
          for (var i=0;i<els.length;i++){
            var t = (els[i].textContent||'').trim().toLowerCase();
            if (texts.indexOf(t) >= 0){ try{ els[i].click(); }catch(e){} }
          }
          // Cuon tung doan xuong day trang de cac hang phim tai cham
          // (lazy render khi thay trong tam nhin) kip hien ra het
          var y = 0;
          var iv = setInterval(function(){
            y += 900;
            window.scrollTo(0, y);
            if (y >= document.body.scrollHeight){ clearInterval(iv); window.scrollTo(0, 0); }
          }, 130);
          return 'done';
        })()
    """

    // Trang /phim-18 co modal xac nhan tuoi phu len luoi phim: phai bam
    // nut "Toi da tren 18 tuoi" (web con co chen quang cao popunder vao
    // lan bam dau - da bi chan popup nen thuong phai bam 2 lan)
    private const val AGE_GATE_JS = """
        (function(){
          var els = document.querySelectorAll('a,button,div,span');
          for (var i=0;i<els.length;i++){
            var t = (els[i].textContent||'').trim().toLowerCase();
            if (t.indexOf('trên 18 tuổi') >= 0 && t.length < 60){
              try{ els[i].click(); return 'clicked'; }catch(e){}
            }
          }
          return 'none';
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
            webView.settings.userAgentString = TophimSource.UA

            var done = false
            var expanded = false
            var ageClicks = 0
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
                    val ageGated = lower.contains("giới hạn độ tuổi") ||
                        lower.contains("tôi đã trên 18")
                    when {
                        // Trang bi cong tuoi (18+): bam nut xac nhan truoc
                        hasContent && !isChallenge && ageGated && ageClicks < 3 -> {
                            ageClicks++
                            webView.evaluateJavascript(AGE_GATE_JS) {
                                main.postDelayed({ poll() }, 2000)
                            }
                        }
                        // Da qua kiem tra va co noi dung phim
                        hasContent && !isChallenge -> {
                            if (!expanded) {
                                expanded = true
                                webView.evaluateJavascript(EXPAND_JS) {
                                    main.postDelayed({ poll() }, 2600)
                                }
                            } else {
                                finish(html)
                            }
                        }
                        // Van o trang kiem tra: cho tiep (toi da 50 giay)
                        elapsed < 30000 -> main.postDelayed({ poll() }, 2500)
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
            main.postDelayed({ finish(null) }, 40000)
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

package com.example.tophimapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

/**
 * Trinh phat video nhieu server, thu tu uu tien (theo khao sat web that):
 * 1) HLS thang: thuoc tinh data-embedbackup tren trang xem la file .m3u8
 *    cua may chu du phong (play.vinahost.id) -> phat bang trang HLS noi bo
 *    (hls.js), sach quang cao vi chi tai dung luong video.
 * 2) Khung nhung playembed.vip (data-embed) kem Referer cua trang xem,
 *    chan request quang cao (AdBlock), cam popup, khoa khung chinh,
 *    don overlay quang cao bang JS chay lien tuc.
 * 3) Trang xem goc tren web nguon (van ap AdBlock) - du phong cuoi.
 * Loi o server nao thi tu dong chuyen server tiep; nguoi dung cung bam
 * duoc nut "Doi server" o goc phai.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WATCH_URL = "watch_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_EPISODE = "episode_name"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Xoa lien ket target=_blank (nhe - dung cho trang xem goc)
        private const val CLEAN_LINKS_JS = """
            (function(){
              document.querySelectorAll('a[target="_blank"]').forEach(function(a){ a.remove(); });
              return 'ok';
            })()
        """

        // Don manh cho trang nhung playembed: xoa link _blank + an overlay
        // quang cao phu rong man hinh (ke ca phan tu xuat hien sau)
        private const val CLEAN_EMBED_JS = """
            (function(){
              if (window.__museClean) return 'already';
              window.__museClean = true;
              function clean(){
                document.querySelectorAll('a[target="_blank"]').forEach(function(a){ a.remove(); });
                var W = window.innerWidth, H = window.innerHeight;
                document.querySelectorAll('div,ins,section,aside').forEach(function(el){
                  try{
                    var s = getComputedStyle(el);
                    if ((s.position === 'fixed' || s.position === 'absolute') &&
                        !el.querySelector('video') &&
                        el.offsetWidth > W * 0.6 && el.offsetHeight > H * 0.35 &&
                        (el.querySelector('a') || el.querySelector('img'))){
                      el.style.display = 'none';
                    }
                  }catch(e){}
                });
              }
              clean();
              new MutationObserver(clean).observe(document.documentElement, {childList:true, subtree:true});
              return 'ok';
            })()
        """
    }

    private enum class Kind { HLS, EMBED, WATCH }
    private data class Candidate(val kind: Kind, val url: String)

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var btnServer: Button
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var targetHost: String = ""
    private var watchUrl: String = ""
    private var candidates: List<Candidate> = emptyList()
    private var candidateIndex = 0
    private var errorToastShown = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        watchUrl = TophimSource.rebase(
            intent.getStringExtra(EXTRA_WATCH_URL) ?: run { finish(); return }
        )
        val movieTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val epName = intent.getStringExtra(EXTRA_EPISODE) ?: ""
        title = if (epName.isNotEmpty()) "$movieTitle - $epName" else movieTitle

        progress = findViewById(R.id.playerProgress)
        btnServer = findViewById(R.id.btnServer)
        btnServer.setOnClickListener { nextCandidate(auto = false) }
        webView = findViewById(R.id.wvPlayer)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = DESKTOP_UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // Trang HLS noi bo bao loi phat ve day de tu doi server
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onError() {
                runOnUiThread { nextCandidate(auto = true) }
            }
        }, "PlayerBridge")

        webView.webChromeClient = object : WebChromeClient() {
            // Cam popup: quang cao khong mo duoc tab/trang moi
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customCallback = callback
                if (view != null) {
                    findViewById<FrameLayout>(R.id.playerRoot).addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                webView.visibility = View.GONE
                btnServer.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let {
                    findViewById<FrameLayout>(R.id.playerRoot).removeView(it)
                }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                webView.visibility = View.VISIBLE
                if (candidates.size > 1) btnServer.visibility = View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                return if (AdBlock.isAd(url)) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            // Khoa khung chinh: khong cho dieu huong sang host la (trang QC)
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (request.isForMainFrame) {
                    val host = url.host ?: ""
                    if (targetHost.isNotEmpty() && host != targetHost) return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                val kind = candidates.getOrNull(candidateIndex)?.kind
                when (kind) {
                    Kind.EMBED -> {
                        view?.evaluateJavascript(CLEAN_EMBED_JS, null)
                        checkEmbedBlocked(view)
                    }
                    Kind.WATCH -> view?.evaluateJavascript(CLEAN_LINKS_JS, null)
                    else -> Unit
                }
            }
        }

        resolveAndPlay()
    }

    /** playembed tra trang chan Cloudflare -> tu chuyen server tiep theo. */
    private fun checkEmbedBlocked(view: WebView?) {
        view?.evaluateJavascript(
            "(function(){return (document.body?document.body.innerText:'').slice(0,300);})()"
        ) { text ->
            val t = text?.lowercase() ?: ""
            if (t.contains("you have been blocked") ||
                t.contains("unable to access") ||
                t.contains("attention required")
            ) {
                nextCandidate(auto = true)
            }
        }
    }

    /** Lay cac nguon phat tu trang xem: Jsoup truoc, WebView du phong. */
    private fun resolveAndPlay() {
        progress.visibility = View.VISIBLE
        thread {
            var links = PlayerLinks()
            try {
                links = TophimSource.extractPlayers(TophimSource.fetchDoc(watchUrl))
            } catch (e: Exception) {
                // Du phong WebView
            }
            runOnUiThread {
                if (links.hls.isNotEmpty() || links.embed.isNotEmpty()) {
                    startWith(links)
                } else {
                    WebFetcher.fetchHtml(this, watchUrl) { html ->
                        var l2 = PlayerLinks()
                        if (html != null) {
                            try {
                                l2 = TophimSource.extractPlayers(
                                    TophimSource.parseHtml(html, watchUrl)
                                )
                            } catch (e: Exception) {
                                // bo qua
                            }
                        }
                        startWith(l2)
                    }
                }
            }
        }
    }

    private fun startWith(links: PlayerLinks) {
        val list = ArrayList<Candidate>()
        if (links.hls.isNotEmpty()) list.add(Candidate(Kind.HLS, links.hls))
        if (links.embed.isNotEmpty()) list.add(Candidate(Kind.EMBED, links.embed))
        list.add(Candidate(Kind.WATCH, watchUrl))
        candidates = list
        playCandidate(0)
    }

    private fun playCandidate(index: Int) {
        if (index !in candidates.indices) return
        candidateIndex = index
        val c = candidates[index]
        btnServer.text = "Server ${index + 1}/${candidates.size} ⟳"
        btnServer.visibility = if (candidates.size > 1) View.VISIBLE else View.GONE
        progress.visibility = View.VISIBLE
        when (c.kind) {
            Kind.HLS -> {
                targetHost = ""
                webView.loadDataWithBaseURL(
                    TophimSource.BASE + "/", hlsHtml(c.url), "text/html", "utf-8", null
                )
            }
            Kind.EMBED -> {
                targetHost = hostOf(c.url)
                // Referer la trang xem: may chu video chi cho phat khi duoc
                // "gioi thieu" tu trang phim, mo truc tiep de bi chan
                webView.loadUrl(c.url, mapOf("Referer" to watchUrl))
            }
            Kind.WATCH -> {
                targetHost = hostOf(c.url)
                webView.loadUrl(c.url)
            }
        }
    }

    private fun nextCandidate(auto: Boolean) {
        if (candidates.size <= 1) {
            if (auto && !errorToastShown) {
                errorToastShown = true
                Toast.makeText(
                    this,
                    "Không phát được video. Thử lại sau hoặc đổi tập khác.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val next = (candidateIndex + 1) % candidates.size
        if (auto) {
            Toast.makeText(this, "Server lỗi, đang đổi server khác...", Toast.LENGTH_SHORT).show()
        }
        playCandidate(next)
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Trang HTML noi bo phat HLS bang hls.js; loi thi bao ve PlayerBridge. */
    private fun hlsHtml(m3u8: String): String {
        val src = m3u8.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;background:#000;height:100%}video{width:100%;height:100%}</style>
            <script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js"></script>
            </head><body>
            <video id="v" controls autoplay playsinline></video>
            <script>
              var v = document.getElementById('v');
              var src = "$src";
              function fail(){ if (window.PlayerBridge) { PlayerBridge.onError(); } }
              if (window.Hls && Hls.isSupported()) {
                var h = new Hls();
                h.loadSource(src);
                h.attachMedia(v);
                h.on(Hls.Events.ERROR, function(e, d){ if (d && d.fatal) { fail(); } });
              } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
                v.src = src;
                v.addEventListener('error', fail);
              } else {
                fail();
              }
            </script>
            </body></html>
        """.trimIndent()
    }

    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            webView.onPause()
        } catch (e: Exception) {
            // bo qua
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
        } catch (e: Exception) {
            // bo qua
        }
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
            // bo qua
        }
        super.onDestroy()
    }
}

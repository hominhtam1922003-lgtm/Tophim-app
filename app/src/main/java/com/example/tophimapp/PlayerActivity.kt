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


        // Xoa lien ket target=_blank (nhe - dung cho trang xem goc)
        private const val CLEAN_LINKS_JS = """
            (function(){
              document.querySelectorAll('a[target="_blank"]').forEach(function(a){ a.remove(); });
              return 'ok';
            })()
        """

        // Do tim luong phat tren trang xem dang chay that: dia chi + THOI
        // LUONG cua the <video> + moi tai nguyen .m3u8/.mp4 trang da tai
        // + iframe (neu co). Thoi luong la chìa khoa phan biet phim that
        // (hang gio) voi quang cao mo dau (chi ~50 giay) - ban 1.4 bat
        // nham quang cao co bac Hi88 phat cho nguoi dung vi thieu dau nay.
        private const val PROBE_JS = """
            (function(){
              var out = {src:'', dur:0, res:[], ifr:''};
              try {
                var v = document.querySelector('video');
                if (v) {
                  out.src = v.currentSrc || v.src || '';
                  out.dur = (v.duration && isFinite(v.duration)) ? v.duration : 0;
                }
                var es = performance.getEntriesByType('resource');
                for (var i=0;i<es.length;i++){
                  var n = es[i].name || '';
                  if (n.indexOf('.m3u8')>=0 || n.indexOf('.mp4')>=0) out.res.push(n);
                }
                var f = document.querySelector('iframe');
                if (f) out.ifr = f.src || '';
              } catch(e){}
              return JSON.stringify(out);
            })()
        """

        // Bam nut "Bo qua" quang cao trong player cua web (neu co)
        private const val SKIP_AD_JS = """
            (function(){
              var els = document.querySelectorAll('a,button,div,span');
              for (var i=0;i<els.length;i++){
                var t = (els[i].textContent||'').trim().toLowerCase();
                if (t.length < 40 && (t.indexOf('bỏ qua')>=0 || t.indexOf('skip')>=0)){
                  try{ els[i].click(); return 'clicked'; }catch(e){}
                }
              }
              return 'none';
            })()
        """

        // Tat tieng VINH VIEN moi the video/audio trong WebView do nguon
        // (ke ca the moi tao sau nay): ban 1.4.1/1.4.2 de WebView ngam tu
        // phat ca TIENG cua quang cao co bac trong luc do - nguoi dung
        // thay man hinh den ma nghe tieng quang cao rat phan cam
        private const val MUTE_JS = """
            (function(){
              function muteAll(){
                var ms = document.querySelectorAll('video,audio');
                for (var i=0;i<ms.length;i++){
                  try{ ms[i].muted = true; ms[i].volume = 0; }catch(e){}
                }
              }
              muteAll();
              if (!window.__museMute){
                window.__museMute = true;
                setInterval(muteAll, 300);
              }
              return 'ok';
            })()
        """

        // Tua thang doan quang cao mo dau ve gan cuoi de player cua web
        // chuyen sang phim that ngay, thay vi phai cho phat het 50 giay
        private const val FASTFORWARD_AD_JS = """
            (function(){
              var v = document.querySelector('video');
              if (v && v.duration && isFinite(v.duration) &&
                  v.duration > 0 && v.duration < 180 &&
                  v.currentTime < v.duration - 2){
                try{ v.currentTime = Math.max(0, v.duration - 0.5); return 'ff'; }catch(e){}
              }
              return 'no';
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

    private enum class Kind { HLS, DIRECT, EMBED }
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
        // Full man hinh that: tat thanh tieu de cua app + an thanh trang
        // thai/dieu huong cua he thong (vuot canh de hien lai tam thoi).
        // Ban <=1.3 de sot action bar o man nay nen video bi mat khong gian.
        supportActionBar?.hide()
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller =
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            // May cu khong ho tro: bo qua
        }

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
            userAgentString = TophimSource.UA
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
                // EMBED gio nam trong iframe khac goc (wrapper) nen khong
                // cham duoc DOM ben trong nua; chan quang cao o tang mang
                // (AdBlock) van ap dung cho moi request cua player.
                Unit
            }
        }

        resolveAndPlay()
    }

    /** Trang noi bo phat file video truc tiep (.mp4) bang the video chuan. */
    private fun directHtml(src0: String): String {
        val src = src0.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;background:#000;height:100%}video{width:100%;height:100%}</style>
            </head><body>
            <video id="v" controls autoplay playsinline src="$src"></video>
            <script>
              var v = document.getElementById('v');
              v.addEventListener('error', function(){
                if (window.PlayerBridge) { PlayerBridge.onError(); }
              });
            </script>
            </body></html>
        """.trimIndent()
    }

    /** Trang boc noi bo cho khung nhung: iframe chiem toan man hinh. */
    private fun embedHtml(embedUrl: String): String {
        val src = embedUrl.replace("&", "&amp;").replace("\"", "&quot;")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
              iframe{position:fixed;inset:0;width:100%;height:100%;border:0}
            </style>
            </head><body>
            <iframe src="$src" allowfullscreen
              allow="autoplay; fullscreen; encrypted-media; picture-in-picture"></iframe>
            </body></html>
        """.trimIndent()
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
                        if (l2.hls.isNotEmpty() || l2.embed.isNotEmpty()) {
                            startWith(l2)
                        } else {
                            // Boc tinh khong thay gi: phim dung trinh phat
                            // rieng cua web -> do luong truc tiep
                            probeRuntime()
                        }
                    }
                }
            }
        }
    }

    private fun startWith(links: PlayerLinks) {
        val list = ArrayList<Candidate>()
        if (links.hls.isNotEmpty()) list.add(Candidate(Kind.HLS, links.hls))
        if (links.embed.isNotEmpty()) list.add(Candidate(Kind.EMBED, links.embed))
        // KHONG con phuong an "mo nguyen trang xem cua web" nhu truoc:
        // trang do day quang cao co bac, nguoi dung phan anh rat gat.
        candidates = list
        if (list.isEmpty()) {
            probeRuntime()
        } else {
            playCandidate(0)
        }
    }

    /**
     * Do luong phat TRUC TIEP: chay trang xem trong WebView ngam, player
     * rieng cua web se tu nap video (ke ca quang cao mo dau cua no bi
     * AdBlock chan); minh bat dia chi luong that qua the <video> va danh
     * sach tai nguyen trang da tai, roi phat bang player sach cua app.
     * Ap dung cho nhom phim nhu "Ca Voi Nuot Chung" - khong iframe,
     * khong data-embed, nguon chi lo ra luc trang dang chay.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun probeRuntime() {
        progress.visibility = View.VISIBLE
        // Dong chu trang thai giua man hinh: giai doan do nguon co the
        // keo dai vai giay, de man hinh den thui + vong xoay khong thi
        // nguoi dung tuong app treo (kem tieng quang cao ro ri cua ban cu)
        val statusTv = android.widget.TextView(this)
        statusTv.text =
            "Đang tìm nguồn phát sạch…\n" +
                "Đang tự bỏ qua quảng cáo mở đầu của web, chờ chút nhé"
        statusTv.setTextColor(android.graphics.Color.WHITE)
        statusTv.textSize = 15f
        statusTv.gravity = android.view.Gravity.CENTER
        statusTv.setPadding(56, 56, 56, 56)
        try {
            findViewById<FrameLayout>(R.id.playerRoot).addView(
                statusTv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        } catch (e: Exception) {
            // bo qua
        }
        val probe = WebView(applicationContext)
        try {
            android.webkit.CookieManager.getInstance()
                .setAcceptThirdPartyCookies(probe, true)
        } catch (e: Exception) {
            // bo qua
        }
        with(probe.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = TophimSource.UA
        }
        probe.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                return if (AdBlock.isAd(request?.url?.toString())) {
                    WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                } else {
                    null
                }
            }

            // Trang vua tai xong la tat tieng ngay (va lap lai lien tuc)
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(MUTE_JS, null)
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var probeDone = false
        val probeStart = System.currentTimeMillis()
        // Trang thai do duoc sau moi vong:
        // - adUrls: cac luong da xac nhan la QUANG CAO (video dang phat
        //   chung co thoi luong ngan < 180 giay) -> khong bao gio phat
        // - movieUrl: luong cua the video co thoi luong >= 180 giay
        // - resHls/resMp4: luong thay trong danh sach tai nguyen
        val adUrls = HashSet<String>()
        var movieUrl = ""
        var resHls = ""
        var resMp4 = ""
        var embedUrl = ""
        var lastSrc = ""

        fun finishProbe() {
            if (probeDone) return
            probeDone = true
            try {
                (statusTv.parent as? ViewGroup)?.removeView(statusTv)
            } catch (e: Exception) {
                // bo qua
            }
            try {
                probe.destroy()
            } catch (e: Exception) {
                // bo qua
            }
            val list = ArrayList<Candidate>()
            when {
                movieUrl.isNotEmpty() -> {
                    if (movieUrl.contains(".m3u8")) {
                        list.add(Candidate(Kind.HLS, movieUrl))
                    } else {
                        list.add(Candidate(Kind.DIRECT, movieUrl))
                    }
                }
                resHls.isNotEmpty() && !adUrls.contains(resHls) ->
                    list.add(Candidate(Kind.HLS, resHls))
                lastSrc.isNotEmpty() && !adUrls.contains(lastSrc) -> {
                    if (lastSrc.contains(".m3u8")) {
                        list.add(Candidate(Kind.HLS, lastSrc))
                    } else {
                        list.add(Candidate(Kind.DIRECT, lastSrc))
                    }
                }
                resMp4.isNotEmpty() && !adUrls.contains(resMp4) ->
                    list.add(Candidate(Kind.DIRECT, resMp4))
                embedUrl.isNotEmpty() ->
                    list.add(Candidate(Kind.EMBED, embedUrl))
            }
            candidates = list
            if (list.isEmpty()) {
                showNoSource()
            } else {
                playCandidate(0)
            }
        }

        fun pollProbe() {
            if (probeDone) return
            // Moi vong deu tat tieng lai cho chac (the video moi tao)
            probe.evaluateJavascript(MUTE_JS, null)
            probe.evaluateJavascript(PROBE_JS) { result ->
                if (probeDone) return@evaluateJavascript
                try {
                    val raw = org.json.JSONTokener(result ?: "null")
                        .nextValue()?.toString() ?: ""
                    val o = org.json.JSONObject(raw)
                    val src = o.optString("src")
                    val dur = o.optDouble("dur", 0.0)
                    if (src.startsWith("http") &&
                        (src.contains(".m3u8") || src.contains(".mp4"))
                    ) {
                        lastSrc = src
                        if (dur >= 180) {
                            movieUrl = src
                        } else if (dur > 0) {
                            // Luong ngan dang phat = quang cao mo dau:
                            // ghi nho de tranh, bam nut bo qua QC va TUA
                            // THANG ve cuoi doan quang cao de player
                            // chuyen sang phim that nhanh hon nhieu
                            adUrls.add(src)
                            probe.evaluateJavascript(SKIP_AD_JS, null)
                            probe.evaluateJavascript(FASTFORWARD_AD_JS, null)
                        }
                    }
                    val res = o.optJSONArray("res")
                    if (res != null) {
                        for (i in 0 until res.length()) {
                            val u = res.optString(i)
                            if (AdBlock.isAd(u) || adUrls.contains(u)) continue
                            if (u.contains(".m3u8") && resHls.isEmpty()) resHls = u
                            else if (u.contains(".mp4") && resMp4.isEmpty()) resMp4 = u
                        }
                    }
                    val ifr = o.optString("ifr")
                    if (ifr.startsWith("http") &&
                        (ifr.contains("player") || ifr.contains("embed"))
                    ) {
                        embedUrl = ifr
                    }
                } catch (e: Exception) {
                    // Chua doc duoc: thu lai sau
                }
                val elapsed = System.currentTimeMillis() - probeStart
                when {
                    // Da thay phim that (dai >= 180 giay): phat ngay
                    movieUrl.isNotEmpty() -> finishProbe()
                    // Player da tai san playlist phim (khac luong quang
                    // cao dang phat) va da quan sat du lau: dung luon
                    resHls.isNotEmpty() && !adUrls.contains(resHls) &&
                        adUrls.isNotEmpty() && elapsed > 15000 -> finishProbe()
                    embedUrl.isNotEmpty() && elapsed > 20000 -> finishProbe()
                    elapsed > 45000 -> finishProbe()
                    else -> handler.postDelayed({ pollProbe() }, 2000)
                }
            }
        }

        probe.loadUrl(watchUrl)
        handler.postDelayed({ pollProbe() }, 2500)
        handler.postDelayed({ finishProbe() }, 50000)
    }

    /** Khong boc duoc nguon sach o moi duong -> bao ro, khong mo trang web. */
    private fun showNoSource() {
        progress.visibility = View.GONE
        Toast.makeText(
            this,
            "Chưa lấy được nguồn phát sạch cho tập này. Thử bản chiếu hoặc tập khác nhé.",
            Toast.LENGTH_LONG
        ).show()
        finish()
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
            Kind.DIRECT -> {
                targetHost = ""
                webView.loadDataWithBaseURL(
                    TophimSource.BASE + "/", directHtml(c.url), "text/html", "utf-8", null
                )
            }
            Kind.EMBED -> {
                targetHost = ""
                // Boc khung nhung trong 1 trang noi bo co iframe chiem
                // 100% man hinh: mo thang URL playembed nhu truoc lam
                // trang cua no tu co khung hinh vao giua, thua vien den
                // va thanh tien do bi day khoi tam nhin. Base URL dat la
                // trang xem de iframe ke thua Referer cua trang phim
                // (may chu video chi cho phat khi duoc "gioi thieu").
                webView.loadDataWithBaseURL(
                    watchUrl, embedHtml(c.url), "text/html", "utf-8", null
                )
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

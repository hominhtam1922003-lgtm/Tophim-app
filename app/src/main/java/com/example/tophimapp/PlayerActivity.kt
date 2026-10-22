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
 * Trinh phat video — ban 2.0 (nguon cobephim qua API).
 * Dau vao la trang phat CHINH THUC cua web: {host API}/embed/{tap}.
 * Thu tu uu tien:
 * 1) Bo do chay trang embed trong WebView ngam va BAT MOI REQUEST
 *    .m3u8/.mp4 o tang mang; luong bat duoc phai qua kiem tra thoi
 *    luong (chong quang cao mo dau) roi phat bang player sach noi bo
 *    cua app (HLS con qua PlaylistCleaner loc quang cao ghep).
 * 2) Khong bat duoc luong thang: phat bang chinh trang embed chinh
 *    thuc do trong khung da khoa (cam popup, khoa dieu huong sang
 *    host la, AdBlock o tang mang). Day la player cua chinh web nguon
 *    (khong phai iframe quang cao cua ben thu ba nhu playembed xua).
 * Nut "Doi ban" chuyen giua cac luong bat duoc va cac BAN CHIEU khac
 * (Phu de / Thuyet Minh / Long tieng) cua cung 1 tap.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WATCH_URL = "watch_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_EPISODE = "episode_name"
        // Cac ban chieu khac cua cung 1 tap (nhan + trang phat embed),
        // DetailActivity gui kem de doi ban ngay trong player
        const val EXTRA_SERVER_NAMES = "server_names"
        const val EXTRA_SERVER_URLS = "server_urls"

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

        // Bam nut hien player cua trang xem (mot so trang chi tao
        // iframe/player sau khi nguoi dung bam nut nay) de player ben
        // trong chiu tai luong video that -> bo do bat duoc o tang mang
        private const val PLAY_TRIGGER_JS = """
            (function(){
              var b = document.querySelector(
                '#btnShowPlayer,[id*=showPlayer],[id*=show-player],' +
                '[class*=showPlayer],[class*=show-player]');
              if (b){ try{ b.click(); return 'clicked'; }catch(e){} }
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
    }

    private enum class Kind { HLS, DIRECT, EMBEDPAGE }
    private data class Candidate(val kind: Kind, val url: String, val label: String = "")

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var btnServer: Button
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var targetHost: String = ""
    private var watchUrl: String = ""
    // Cac ban chieu khac cua cung tap (trang embed + nhan ban chieu)
    private var versionUrls: List<String> = emptyList()
    private var versionNames: List<String> = emptyList()
    private var candidates: List<Candidate> = emptyList()
    private var candidateIndex = 0
    private var errorToastShown = false
    // Danh dau moi lan doi ung vien: ket qua loc playlist (chay nen)
    // chi duoc ap dung neu chua doi sang ung vien khac
    private var playToken = 0

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
        versionUrls = intent.getStringArrayListExtra(EXTRA_SERVER_URLS) ?: emptyList()
        versionNames = intent.getStringArrayListExtra(EXTRA_SERVER_NAMES) ?: emptyList()
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

    /**
     * Diem vao phat (ban 2.0): watchUrl la trang phat embed chinh thuc
     * cua web ({host API}/embed/{id tap}) — chay bo do tren chinh trang
     * do de bat luong thang. Neu dau vao la file video thang thi phat
     * ngay; URL la (lich su cu cua nguon xua) thi bao ro, khong do nua.
     */
    private fun resolveAndPlay() {
        progress.visibility = View.VISIBLE
        val path = watchUrl.substringBefore("?").lowercase()
        when {
            path.endsWith(".m3u8") -> {
                candidates = listOf(Candidate(Kind.HLS, watchUrl, "Luồng trực tiếp"))
                playCandidate(0)
            }
            path.endsWith(".mp4") -> {
                candidates = listOf(Candidate(Kind.DIRECT, watchUrl, "Luồng trực tiếp"))
                playCandidate(0)
            }
            watchUrl.contains("/embed/") -> probeRuntime()
            else -> showNoSource()
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
        // BAT LUONG O TANG MANG (moi o ban 1.5): moi request tai file
        // video (.m3u8/.mp4) tu BAT KY frame nao trong trang - ke ca
        // tu ben trong iframe playembed - deu bi ghi lai o day.
        // shouldInterceptRequest chay tren luong nen nen dung tap hop
        // dong bo; chi QUAN SAT roi cho request di tiep, khong chan.
        val netHls = java.util.Collections.synchronizedSet(
            java.util.LinkedHashSet<String>()
        )
        val netMp4 = java.util.Collections.synchronizedSet(
            java.util.LinkedHashSet<String>()
        )
        val lastNetAt = java.util.concurrent.atomic.AtomicLong(0)
        val watchHost = hostOf(watchUrl)
        probe.webChromeClient = object : WebChromeClient() {
            // Cam popup trong WebView do (QC khong mo trang moi)
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }
        probe.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (AdBlock.isAd(u)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                val path = u.substringBefore("?").lowercase()
                if (path.endsWith(".m3u8")) {
                    netHls.add(u)
                    lastNetAt.set(System.currentTimeMillis())
                } else if (path.endsWith(".mp4")) {
                    netMp4.add(u)
                    lastNetAt.set(System.currentTimeMillis())
                }
                return null
            }

            // Khoa khung chinh cua WebView do: chi o lai host trang
            // phat + cac host luong/phat cua web, khong bi popunder
            // cuop trang sang trang quang cao
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (request.isForMainFrame) {
                    val h = url.host ?: ""
                    val ok = h == watchHost || h.endsWith("darkbytes.xyz") ||
                        h.endsWith("streamvsmov.com") ||
                        h.endsWith("streamuuzy.com") ||
                        h.endsWith("cloudflare.com") || h.isEmpty()
                    if (!ok) return true
                }
                return false
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
        var lastSrc = ""
        var triggerTries = 0

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
            fun addStream(u: String) {
                if (u.isEmpty() || adUrls.contains(u)) return
                if (list.any { it.url == u }) return
                if (u.contains(".m3u8")) {
                    list.add(Candidate(Kind.HLS, u))
                } else if (u.contains(".mp4")) {
                    list.add(Candidate(Kind.DIRECT, u))
                }
            }
            if (movieUrl.isNotEmpty()) {
                // Da xac nhan bang thoi luong >= 180 giay: phat dung no
                addStream(movieUrl)
            } else {
                // Uu tien: luong bat o tang mang MUON nhat (player
                // thuong tai quang cao truoc roi moi chot luong phim),
                // luong the video gan nhat, playlist trang da tai san,
                // luong mang som nhat lam du phong doi server, MP4 cuoi
                val nets = synchronized(netHls) { ArrayList(netHls) }
                    .filter { !adUrls.contains(it) }
                if (nets.isNotEmpty()) addStream(nets.last())
                addStream(lastSrc)
                addStream(resHls)
                if (nets.size > 1) addStream(nets.first())
                addStream(resMp4)
                val mp4First = synchronized(netMp4) { netMp4.firstOrNull() } ?: ""
                addStream(mp4First)
            }
            // Ban 2.0: co luong thang thi phat luong (sach nhat); kem
            // theo trang embed cua ban dang xem + cac ban chieu khac
            // lam ung vien doi ban. Khong bat duoc luong nao -> phat
            // bang chinh trang embed chinh thuc (da khoa popup/dieu
            // huong/chan quang cao o tang mang) chu khong con bao
            // "khong co nguon" nhu thoi nguon cu.
            val out = ArrayList<Candidate>()
            for (c in list) out.add(Candidate(c.kind, c.url, "Luồng trực tiếp"))
            out.add(
                Candidate(
                    Kind.EMBEDPAGE, watchUrl,
                    versionNameOf(watchUrl) ?: "Bản đang xem"
                )
            )
            for (u in versionUrls) {
                if (u == watchUrl) continue
                if (out.any { it.url == u }) continue
                out.add(Candidate(Kind.EMBEDPAGE, u, versionNameOf(u) ?: "Bản khác"))
            }
            candidates = out
            playCandidate(0)
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
                            // ghi nho de tranh, thu bam nut bo qua QC.
                            // (Ban 1.4.3 co tua nhanh QC - da BO o 1.4.4
                            // vi mot so player phat hien tua se phat lai
                            // QC tu dau, do do cang lau; tra ve dung
                            // hanh vi ban 1.4.1 xem muot.)
                            adUrls.add(src)
                            probe.evaluateJavascript(SKIP_AD_JS, null)
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
                } catch (e: Exception) {
                    // Chua doc duoc: thu lai sau
                }
                val elapsed = System.currentTimeMillis() - probeStart
                // Trang chua tu tao player thi bam nut hien player cua
                // no (toi da 2 lan) de luong that lo ra o tang mang
                if (triggerTries < 2 &&
                    ((elapsed > 4000 && triggerTries == 0) ||
                        (elapsed > 16000 && triggerTries == 1))
                ) {
                    triggerTries++
                    probe.evaluateJavascript(PLAY_TRIGGER_JS, null)
                }
                val netCount = synchronized(netHls) { netHls.size }
                val netQuiet = lastNetAt.get() > 0 &&
                    System.currentTimeMillis() - lastNetAt.get() > 8000
                when {
                    // Da thay phim that (dai >= 180 giay): phat ngay
                    movieUrl.isNotEmpty() -> finishProbe()
                    // Player da tai san playlist phim (khac luong quang
                    // cao dang phat) va da quan sat du lau: dung luon
                    resHls.isNotEmpty() && !adUrls.contains(resHls) &&
                        adUrls.isNotEmpty() && elapsed > 15000 -> finishProbe()
                    // Luong o tang mang da on dinh (8 giay khong co
                    // luong moi): player da chot nguon -> phat
                    netCount > 0 && netQuiet && elapsed > 12000 -> finishProbe()
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

    /** Nhan ban chieu cua 1 trang phat (neu DetailActivity co gui kem). */
    private fun versionNameOf(url: String): String? {
        val idx = versionUrls.indexOf(url)
        return if (idx >= 0) versionNames.getOrNull(idx) else null
    }

    private fun playCandidate(index: Int) {
        if (index !in candidates.indices) return
        candidateIndex = index
        val c = candidates[index]
        btnServer.text = if (c.label.isNotEmpty()) {
            "${c.label} • ${index + 1}/${candidates.size} ⟳"
        } else {
            "Server ${index + 1}/${candidates.size} ⟳"
        }
        btnServer.visibility = if (candidates.size > 1) View.VISIBLE else View.GONE
        progress.visibility = View.VISIBLE
        when (c.kind) {
            Kind.EMBEDPAGE -> {
                // Phat bang trang embed chinh thuc cua web trong khung
                // da khoa: cam popup (onCreateWindow), khoa khung chinh
                // theo host trang phat (shouldOverrideUrlLoading dung
                // targetHost), AdBlock o tang mang
                targetHost = hostOf(c.url)
                webView.loadUrl(
                    c.url,
                    mapOf(
                        "User-Agent" to TophimSource.UA,
                        "Referer" to TophimSource.BASE + "/"
                    )
                )
            }
            Kind.HLS -> {
                targetHost = ""
                // Loc quang cao ghep trong playlist truoc khi phat
                // (chay nen; playlist sach/khong loc duoc thi phat
                // thang nhu cu - xem PlaylistCleaner)
                val token = ++playToken
                thread {
                    val res = PlaylistCleaner.clean(c.url, watchUrl)
                    runOnUiThread {
                        if (token == playToken && !isFinishing) {
                            webView.loadDataWithBaseURL(
                                TophimSource.BASE + "/",
                                hlsHtml(res.playUrl, res.overrideText),
                                "text/html", "utf-8", null
                            )
                        }
                    }
                }
            }
            Kind.DIRECT -> {
                targetHost = ""
                webView.loadDataWithBaseURL(
                    TophimSource.BASE + "/", directHtml(c.url), "text/html", "utf-8", null
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

    /**
     * Trang HTML noi bo phat HLS bang hls.js; loi thi bao ve
     * PlayerBridge. Neu overrideText co gia tri (playlist da duoc
     * PlaylistCleaner cat quang cao ghep), nap playlist tu blob noi
     * dung do thay vi tu URL goc.
     */
    private fun hlsHtml(m3u8: String, overrideText: String?): String {
        val src = m3u8.replace("\\", "\\\\").replace("\"", "\\\"")
        val overrideJs = if (overrideText != null) {
            "var pl = " + org.json.JSONObject.quote(overrideText) + ";" +
                "src = URL.createObjectURL(" +
                "new Blob([pl], {type:'application/vnd.apple.mpegurl'}));"
        } else {
            ""
        }
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
              $overrideJs
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

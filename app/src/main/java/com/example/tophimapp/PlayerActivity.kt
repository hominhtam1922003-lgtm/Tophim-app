package com.example.tophimapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

/**
 * Trinh phat video:
 * - Trich link nhung (playembed.vip/player/...) tu trang xem cua web nguon,
 *   chi tai DUNG khung phat do -> khong keo theo banner/quang cao cua trang.
 * - WebView phat bi khoa cung: chan moi request toi ten mien quang cao
 *   (AdBlock), cam cua so popup, cam dieu huong khung chinh sang trang la.
 * - Neu khong trich duoc link nhung thi tai thang trang xem (van bi chan QC).
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WATCH_URL = "watch_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_EPISODE = "episode_name"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Xoa cac lien ket target=_blank (lop phu quang cao bat len video,
        // bam lan dau thuong bi mo trang ca cuoc)
        private const val CLEAN_JS = """
            (function(){
              document.querySelectorAll('a[target="_blank"]').forEach(function(a){ a.remove(); });
              return 'ok';
            })()
        """
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var targetHost: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val watchUrl = TophimSource.rebase(
            intent.getStringExtra(EXTRA_WATCH_URL) ?: run { finish(); return }
        )
        val movieTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val epName = intent.getStringExtra(EXTRA_EPISODE) ?: ""
        title = if (epName.isNotEmpty()) "$movieTitle - $epName" else movieTitle

        progress = findViewById(R.id.playerProgress)
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
            }

            override fun onHideCustomView() {
                customView?.let {
                    findViewById<FrameLayout>(R.id.playerRoot).removeView(it)
                }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                webView.visibility = View.VISIBLE
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
                view?.evaluateJavascript(CLEAN_JS, null)
            }
        }

        resolveAndPlay(watchUrl)
    }

    /** Lay link nhung tu trang xem: Jsoup truoc, WebView du phong. */
    private fun resolveAndPlay(watchUrl: String) {
        progress.visibility = View.VISIBLE
        thread {
            var embed = ""
            try {
                embed = TophimSource.extractEmbed(TophimSource.fetchDoc(watchUrl))
            } catch (e: Exception) {
                // Du phong WebView
            }
            runOnUiThread {
                if (embed.isNotEmpty()) {
                    play(embed)
                } else {
                    WebFetcher.fetchHtml(this, watchUrl) { html ->
                        var e2 = ""
                        if (html != null) {
                            try {
                                e2 = TophimSource.extractEmbed(
                                    TophimSource.parseHtml(html, watchUrl)
                                )
                            } catch (e: Exception) {
                                // bo qua
                            }
                        }
                        play(e2.ifEmpty { watchUrl })
                    }
                }
            }
        }
    }

    private fun play(url: String) {
        targetHost = try {
            java.net.URI(url).host ?: ""
        } catch (e: Exception) {
            ""
        }
        webView.loadUrl(url)
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

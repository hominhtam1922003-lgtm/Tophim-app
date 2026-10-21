package com.example.tophimapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "movie_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_POSTER = "movie_poster"
    }

    private lateinit var db: DbRepo
    private var detail: MovieDetail? = null
    private lateinit var movieUrl: String
    private var currentServer = 0

    private lateinit var rvEpisodes: RecyclerView
    private lateinit var epAdapter: EpisodeAdapter

    inner class EpisodeAdapter(private var items: List<Episode>) :
        RecyclerView.Adapter<EpisodeAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = items[position]
            holder.tv.text = ep.name
            holder.itemView.setOnClickListener { playEpisode(ep) }
        }

        override fun getItemCount(): Int = items.size

        fun submit(list: List<Episode>) {
            items = list
            notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        db = DbRepo(this)

        movieUrl = TophimSource.rebase(intent.getStringExtra(EXTRA_URL) ?: run { finish(); return })
        val preTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val prePoster = intent.getStringExtra(EXTRA_POSTER) ?: ""

        title = preTitle
        findViewById<TextView>(R.id.tvDetailTitle).text = preTitle
        if (prePoster.isNotEmpty()) {
            ImageLoader.cover(findViewById(R.id.ivDetailCover), prePoster)
        }

        rvEpisodes = findViewById(R.id.rvChapters)
        rvEpisodes.layoutManager = GridLayoutManager(this, 4)
        epAdapter = EpisodeAdapter(emptyList())
        rvEpisodes.adapter = epAdapter

        updateFavoriteButton()
        findViewById<Button>(R.id.btnFavorite).setOnClickListener {
            val d = detail ?: return@setOnClickListener
            if (db.isFavorite(movieUrl)) {
                db.removeFavorite(movieUrl)
                Toast.makeText(this, "Đã bỏ yêu thích", Toast.LENGTH_SHORT).show()
            } else {
                db.addFavorite(d.movie, d.totalEpisodes)
                Toast.makeText(this, "Đã thêm vào yêu thích", Toast.LENGTH_SHORT).show()
            }
            updateFavoriteButton()
        }
        // Khoa nut Xem ngay cho den khi tai xong danh sach tap: ban cu
        // de bam duoc trong khi chua co tap nao -> player mo ra do
        // nguon tren trang khong phai trang xem, quay rat lau roi bao loi
        val btnWatch = findViewById<Button>(R.id.btnWatchFirst)
        btnWatch.isEnabled = false
        btnWatch.setOnClickListener {
            val first = detail?.servers?.firstOrNull()?.episodes?.firstOrNull()
            if (first != null) {
                playEpisode(first)
            } else {
                Toast.makeText(
                    this,
                    "Danh sách tập chưa tải xong, chờ chút rồi thử lại nhé",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        loadDetail()
    }

    private fun updateFavoriteButton() {
        findViewById<Button>(R.id.btnFavorite).text =
            if (db.isFavorite(movieUrl)) "★ Đã yêu thích" else "☆ Yêu thích"
    }

    private fun loadDetail() {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(3500)
            var d: MovieDetail? = null
            try {
                d = TophimSource.fetchDetail(movieUrl)
            } catch (e: Exception) {
                // Du phong WebView
            }
            runOnUiThread {
                if (d != null && d.servers.isNotEmpty()) {
                    showDetail(d)
                } else {
                    loadDetailViaWeb()
                }
            }
        }
    }

    private fun loadDetailViaWeb(attempt: Int = 1) {
        WebFetcher.fetchHtml(this, movieUrl) { html ->
            var d: MovieDetail? = null
            if (html != null) {
                try {
                    d = TophimSource.parseDetail(TophimSource.parseHtml(html, movieUrl), movieUrl)
                } catch (e: Exception) {
                    // bo qua
                }
            }
            runOnUiThread {
                if (d != null && d.servers.isNotEmpty()) {
                    showDetail(d)
                } else if (attempt < 2) {
                    // Thu lai 1 lan: lan tai dau co the vua bam qua cong
                    // tuoi/xac minh xong, anh chup trang chua kip la
                    // trang that (trang thai mo khoa da duoc luu ben
                    // nen lan 2 thuong vao thang)
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ loadDetailViaWeb(attempt + 1) }, 1500)
                } else {
                    findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Không tải được danh sách tập (web đang chặn). Thử lại sau ít phút.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showDetail(d: MovieDetail) {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
        findViewById<Button>(R.id.btnWatchFirst).isEnabled = true
        detail = d
        title = d.movie.title
        findViewById<TextView>(R.id.tvDetailTitle).text = d.movie.title
        if (d.movie.poster.isNotEmpty()) {
            ImageLoader.cover(findViewById(R.id.ivDetailCover), d.movie.poster)
        }
        findViewById<TextView>(R.id.tvDetailMeta).text = d.meta
        findViewById<TextView>(R.id.tvDetailDesc).text = d.description
        findViewById<TextView>(R.id.tvChapterCount).text =
            "Danh sách tập (${d.totalEpisodes} tập)"
        if (db.isFavorite(movieUrl)) {
            db.updateFavoriteEpisodeCount(movieUrl, d.totalEpisodes)
        }
        buildServerButtons(d)
        selectServer(0)
    }

    private fun buildServerButtons(d: MovieDetail) {
        if (d.servers.size <= 1) return
        findViewById<TextView>(R.id.tvServerLabel).visibility = View.VISIBLE
        findViewById<View>(R.id.svServers).visibility = View.VISIBLE
        val ll = findViewById<LinearLayout>(R.id.llServers)
        ll.removeAllViews()
        d.servers.forEachIndexed { idx, server ->
            val b = MaterialButton(this)
            b.text = server.name
            b.setOnClickListener { selectServer(idx) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            b.layoutParams = lp
            ll.addView(b)
        }
    }

    private fun selectServer(idx: Int) {
        val d = detail ?: return
        if (idx !in d.servers.indices) return
        currentServer = idx
        epAdapter.submit(d.servers[idx].episodes)
    }

    private fun playEpisode(ep: Episode) {
        val d = detail ?: return
        db.addHistory(d.movie, ep.name, TophimSource.rebase(ep.url))
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_WATCH_URL, TophimSource.rebase(ep.url))
        i.putExtra(PlayerActivity.EXTRA_TITLE, d.movie.title)
        i.putExtra(PlayerActivity.EXTRA_EPISODE, ep.name)
        startActivity(i)
    }
}

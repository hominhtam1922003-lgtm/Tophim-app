package com.example.tophimapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/**
 * Tab duyet phim theo loai:
 * - SINGLE: phim le (/phim-le)
 * - SERIES: phim bo (/phim-bo)
 * Moi lan mo tab deu tai lai tu web nen phim moi ra la thay ngay.
 */
class BrowseFragment : Fragment() {

    companion object {
        const val KIND_SINGLE = "single"
        const val KIND_SERIES = "series"
        fun newInstance(kind: String): BrowseFragment {
            val f = BrowseFragment()
            f.arguments = Bundle().apply { putString("kind", kind) }
            return f
        }
    }

    private lateinit var adapter: MovieGridAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: android.widget.ProgressBar
    private var kind: String = KIND_SERIES
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        kind = arguments?.getString("kind") ?: KIND_SERIES
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        v.findViewById<View>(R.id.etSearch).visibility = View.GONE
        progress = v.findViewById(R.id.progress)
        swipe = v.findViewById(R.id.swipeRefresh)
        val rv = v.findViewById<RecyclerView>(R.id.rvComics)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = MovieGridAdapter(emptyList()) { openDetail(it) }
        rv.adapter = adapter
        v.findViewById<MaterialButton>(R.id.btnLoadMore).visibility = View.GONE
        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, TophimSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }

    private fun load() {
        if (loading) return
        loading = true
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(3500)
            var list: List<Movie> = emptyList()
            try {
                list = TophimSource.fetchCategory(kind)
            } catch (e: Exception) {
                // Du phong WebView o duoi
            }
            activity?.runOnUiThread {
                if (list.isNotEmpty()) {
                    adapter.submit(list)
                    loading = false
                    swipe.isRefreshing = false
                    progress.visibility = View.GONE
                } else {
                    loadViaWeb()
                }
            }
        }
    }

    private fun loadViaWeb() {
        val ctx = context ?: return
        // Duong dan ung vien dau tien cua tung loai (khop fetchCategory)
        val paths = if (kind == KIND_SERIES) {
            listOf("/phim-bo", "/danh-sach/phim-bo", "/the-loai/phim-bo")
        } else {
            listOf("/phim-le", "/danh-sach/phim-le", "/the-loai/phim-le")
        }
        fun tryPath(idx: Int) {
            if (idx >= paths.size) {
                activity?.runOnUiThread {
                    loading = false
                    swipe.isRefreshing = false
                    progress.visibility = View.GONE
                    Toast.makeText(ctx, "Không tải được danh sách, kéo xuống thử lại", Toast.LENGTH_LONG).show()
                }
                return
            }
            val url = TophimSource.BASE + paths[idx]
            WebFetcher.fetchHtml(ctx, url) { html ->
                var list: List<Movie> = emptyList()
                if (html != null) {
                    try {
                        list = TophimSource.parseList(TophimSource.parseHtml(html, url))
                    } catch (e: Exception) {
                        // Thu duong dan tiep theo
                    }
                }
                if (list.isNotEmpty()) {
                    activity?.runOnUiThread {
                        adapter.submit(list)
                        loading = false
                        swipe.isRefreshing = false
                        progress.visibility = View.GONE
                    }
                } else {
                    tryPath(idx + 1)
                }
            }
        }
        tryPath(0)
    }
}

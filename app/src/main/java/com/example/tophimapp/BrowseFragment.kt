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
 * Tab duyet phim theo muc (ban 2.0 - nguon API): moi muc la 1 truy van
 * /movies/search (phim le = type=movie, phim bo = type=series, chieu
 * rap = the loai "Chiếu rạp") kem phan trang theo meta.totalPages.
 * Che do generic (title + params) dung cho duyet theo the loai/quoc gia.
 */
class BrowseFragment : Fragment() {

    companion object {
        const val KIND_SINGLE = "single"
        const val KIND_SERIES = "series"
        const val KIND_CINEMA = "cinema"
        const val KIND_PARAMS = "params"

        fun newInstance(kind: String): BrowseFragment {
            val f = BrowseFragment()
            f.arguments = Bundle().apply { putString("kind", kind) }
            return f
        }

        fun newInstanceParams(
            title: String, genres: String = "", countries: String = "",
            type: String = ""
        ): BrowseFragment {
            val f = BrowseFragment()
            f.arguments = Bundle().apply {
                putString("kind", KIND_PARAMS)
                putString("title", title)
                putString("genres", genres)
                putString("countries", countries)
                putString("typeParam", type)
            }
            return f
        }

        // Ma the loai "Chieu rap": tra tu bang ma API theo slug, du
        // phong 25 (da xac thuc truc tiep 22/10/2026)
        @Volatile
        private var cinemaGenreId: String = "25"
        @Volatile
        private var cinemaResolved = false
    }

    private lateinit var adapter: MovieGridAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var btnLoadMore: MaterialButton
    private lateinit var tvError: android.widget.TextView

    private var kind: String = KIND_SERIES
    private var title: String = ""
    private var params: Map<String, String> = emptyMap()
    private val items = ArrayList<Movie>()
    private var loading = false
    private var loadingMore = false
    private var currentPage = 1
    private var totalPages = 1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        kind = arguments?.getString("kind") ?: KIND_SERIES
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        v.findViewById<View>(R.id.etSearch).visibility = View.GONE
        v.findViewById<View>(R.id.btnVerify).visibility = View.GONE
        val tvTitle = v.findViewById<android.widget.TextView>(R.id.tvBrowseTitle)
        title = when (kind) {
            KIND_SINGLE -> "Phim lẻ"
            KIND_SERIES -> "Phim bộ"
            KIND_CINEMA -> "Chiếu rạp"
            else -> arguments?.getString("title") ?: ""
        }
        if (title.isNotEmpty()) {
            tvTitle.text = title
            tvTitle.visibility = View.VISIBLE
        }
        progress = v.findViewById(R.id.progress)
        swipe = v.findViewById(R.id.swipeRefresh)
        val rv = v.findViewById<RecyclerView>(R.id.rvComics)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = MovieGridAdapter(emptyList()) { openDetail(it) }
        rv.adapter = adapter
        btnLoadMore = v.findViewById(R.id.btnLoadMore)
        btnLoadMore.text = "Tải thêm phim"
        btnLoadMore.visibility = View.GONE
        btnLoadMore.setOnClickListener { loadMore() }
        tvError = v.findViewById(R.id.tvLoadError)
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

    /** Bo tham so truy van cho muc nay (chieu rap can tra bang ma truoc). */
    private fun resolveParams(): Map<String, String> {
        return when (kind) {
            KIND_SINGLE -> mapOf("type" to "movie")
            KIND_SERIES -> mapOf("type" to "series")
            KIND_CINEMA -> {
                if (!cinemaResolved) {
                    try {
                        val cats = TophimSource.fetchCategories()
                        cats.firstOrNull { it.slug == "chieu-rap" }
                            ?.let { cinemaGenreId = it.id.toString() }
                    } catch (e: Exception) {
                        // Giu ma du phong 25
                    }
                    cinemaResolved = true
                }
                mapOf("genres" to cinemaGenreId)
            }
            else -> {
                val m = LinkedHashMap<String, String>()
                arguments?.getString("genres")?.takeIf { it.isNotBlank() }
                    ?.let { m["genres"] = it }
                arguments?.getString("countries")?.takeIf { it.isNotBlank() }
                    ?.let { m["countries"] = it }
                arguments?.getString("typeParam")?.takeIf { it.isNotBlank() }
                    ?.let { m["type"] = it }
                m
            }
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        if (::tvError.isInitialized) tvError.visibility = View.GONE
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(2500)
            params = resolveParams()
            var page: SearchPage? = null
            try {
                page = TophimSource.searchMovies(params, 1)
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            activity?.runOnUiThread {
                loading = false
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                if (page != null && page.movies.isNotEmpty()) {
                    items.clear()
                    items.addAll(page.movies)
                    currentPage = 1
                    totalPages = page.totalPages
                    adapter.submit(items.toList())
                    updateLoadMoreVisibility()
                } else if (items.isEmpty()) {
                    tvError.visibility = View.VISIBLE
                    tvError.text =
                        "Mục này chưa tải được. Kiểm tra kết nối mạng rồi vuốt xuống để thử lại nhé."
                    updateLoadMoreVisibility()
                }
            }
        }
    }

    private fun updateLoadMoreVisibility() {
        if (::btnLoadMore.isInitialized) {
            btnLoadMore.visibility =
                if (currentPage < totalPages && items.isNotEmpty()) View.VISIBLE
                else View.GONE
            btnLoadMore.isEnabled = !loadingMore
        }
    }

    private fun loadMore() {
        if (loading || loadingMore || currentPage >= totalPages) return
        loadingMore = true
        updateLoadMoreVisibility()
        val next = currentPage + 1
        thread {
            var page: SearchPage? = null
            try {
                page = TophimSource.searchMovies(params, next)
            } catch (e: Exception) {
                // Giu nguyen trang thai
            }
            activity?.runOnUiThread {
                loadingMore = false
                if (page != null && page.movies.isNotEmpty()) {
                    val known = items.map { it.url }.toHashSet()
                    val added = page.movies.filter { known.add(it.url) }
                    if (added.isNotEmpty()) {
                        items.addAll(added)
                        currentPage = next
                        totalPages = page.totalPages
                        adapter.submit(items.toList())
                    } else if (page.movies.size >= TophimSource.PAGE_SIZE) {
                        // Trang day nhung trung het (phim moi cap nhat
                        // day phim cu sang trang sau): van di tiep
                        currentPage = next
                        totalPages = page.totalPages
                    } else {
                        totalPages = currentPage
                    }
                } else {
                    totalPages = currentPage
                    Toast.makeText(
                        requireContext(), "Đã tải hết phim của mục này",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updateLoadMoreVisibility()
            }
        }
    }
}

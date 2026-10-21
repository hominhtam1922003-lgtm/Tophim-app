package com.example.tophimapp

import android.app.Activity
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
        const val KIND_ADULT = "adult"
        fun newInstance(kind: String): BrowseFragment {
            val f = BrowseFragment()
            f.arguments = Bundle().apply { putString("kind", kind) }
            return f
        }
    }

    private lateinit var adapter: MovieGridAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var btnVerify: com.google.android.material.button.MaterialButton
    private lateinit var btnLoadMore: MaterialButton
    private var kind: String = KIND_SERIES
    private var loading = false
    private var verifyAutoOpened = false
    // Phan trang ?page=N: duong dan nao tai duoc trang 1 thi dung tiep
    // cho cac trang sau; het phim (trang tra ve rong/trung) thi an nut
    private val items = ArrayList<Movie>()
    private var workingPath: String? = null
    private var currentPage = 1
    private var hasMore = false
    private var loadingMore = false

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
        btnLoadMore = v.findViewById(R.id.btnLoadMore)
        btnLoadMore.text = "Tải thêm phim"
        btnLoadMore.visibility = View.GONE
        btnLoadMore.setOnClickListener { loadMore() }
        btnVerify = v.findViewById(R.id.btnVerify)
        btnVerify.setOnClickListener { openVerify() }
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
        items.clear()
        workingPath = null
        currentPage = 1
        hasMore = false
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        // Tab 18+: Jsoup khong qua noi cong tuoi (can cookie xac nhan
        // tuoi) -> di duong WebView TRUOC; neu WebView cung that bai moi
        // thu Jsoup sau. Cac tab khac giu thu tu Jsoup -> WebView.
        if (kind == KIND_ADULT) {
            loadViaWeb(adultFallbackToJsoup = true)
            return
        }
        thread {
            RemoteConfig.awaitReady(3500)
            var path: String? = null
            var list: List<Movie> = emptyList()
            try {
                val r = TophimSource.fetchCategoryPage(kind, 1)
                path = r.first
                list = r.second
            } catch (e: Exception) {
                // Du phong WebView o duoi
            }
            activity?.runOnUiThread {
                if (list.isNotEmpty()) {
                    workingPath = path
                    showFirstPage(list)
                } else {
                    loadViaWeb()
                }
            }
        }
    }

    private fun showFirstPage(list: List<Movie>) {
        items.clear()
        appendUnique(list)
        adapter.submit(items.toList())
        hasMore = true
        updateLoadMoreVisibility()
        loading = false
        swipe.isRefreshing = false
        progress.visibility = View.GONE
    }

    /** Them phim moi (bo qua phim da co) - tra ve so phim them duoc. */
    private fun appendUnique(list: List<Movie>): Int {
        val known = items.map { it.url }.toHashSet()
        var added = 0
        for (m in list) {
            if (known.add(m.url)) {
                items.add(m)
                added++
            }
        }
        return added
    }

    private fun updateLoadMoreVisibility() {
        if (::btnLoadMore.isInitialized) {
            btnLoadMore.visibility =
                if (hasMore && items.isNotEmpty()) View.VISIBLE else View.GONE
            btnLoadMore.isEnabled = !loadingMore
        }
    }

    /** Nut "Tai them phim": lat sang trang ?page=N+1 cua danh muc. */
    private fun loadMore() {
        val path = workingPath ?: return
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        updateLoadMoreVisibility()
        val nextPage = currentPage + 1
        val url = "${TophimSource.BASE}$path?page=$nextPage"
        thread {
            var list: List<Movie> = emptyList()
            try {
                list = TophimSource.parseList(TophimSource.fetchDoc(url))
            } catch (e: Exception) {
                // Thu WebView ben duoi
            }
            activity?.runOnUiThread {
                if (list.isNotEmpty()) {
                    applyMorePage(list, nextPage)
                } else {
                    val ctx = context
                    if (ctx == null) {
                        loadingMore = false
                        updateLoadMoreVisibility()
                        return@runOnUiThread
                    }
                    WebFetcher.fetchHtml(ctx, url) { html ->
                        var l2: List<Movie> = emptyList()
                        if (html != null) {
                            try {
                                l2 = TophimSource.parseList(
                                    TophimSource.parseHtml(html, url)
                                )
                            } catch (e: Exception) {
                                // bo qua
                            }
                        }
                        applyMorePage(l2, nextPage)
                    }
                }
            }
        }
    }

    private fun applyMorePage(list: List<Movie>, page: Int) {
        val added = appendUnique(list)
        if (added > 0) {
            currentPage = page
            adapter.submit(items.toList())
        } else {
            hasMore = false
            Toast.makeText(
                requireContext(), "Đã tải hết phim của mục này", Toast.LENGTH_SHORT
            ).show()
        }
        loadingMore = false
        updateLoadMoreVisibility()
    }

    private fun loadViaWeb(adultFallbackToJsoup: Boolean = false) {
        val ctx = context ?: return
        // Duong dan ung vien dau tien cua tung loai (khop fetchCategory)
        val paths = TophimSource.categoryPaths(kind)
        fun tryPath(idx: Int) {
            if (idx >= paths.size) {
                if (adultFallbackToJsoup) {
                    // WebView that bai toan tap: thu not Jsoup (biet dau
                    // cookie xac nhan tuoi da co tu phien truoc)
                    thread {
                        var path: String? = null
                        var list: List<Movie> = emptyList()
                        try {
                            val r = TophimSource.fetchCategoryPage(kind, 1)
                            path = r.first
                            list = r.second
                        } catch (e: Exception) {
                            // bo qua
                        }
                        activity?.runOnUiThread {
                            if (list.isNotEmpty()) {
                                workingPath = path
                                showFirstPage(list)
                            } else {
                                loading = false
                                swipe.isRefreshing = false
                                progress.visibility = View.GONE
                                showVerifyOffer()
                            }
                        }
                    }
                    return
                }
                activity?.runOnUiThread {
                    loading = false
                    swipe.isRefreshing = false
                    progress.visibility = View.GONE
                    showVerifyOffer()
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
                        if (::btnVerify.isInitialized) btnVerify.visibility = View.GONE
                        workingPath = paths[idx]
                        showFirstPage(list)
                    }
                } else {
                    tryPath(idx + 1)
                }
            }
        }
        tryPath(0)
    }

    /** Tai that bai o moi duong -> kha nang cao la o tick xac minh Cloudflare. */
    private fun showVerifyOffer() {
        if (::btnVerify.isInitialized) btnVerify.visibility = View.VISIBLE
        if (!verifyAutoOpened) {
            verifyAutoOpened = true
            Toast.makeText(
                requireContext(),
                "Web yêu cầu tick xác minh 1 lần, mình mở sẵn trang xác minh cho bạn",
                Toast.LENGTH_LONG
            ).show()
            openVerify()
        }
    }

    private fun openVerify() {
        startActivityForResult(
            Intent(requireContext(), VerifyActivity::class.java), HomeFragment.REQ_VERIFY
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HomeFragment.REQ_VERIFY && resultCode == Activity.RESULT_OK) {
            if (::btnVerify.isInitialized) btnVerify.visibility = View.GONE
            load()
        }
    }
}

package com.example.tophimapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.concurrent.thread

class HomeFragment : Fragment() {

    private lateinit var adapter: MovieGridAdapter
    private val movies = ArrayList<Movie>()
    private val homeBackup = ArrayList<Movie>()
    private var loading = false

    // Tim kiem truc tiep khi go: dem thoi gian + so the he chong ket qua cu
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchGeneration = 0
    private var searching = false

    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var etSearch: EditText
    private lateinit var btnVerify: Button
    private lateinit var tvLoadError: android.widget.TextView
    private var verifyAutoOpened = false

    companion object {
        const val REQ_VERIFY = 77
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        progress = v.findViewById(R.id.progress)
        swipe = v.findViewById(R.id.swipeRefresh)
        etSearch = v.findViewById(R.id.etSearch)

        val rv = v.findViewById<RecyclerView>(R.id.rvComics)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = MovieGridAdapter(movies) { openDetail(it) }
        rv.adapter = adapter
        v.findViewById<Button>(R.id.btnLoadMore).visibility = View.GONE
        btnVerify = v.findViewById(R.id.btnVerify)
        btnVerify.setOnClickListener { openVerify() }
        tvLoadError = v.findViewById(R.id.tvLoadError)

        swipe.setOnRefreshListener {
            if (searching) runSearch(etSearch.text.toString().trim(), manual = false)
            else loadHome()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (q.isEmpty()) {
                    // Xoa o tim kiem -> quay lai danh sach trang chu
                    searchGeneration++
                    searching = false
                    movies.clear()
                    movies.addAll(homeBackup)
                    adapter.submit(movies)
                    return
                }
                val r = Runnable { runSearch(q, manual = false) }
                searchRunnable = r
                searchHandler.postDelayed(r, 450)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    runSearch(q, manual = true)
                }
                true
            } else {
                false
            }
        }

        loadHome()
        return v
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, TophimSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }

    /**
     * Tai trang chu bang 2 duong SONG SONG (Jsoup + WebView), duong nao ra
     * ket qua truoc thi dung duong do. Cach cu (Jsoup xong moi toi WebView)
     * khien nguoi dung phai cho toi ~90 giay toan vong xoay khi bi chan.
     */
    private fun loadHome() {
        if (loading) return
        loading = true
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        val ctx = context ?: run { loading = false; return }
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)

        fun settle(list: List<Movie>) {
            activity?.runOnUiThread {
                if (!settled.compareAndSet(false, true)) return@runOnUiThread
                loading = false
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                if (list.isNotEmpty()) {
                    setLoadError(false)
                    showHomeList(list)
                } else if (movies.isEmpty()) {
                    // Ca 2 duong tai deu that bai -> gan nhu chac chan web
                    // dang bat o tick xac minh Cloudflare: moi xac minh thu cong
                    setLoadError(true)
                    showVerifyOffer()
                }
            }
        }

        thread {
            RemoteConfig.awaitReady(3500)
            var list: List<Movie> = emptyList()
            try {
                list = TophimSource.fetchHome()
            } catch (e: Exception) {
                // Bi chan: de duong WebView phan dinh
            }
            if (list.isNotEmpty()) settle(list)
        }
        WebFetcher.fetchHtml(ctx, TophimSource.BASE + "/") { html ->
            var list: List<Movie> = emptyList()
            if (html != null) {
                try {
                    list = TophimSource.parseList(
                        TophimSource.parseHtml(html, TophimSource.BASE + "/")
                    )
                } catch (e: Exception) {
                    // bo qua
                }
            }
            settle(list)
        }
        // Phong thu: 45 giay ma chua duong nao xong -> coi nhu that bai
        Handler(Looper.getMainLooper()).postDelayed({ settle(emptyList()) }, 45000)
    }

    private fun setLoadError(show: Boolean) {
        if (!::tvLoadError.isInitialized) return
        tvLoadError.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            tvLoadError.text =
                "Chưa tải được phim: trang web đang chặn app bằng màn hình xác minh " +
                    "Cloudflare. Hãy bấm nút xác minh phía trên, tick vào ô " +
                    "\"Tôi là con người\", chờ trang phim hiện ra rồi quay lại. " +
                    "Nếu đã xác minh mà vẫn không tải được, chụp màn hình này gửi lại nhé."
        }
    }

    /** Hien nut xac minh + tu mo man hinh xac minh 1 lan trong phien nay. */
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
            Intent(requireContext(), VerifyActivity::class.java), REQ_VERIFY
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VERIFY && resultCode == Activity.RESULT_OK) {
            if (::btnVerify.isInitialized) btnVerify.visibility = View.GONE
            searching = false
            loadHome()
        }
    }

    private fun showHomeList(list: List<Movie>) {
        if (::btnVerify.isInitialized) btnVerify.visibility = View.GONE
        homeBackup.clear()
        homeBackup.addAll(list)
        if (!searching) {
            movies.clear()
            movies.addAll(list)
            adapter.submit(movies)
        }
    }

    /** Tim kiem: thu Jsoup truoc, trong/trong thi du phong WebView. */
    private fun runSearch(keyword: String, manual: Boolean) {
        val gen = ++searchGeneration
        searching = true
        progress.visibility = View.VISIBLE
        thread {
            var list: List<Movie> = emptyList()
            try {
                list = TophimSource.search(keyword)
            } catch (e: Exception) {
                // Thu du phong WebView o duoi
            }
            activity?.runOnUiThread {
                if (gen != searchGeneration) return@runOnUiThread
                if (list.isNotEmpty()) {
                    progress.visibility = View.GONE
                    swipe.isRefreshing = false
                    movies.clear()
                    movies.addAll(list)
                    adapter.submit(movies)
                } else {
                    searchViaWeb(keyword, gen, manual)
                }
            }
        }
    }

    private fun searchViaWeb(keyword: String, gen: Int, manual: Boolean) {
        val ctx = context ?: return
        WebFetcher.fetchHtml(ctx, TophimSource.searchUrl(keyword)) { html ->
            var list: List<Movie> = emptyList()
            if (html != null) {
                try {
                    list = TophimSource.parseList(
                        TophimSource.parseHtml(html, TophimSource.searchUrl(keyword))
                    )
                } catch (e: Exception) {
                    // bo qua
                }
            }
            activity?.runOnUiThread {
                if (gen != searchGeneration) return@runOnUiThread
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                if (list.isNotEmpty()) {
                    movies.clear()
                    movies.addAll(list)
                    adapter.submit(movies)
                } else if (manual) {
                    Toast.makeText(ctx, "Không tìm thấy phim phù hợp", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

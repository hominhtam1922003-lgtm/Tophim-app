package com.example.tophimapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.concurrent.thread

/**
 * Trang Loc phim (yeu cau cua chu app): chon THE LOAI + QUOC GIA
 * (kem loai phim / nam / sap xep) roi loc bang endpoint
 * /movies/search. Bang ma the loai (80) + quoc gia (137) tai truc
 * tiep tu API cua web nen web them muc moi la app co ngay.
 * Chon 1 the loai hoac 1 quoc gia roi bam Loc cung chinh la cach
 * duyet phim theo tung the loai / quoc gia.
 */
class FilterFragment : Fragment() {

    companion object {
        // Uu tien hien truoc: dung cac muc tren menu loc cua web
        private val GENRE_PRIORITY = listOf(
            "chinh-kich", "khoa-hoc", "tinh-cam", "co-trang", "tam-ly",
            "bi-an", "hanh-djong", "hinh-su", "hai-huoc", "phieu-luu",
            "gia-djinh", "kinh-di", "tai-lieu", "hoat-hinh", "chieu-rap"
        )
        private val COUNTRY_PRIORITY = listOf(
            "han-quoc", "thai-lan", "nhat-ban", "au-my", "hong-kong",
            "trung-quoc", "anh", "an-djo", "viet-nam", "dai-loan"
        )

        @Volatile
        private var cachedGenres: List<Taxon>? = null
        @Volatile
        private var cachedRegions: List<Taxon>? = null
    }

    /** 1 nhan loc: ten hien thi + toan bo id cung ten (API co ban trung). */
    private data class ChipData(val name: String, val ids: List<Int>, val slug: String)

    private lateinit var cgGenres: ChipGroup
    private lateinit var cgCountries: ChipGroup
    private lateinit var cgType: ChipGroup
    private lateinit var cgSort: ChipGroup
    private lateinit var spYear: Spinner
    private lateinit var tvCount: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnMore: MaterialButton
    private lateinit var adapter: MovieGridAdapter

    private val items = ArrayList<Movie>()
    private var params: Map<String, String> = emptyMap()
    private var currentPage = 1
    private var totalPages = 1
    private var loading = false
    private var loadingMore = false

    private var chipAllTypeId = 0
    private var chipMovieId = 0
    private var chipSeriesId = 0
    private var chipNewestId = 0
    private var chipImdbId = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_filter, container, false)
        cgGenres = v.findViewById(R.id.cgGenres)
        cgCountries = v.findViewById(R.id.cgCountries)
        cgType = v.findViewById(R.id.cgType)
        cgSort = v.findViewById(R.id.cgSort)
        spYear = v.findViewById(R.id.spYear)
        tvCount = v.findViewById(R.id.tvFilterCount)
        progress = v.findViewById(R.id.filterProgress)
        btnMore = v.findViewById(R.id.btnFilterMore)
        btnMore.setOnClickListener { loadMore() }

        cgType.isSingleSelection = true
        chipAllTypeId = addChip(cgType, "Tất cả", single = true)
        chipMovieId = addChip(cgType, "Phim lẻ", single = true)
        chipSeriesId = addChip(cgType, "Phim bộ", single = true)
        cgType.check(chipAllTypeId)

        cgSort.isSingleSelection = true
        chipNewestId = addChip(cgSort, "Mới nhất", single = true)
        chipImdbId = addChip(cgSort, "Điểm IMDb", single = true)
        cgSort.check(chipNewestId)

        val years = ArrayList<String>()
        years.add("Tất cả các năm")
        for (y in 2026 downTo 2012) years.add(y.toString())
        spYear.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item, years
        )

        val rv = v.findViewById<RecyclerView>(R.id.rvFilter)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = MovieGridAdapter(emptyList()) { openDetail(it) }
        rv.adapter = adapter

        tvCount.text = "Chọn thể loại / quốc gia rồi bấm Lọc phim."
        v.findViewById<MaterialButton>(R.id.btnApplyFilter)
            .setOnClickListener { applyFilter() }

        loadTaxonomies()
        return v
    }

    private fun addChip(group: ChipGroup, text: String, single: Boolean): Int {
        val chip = Chip(requireContext())
        chip.id = View.generateViewId()
        chip.text = text
        chip.isCheckable = true
        group.addView(chip)
        return chip.id
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, TophimSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }

    private fun loadTaxonomies() {
        if (cachedGenres != null && cachedRegions != null) {
            buildTaxonChips(cgGenres, cachedGenres!!, GENRE_PRIORITY)
            buildTaxonChips(cgCountries, cachedRegions!!, COUNTRY_PRIORITY)
            return
        }
        thread {
            RemoteConfig.awaitReady(2500)
            var genres: List<Taxon> = emptyList()
            var regions: List<Taxon> = emptyList()
            try {
                genres = TophimSource.fetchCategories()
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            try {
                regions = TophimSource.fetchRegions()
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            if (genres.isNotEmpty()) cachedGenres = genres
            if (regions.isNotEmpty()) cachedRegions = regions
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (genres.isEmpty() && regions.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "Chưa tải được danh sách thể loại/quốc gia, thử lại sau nhé",
                        Toast.LENGTH_LONG
                    ).show()
                }
                buildTaxonChips(cgGenres, genres, GENRE_PRIORITY)
                buildTaxonChips(cgCountries, regions, COUNTRY_PRIORITY)
            }
        }
    }

    /** Gom cac ban ghi trung ten thanh 1 chip (giu moi id cua ten do). */
    private fun buildTaxonChips(
        group: ChipGroup, taxons: List<Taxon>, prioritySlugs: List<String>
    ) {
        group.removeAllViews()
        val byName = LinkedHashMap<String, ChipData>()
        for (t in taxons) {
            if (t.name.matches(Regex("""\d+"""))) continue // muc rac dang so nam
            val cur = byName[t.name]
            if (cur == null) {
                byName[t.name] = ChipData(t.name, listOf(t.id), t.slug)
            } else {
                byName[t.name] = cur.copy(ids = cur.ids + t.id)
            }
        }
        val all = byName.values.toList()
        val prioritized = prioritySlugs.mapNotNull { slug ->
            all.firstOrNull { it.slug == slug }
        }
        val rest = all.filter { it !in prioritized }.sortedBy { it.name }
        for (cd in prioritized + rest) {
            val chip = Chip(requireContext())
            chip.id = View.generateViewId()
            chip.text = cd.name
            chip.isCheckable = true
            chip.tag = cd.ids.joinToString(",")
            group.addView(chip)
        }
    }

    private fun selectedIds(group: ChipGroup): String {
        val ids = ArrayList<String>()
        for (id in group.checkedChipIds) {
            val chip = group.findViewById<Chip>(id) ?: continue
            (chip.tag as? String)?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        return ids.joinToString(",")
    }

    private fun buildParams(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        selectedIds(cgGenres).takeIf { it.isNotEmpty() }?.let { m["genres"] = it }
        selectedIds(cgCountries).takeIf { it.isNotEmpty() }?.let { m["countries"] = it }
        when (cgType.checkedChipId) {
            chipMovieId -> m["type"] = "movie"
            chipSeriesId -> m["type"] = "series"
        }
        val year = spYear.selectedItem?.toString() ?: ""
        if (year.all { it.isDigit() } && year.isNotEmpty()) m["years"] = year
        m["sort"] = if (cgSort.checkedChipId == chipImdbId) "imdb_rating" else "updatedAt"
        return m
    }

    private fun applyFilter() {
        if (loading) return
        loading = true
        params = buildParams()
        progress.visibility = View.VISIBLE
        btnMore.visibility = View.GONE
        thread {
            var page: SearchPage? = null
            try {
                page = TophimSource.searchMovies(params, 1)
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            activity?.runOnUiThread {
                loading = false
                progress.visibility = View.GONE
                if (!isAdded) return@runOnUiThread
                items.clear()
                if (page != null) {
                    items.addAll(page.movies)
                    currentPage = 1
                    totalPages = page.totalPages
                    tvCount.text = if (page.movies.isEmpty()) {
                        "Không có phim nào khớp bộ lọc này."
                    } else {
                        "Tìm thấy ${page.total} phim"
                    }
                } else {
                    totalPages = 1
                    tvCount.text = "Chưa lọc được. Kiểm tra mạng rồi thử lại nhé."
                }
                adapter.submit(items.toList())
                updateMoreVisibility()
            }
        }
    }

    private fun updateMoreVisibility() {
        btnMore.visibility =
            if (currentPage < totalPages && items.isNotEmpty()) View.VISIBLE
            else View.GONE
        btnMore.isEnabled = !loadingMore
    }

    private fun loadMore() {
        if (loading || loadingMore || currentPage >= totalPages) return
        loadingMore = true
        updateMoreVisibility()
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
                if (!isAdded) return@runOnUiThread
                if (page != null && page.movies.isNotEmpty()) {
                    val known = items.map { it.url }.toHashSet()
                    val added = page.movies.filter { known.add(it.url) }
                    if (added.isNotEmpty()) {
                        items.addAll(added)
                        currentPage = next
                        adapter.submit(items.toList())
                    } else {
                        totalPages = currentPage
                    }
                } else {
                    totalPages = currentPage
                    Toast.makeText(
                        requireContext(), "Đã tải hết kết quả", Toast.LENGTH_SHORT
                    ).show()
                }
                updateMoreVisibility()
            }
        }
    }
}

package com.example.tophimapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Nguon du lieu: cobephim.icu — app "Cine" (tu ban 2.0).
 *
 * KET QUA KHAO SAT TRUC TIEP (22/10/2026, 2 vong doc goi JS + goi thu API):
 * Web la Next.js, du lieu nam o API JSON CONG KHAI rieng (khong can
 * dang nhap/token cho cac endpoint doc):
 *   API goc: https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1
 *   - GET /movies/hot                          -> {result: [phim]}
 *   - GET /lists/homepageLists?page&limit      -> {result:{collections:[{name, movies[]}]}}
 *   - GET /movies/search?keyword=&countries=&genres=&years=&type=&sort=&page=
 *                                              -> {result: [phim], meta:{total,totalPages}}
 *   - GET /movies/by-slug/{slug}               -> {movie: {...chi tiet...}}
 *   - GET /episodes/by-idMovie/{movieId}       -> mang THANG toan bo tap
 *        (khong phan trang; moi tap co id + server chua nhan ban chieu:
 *         "Phu de", "Thuyet Minh", "Long tieng", "#Ha Noi (Vietsub)"...)
 *   - GET /categories?limit=100 / /regions?limit=100&page= : bang ma
 *        the loai (80) + quoc gia (137) cho trang Loc phim.
 * Anh bia DOC nam o field "poster" (ban ngang la "thumbnail"),
 * host anh icdn.darkbytes.xyz.
 * PHAT PHIM: phan hoi cong khai KHONG chua link luong (link that khoa
 * sau token) — duong phat chinh thuc ma web dung la trang
 *   {host API}/embed/{episodeId}
 * PlayerActivity se do trang do de bat luong thang; khong bat duoc
 * moi phat bang chinh trang embed do (da khoa popup/dieu huong).
 * Web KHONG co Cloudflare, KHONG cong tuoi, KHONG quang cao banner;
 * click tren web hay bat cua so quang cao rieng (popunder) — app doc
 * API nen khong dung toi co che do.
 */
object TophimSource {

    const val DEFAULT_BASE = "https://cobephim.icu"
    const val DEFAULT_API_BASE = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1"

    /** Ten mien web dang dung (RemoteConfig cap nhat khi web doi ten mien). */
    @Volatile
    var activeBase: String = DEFAULT_BASE

    /** Goc API dang dung (RemoteConfig cap nhat cung config). */
    @Volatile
    var activeApiBase: String = DEFAULT_API_BASE

    val BASE: String
        get() = activeBase

    val API_BASE: String
        get() = activeApiBase

    /** Host goc cua API (bo hau to /api/v1): noi o trang phat /embed/{id}. */
    val API_HOST: String
        get() = Regex("""^https?://[^/]+""").find(activeApiBase)?.value ?: activeApiBase

    /** Trang phat chinh thuc cua 1 tap. */
    fun embedUrl(episodeId: Long): String = "$API_HOST/embed/$episodeId"

    fun detailUrl(slug: String): String = "$BASE/phim/$slug"

    /** URL da luu co the con o ten mien cu: doi host ve ten mien dang dung. */
    fun rebase(url: String): String {
        if (!url.contains("/phim/")) return url
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (host == activeBase) url else activeBase + url.substring(host.length)
    }

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36"

    /**
     * User-Agent dung chung cho ca app. MainActivity ghi de bang UA THAT
     * cua may ngay khi mo app (thua huong tu thoi nguon cu can dau van
     * tay nhat quan; nguon moi khong chan bot nen khong con bat buoc,
     * giu de moi request trong app dong nhat).
     */
    @Volatile
    var UA: String = FALLBACK_UA

    fun slugOf(detailUrl: String): String =
        detailUrl.substringAfter("/phim/").substringBefore("?").trimEnd('/')

    // ---------------- HTTP JSON ----------------

    private fun getText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code")
            return text
        } finally {
            conn?.disconnect()
        }
    }

    private fun getJsonObject(path: String): JSONObject =
        JSONObject(getText(API_BASE + path))

    private fun getJsonArray(path: String): JSONArray =
        JSONArray(getText(API_BASE + path))

    /** Ma hoa gia tri tham so truy van (giu nguyen dau phay cua danh sach id). */
    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8").replace("%2C", ",")

    // ---------------- Anh xa phim ----------------

    fun movieFromJson(o: JSONObject): Movie {
        val slug = o.optString("slug")
        val title = o.optString("name").ifBlank { o.optString("origin_name") }
        var poster = o.optString("poster")
        if (poster.isBlank()) poster = o.optString("thumbnail")
        val parts = ArrayList<String>()
        o.optString("quality").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val cur = o.optString("episode_current").trim()
        if (cur.isNotEmpty()) {
            parts.add(if (cur.all { it.isDigit() }) "Tập $cur" else cur)
        }
        val year = o.optInt("publish_year", 0)
        if (year > 0) parts.add(year.toString())
        return Movie(title, detailUrl(slug), poster, parts.joinToString(" • "))
    }

    private fun moviesFromArray(arr: JSONArray?): List<Movie> {
        if (arr == null) return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val m = movieFromJson(o)
            if (m.title.isNotEmpty() && o.optString("slug").isNotEmpty()) out.add(m)
        }
        return out
    }

    // ---------------- Danh sach / tim kiem / loc ----------------

    /** Co trang chuan cua web: 32 phim/trang (khop trang danh sach tren web). */
    const val PAGE_SIZE = 32

    /**
     * Endpoint dung chung cho moi danh sach cua web: tim tu khoa, phim
     * le/bo, chieu rap, the loai, quoc gia, trang loc. Params hop le:
     * keyword, countries (id cach nhau dau phay), genres, years,
     * type (movie|series), sort (updatedAt|imdb_rating), rating.
     * LUON kem limit=PAGE_SIZE: thieu tham so nay API chi tra ~5
     * phim/trang (loi ban 2.0: cac tab danh muc chi thay vai phim).
     */
    fun searchMovies(params: Map<String, String>, page: Int): SearchPage {
        val q = StringBuilder()
        for ((k, v) in params) {
            if (v.isBlank()) continue
            if (q.isNotEmpty()) q.append('&')
            q.append(k).append('=').append(enc(v))
        }
        if (q.isNotEmpty()) q.append('&')
        q.append("limit=").append(PAGE_SIZE).append("&page=").append(page)
        val o = getJsonObject("/movies/search?$q")
        val movies = moviesFromArray(o.optJSONArray("result"))
        val meta = o.optJSONObject("meta")
        // CAN TRONG: meta.total/totalPages cua API bi loi (total =
        // page*limit+1, tang dan theo trang dang xem) — ban 2.0 tin
        // totalPages nen cac tab dung o ~15 phim. Quy tac dung (da
        // kiem chung truc tiep: trang sau van con phim that): trang
        // day (du PAGE_SIZE phim) nghia la con trang sau; trang thieu
        // phim la trang cuoi.
        val totalPages = if (movies.size >= PAGE_SIZE) page + 1 else page
        return SearchPage(
            movies = movies,
            totalPages = totalPages,
            total = meta?.optInt("total", 0) ?: 0
        )
    }

    fun searchKeyword(keyword: String, page: Int): SearchPage =
        searchMovies(mapOf("keyword" to keyword), page)

    fun fetchHot(): List<Movie> =
        moviesFromArray(getJsonObject("/movies/hot").optJSONArray("result"))

    /** Cac muc cua trang chu dung nhu tren web ("Phim Song Ngu", ...). */
    fun fetchHomeSections(): List<HomeSection> {
        val out = ArrayList<HomeSection>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages && page <= 3) {
            val o = getJsonObject("/lists/homepageLists?page=$page&limit=15")
            val result = o.optJSONObject("result") ?: break
            totalPages = result.optInt("totalPages", 1)
            val cols = result.optJSONArray("collections") ?: break
            for (i in 0 until cols.length()) {
                val c = cols.optJSONObject(i) ?: continue
                val movies = moviesFromArray(c.optJSONArray("movies"))
                val name = c.optString("name")
                if (name.isNotEmpty() && movies.isNotEmpty()) {
                    out.add(HomeSection(name, movies))
                }
            }
            page++
        }
        return out
    }

    // ---------------- Chi tiet + danh sach tap ----------------

    fun fetchDetail(detailUrl: String): MovieDetail {
        val slug = slugOf(rebase(detailUrl))
        val o = getJsonObject("/movies/by-slug/$slug")
        val m = o.optJSONObject("movie")
            ?: throw Exception("API khong tra ve phim $slug")
        val movie = movieFromJson(m)

        val meta = ArrayList<String>()
        val origin = m.optString("origin_name")
        if (origin.isNotBlank() && origin != movie.title) meta.add("Tên gốc: $origin")
        val year = m.optInt("publish_year", 0)
        if (year > 0) meta.add("Năm phát hành: $year")
        m.optString("quality").takeIf { it.isNotBlank() }
            ?.let { meta.add("Chất lượng: $it") }
        m.optString("language").takeIf { it.isNotBlank() }
            ?.let { meta.add("Ngôn ngữ: $it") }
        m.optString("episode_time").takeIf { it.isNotBlank() }
            ?.let { meta.add("Thời lượng: $it") }
        if (m.optString("type") == "series") {
            val curEp = m.optString("episode_current")
            val totEp = m.optString("episode_total")
            meta.add("Số tập: $curEp/$totEp")
        }
        val imdb = m.optDouble("imdb_rating", 0.0)
        if (imdb > 0) meta.add("Điểm IMDb: $imdb")
        val cats = namesOf(m.optJSONArray("categories"))
        if (cats.isNotEmpty()) meta.add("Thể loại: ${cats.joinToString(", ")}")
        val dirs = namesOf(m.optJSONArray("directors"))
        if (dirs.isNotEmpty()) meta.add("Đạo diễn: ${dirs.joinToString(", ")}")
        val acts = namesOf(m.optJSONArray("actors"))
        if (acts.isNotEmpty()) meta.add("Diễn viên: ${acts.take(8).joinToString(", ")}")

        val descHtml = m.optString("description")
        val description = if (descHtml.isBlank()) "" else try {
            org.jsoup.Jsoup.parse(descHtml).text()
        } catch (e: Exception) {
            descHtml.replace(Regex("<[^>]+>"), " ").trim()
        }

        val movieId = m.optLong("id", 0)
        val servers = if (movieId > 0) fetchEpisodeGroups(movieId) else emptyList()
        return MovieDetail(
            movie = movie,
            meta = meta.joinToString("\n"),
            description = description,
            servers = servers
        )
    }

    /** Ten trong mang doi tuong ({name}) hoac mang chuoi thang. */
    private fun namesOf(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            val name = when (v) {
                is JSONObject -> v.optString("name")
                is String -> v
                else -> ""
            }
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    /**
     * Toan bo tap cua 1 phim, gom nhom theo nhan "server" cua API —
     * do chinh la cac BAN CHIEU (Phu de / Thuyet Minh / Long tieng /
     * Song ngu / may chu Ha Noi...). Moi tap la 1 trang phat embed.
     * Phim bo dai (vd Conan) API tra ~13.000 ban ghi 1 lan (~1,7 MB):
     * van tai tron 1 lan, gom nhom + sap xep trong may.
     */
    fun fetchEpisodeGroups(movieId: Long): List<ServerGroup> {
        val arr = getJsonArray("/episodes/by-idMovie/$movieId")
        val groups = LinkedHashMap<String, LinkedHashMap<String, Episode>>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optLong("id", 0)
            if (id <= 0) continue
            val server = e.optString("server").ifBlank { "Mặc định" }
            val raw = e.optString("name").trim()
            if (raw.isEmpty()) continue
            val label = when {
                raw.startsWith("Tập", true) -> raw
                raw.equals("Full", true) -> "Full"
                raw.matches(Regex("""\d+(\.\d+)?""")) -> "Tập $raw"
                else -> raw
            }
            val g = groups.getOrPut(server) { LinkedHashMap() }
            if (!g.containsKey(label)) {
                g[label] = Episode(label, embedUrl(id))
            }
        }
        return groups.entries.map { (server, eps) ->
            val sorted = eps.values.sortedWith(
                compareBy({ epNum(it.name) ?: Double.MAX_VALUE }, { it.name })
            )
            ServerGroup(server, sorted)
        }
    }

    private fun epNum(name: String): Double? =
        Regex("""[\d]+(?:\.[\d]+)?""").find(name)?.value?.toDoubleOrNull()

    // ---------------- Bang ma the loai / quoc gia ----------------

    fun fetchCategories(): List<Taxon> = fetchTaxons("/categories")

    fun fetchRegions(): List<Taxon> = fetchTaxons("/regions")

    private fun fetchTaxons(path: String): List<Taxon> {
        val out = ArrayList<Taxon>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages && page <= 5) {
            val o = getJsonObject("$path?limit=100&page=$page")
            val meta = o.optJSONObject("meta")
            totalPages = meta?.optInt("totalPages", 1) ?: 1
            val data = o.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val t = data.optJSONObject(i) ?: continue
                val id = t.optInt("id", 0)
                val name = t.optString("name")
                if (id > 0 && name.isNotBlank()) {
                    out.add(Taxon(id, name, t.optString("slug")))
                }
            }
            page++
        }
        return out
    }
}

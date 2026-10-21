package com.example.tophimapp

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Nguon du lieu: tophim.pro (Top Phim / ToPhim).
 *
 * KET QUA KHAO SAT TRUC TIEP (22/10/2026):
 * - Trang chu: tieu de "To Phim - Xem Phim Khong Gioi Han"
 * - Trang chi tiet phim: /phim/{slug}   (khong co ID so o cuoi)
 * - Trang xem tap:       /xem/{slug}/{tap}={may-chu}={may-chu}
 *   vi du: /xem/nghi-pham-dang-yeu-cua-toi/1=may-chu-sn-may-chu-sn
 * - Tim kiem: /filter?search={tu-khoa}  (render phia server)
 * - Poster: anh TMDB (image.tmdb.org) proxy qua wsrv.nl
 * - Video: nhung <iframe> tu playembed.vip/player/{hash}/sub
 * - Web co Cloudflare kiem tra bot moi lan tai trang -> Jsoup co the bi chan,
 *   cac man hinh chinh deu co duong du phong WebView (WebFetcher).
 */
object TophimSource {

    const val DEFAULT_BASE = "https://tophim.pro"

    /** Ten mien dang dung that (RemoteConfig cap nhat khi web doi ten mien). */
    @Volatile
    var activeBase: String = DEFAULT_BASE

    val BASE: String
        get() = activeBase

    /** URL da luu co the con o ten mien cu: doi host ve ten mien dang dung. */
    fun rebase(url: String): String {
        if (!url.contains("/phim/") && !url.contains("/xem/")) return url
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (host == activeBase) url else activeBase + url.substring(host.length)
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    class BlockedException : Exception("Cloudflare blocked")

    private val detailRegex = Regex("""/phim/[a-z0-9\-]+$""")
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val qualityRegex = Regex("""\b(FHD|HD|CAM|SD|4K)\b""", RegexOption.IGNORE_CASE)
    private val episodeTextRegex = Regex("""Tập\s*[\d]+[^\s•|]*""", RegexOption.IGNORE_CASE)

    fun slugOf(detailUrl: String): String =
        detailUrl.substringAfter("/phim/").substringBefore("?").trimEnd('/')

    fun fetchDoc(rawUrl: String): Document {
        val url = rebase(rawUrl)
        val doc = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
            .header("Referer", "$BASE/")
            .timeout(30000)
            .followRedirects(true)
            .get()
        val text = doc.text()
        if (doc.title().contains("Just a moment", true) ||
            text.contains("xác minh bạn là con người", true) ||
            text.contains("Thực hiện xác minh bảo mật", true)
        ) {
            throw BlockedException()
        }
        return doc
    }

    fun parseHtml(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    private fun imgUrl(img: org.jsoup.nodes.Element): String {
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "data-url", "src")) {
            val v = img.absUrl(attr)
            if (v.isNotEmpty() && !v.contains("placeholder", true) && !v.endsWith("loading.gif")) {
                return v
            }
        }
        return img.absUrl("src")
    }

    // ---------------- Danh sach phim ----------------

    fun fetchHome(): List<Movie> = parseList(fetchDoc("$BASE/"))

    /** Duong dan danh muc (da xac thuc tren web that 22/10/2026). */
    fun categoryPaths(kind: String): List<String> = when (kind) {
        "series" -> listOf("/danh-sach/phim-bo", "/phim-bo")
        "adult" -> listOf("/the-loai/phim-18-1", "/the-loai/phim-18-2")
        else -> listOf("/danh-sach/phim-le", "/phim-le")
    }

    fun fetchCategory(kind: String): List<Movie> {
        val paths = categoryPaths(kind)
        for (p in paths) {
            try {
                val list = parseList(fetchDoc("$BASE$p"))
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // Thu duong dan tiep theo
            }
        }
        return fetchHome()
    }

    fun searchUrl(keyword: String): String =
        "$BASE/filter?search=${java.net.URLEncoder.encode(keyword, "UTF-8")}"

    fun search(keyword: String): List<Movie> = parseList(fetchDoc(searchUrl(keyword)))

    fun parseList(doc: Document): List<Movie> {
        val result = LinkedHashMap<String, Movie>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").substringBefore("?").trimEnd('/')
            if (!detailRegex.containsMatchIn(href)) continue
            if (result.containsKey(href)) continue
            // Anh poster: trong the a hoac o khoi cha gan nhat
            var container: org.jsoup.nodes.Element? = a
            var img: org.jsoup.nodes.Element? = a.selectFirst("img")
            var depth = 0
            while (img == null && container != null && depth < 4) {
                container = container.parent()
                img = container?.selectFirst("img")
                depth++
            }
            if (img == null) continue
            val title = img.attr("alt").trim()
                .ifBlank { a.attr("title").trim() }
                .ifBlank {
                    // Tim tieu de o the h2/h3 gan nhat trong khoi item
                    var n: org.jsoup.nodes.Element? = a
                    var t = ""
                    var d = 0
                    while (n != null && d < 4 && t.isEmpty()) {
                        t = n.selectFirst("h2, h3, h4")?.text()?.trim() ?: ""
                        n = n.parent(); d++
                    }
                    t
                }
                .ifBlank { a.text().trim() }
            if (title.isEmpty() || title.length < 2) continue
            // Dong phu: nam + chat luong + tap moi nhat trong khoi item
            val blockText = (container?.text() ?: a.parent()?.text() ?: "").take(400)
            val parts = ArrayList<String>()
            yearRegex.find(blockText)?.value?.let { parts.add(it) }
            qualityRegex.find(blockText)?.value?.uppercase()?.let { parts.add(it) }
            episodeTextRegex.find(blockText)?.value?.trim()?.let { parts.add(it) }
            result[href] = Movie(title, href, imgUrl(img), parts.joinToString(" • "))
        }
        return result.values.toList()
    }

    // ---------------- Chi tiet phim + danh sach tap ----------------

    fun fetchDetail(detailUrl: String): MovieDetail = parseDetail(fetchDoc(detailUrl), detailUrl)

    fun parseDetail(doc: Document, detailUrl: String): MovieDetail {
        val url = rebase(detailUrl)
        val slug = slugOf(url)
        var title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        }
        if (title.isEmpty()) {
            title = doc.title().substringBefore("|").substringBefore("- ToPhim").trim()
        }

        var poster = ""
        val imgs = doc.select("img")
        val posterImg = imgs.firstOrNull { it.attr("alt").trim() == title && title.isNotEmpty() }
            ?: imgs.firstOrNull {
                val s = it.absUrl("data-src") + it.absUrl("src")
                s.contains("tmdb") || s.contains("wsrv")
            }
            ?: imgs.firstOrNull { imgUrl(it).contains(".jpg") }
        if (posterImg != null) poster = imgUrl(posterImg)

        // Meta: quet cac dong "Nhan: gia tri"
        val labels = listOf(
            "Trạng thái", "Đã chiếu", "Loại", "Thời lượng", "Chất lượng",
            "Ngôn ngữ", "Thể loại", "Quốc gia", "Đạo diễn", "Diễn viên", "Năm phát hành"
        )
        val metaLines = ArrayList<String>()
        val seenLabels = HashSet<String>()
        for (el in doc.select("li, tr, p")) {
            val t = el.text().trim()
            if (t.length > 140) continue
            for (label in labels) {
                if (label in seenLabels) continue
                if (t.startsWith(label, true) && t.contains(":")) {
                    metaLines.add(t)
                    seenLabels.add(label)
                    break
                }
            }
            if (metaLines.size >= 8) break
        }
        if (metaLines.isEmpty()) {
            yearRegex.find(doc.selectFirst("h1")?.parent()?.text() ?: "")?.value
                ?.let { metaLines.add("Năm: $it") }
        }

        var description = ""
        val introHead = doc.select("h2, h3").firstOrNull {
            it.text().contains("Nội dung", true) || it.text().contains("Giới thiệu", true)
        }
        if (introHead != null) {
            var sib = introHead.nextElementSibling()
            val sb = StringBuilder()
            while (sib != null && sb.length < 1500 && !sib.tagName().equals("h2", true)) {
                sb.append(sib.text()).append("\n")
                sib = sib.nextElementSibling()
            }
            description = sb.toString().trim()
        }
        if (description.isEmpty()) {
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim() ?: ""
        }

        // Danh sach tap: gom theo may chu (hau to sau dau = trong URL xem)
        val groups = LinkedHashMap<String, LinkedHashMap<String, Episode>>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").substringBefore("?").trimEnd('/')
            if (!href.contains("/xem/$slug/")) continue
            val tail = href.substringAfter("/xem/$slug/")
            val epSegment = tail.substringBefore("=")
            val serverKey = tail.substringAfter("=", "")
            val label = a.text().trim().ifBlank {
                if (epSegment.all { it.isDigit() } && epSegment.isNotEmpty()) "Tập $epSegment"
                else epSegment.replace('-', ' ')
            }
            if (label.isEmpty()) continue
            val g = groups.getOrPut(serverKey) { LinkedHashMap() }
            g[href] = Episode(label, href)
        }
        val servers = groups.entries.mapIndexed { idx, (_, eps) ->
            val sorted = eps.values.sortedWith(
                compareBy({ epNum(it.name) ?: Double.MAX_VALUE }, { it.name })
            )
            ServerGroup("Máy chủ ${idx + 1}", sorted)
        }

        return MovieDetail(
            movie = Movie(title.ifEmpty { slug }, url, poster),
            meta = metaLines.joinToString("\n"),
            description = description,
            servers = servers
        )
    }

    private fun epNum(name: String): Double? =
        Regex("""[\d]+(?:\.[\d]+)?""").find(name)?.value?.toDoubleOrNull()

    // ---------------- Trang xem: lay link phat video ----------------

    /**
     * Trich cac nguon phat tu trang xem (xac thuc tren web that 22/10/2026):
     * - Trang xem KHONG co san <iframe>: link phat nam trong thuoc tinh
     *   data-embed (may chu playembed.vip/player/{hash}) va data-embedbackup
     *   (may chu du phong, la file HLS .m3u8 thang tren play.vinahost.id).
     * - iframe chi duoc tao bang JS sau khi nguoi dung bam nut phat.
     */
    fun extractPlayers(doc: Document): PlayerLinks {
        var hls = ""
        var embed = ""
        val backupEl = doc.selectFirst("[data-embedbackup]")
        if (backupEl != null) {
            hls = backupEl.attr("data-embedbackup").trim()
        }
        val embedEl = doc.selectFirst("[data-embed]")
        if (embedEl != null) {
            embed = embedEl.attr("data-embed").trim()
        }
        if (hls.isEmpty()) {
            // Du phong: tim thang link .m3u8 trong HTML
            val m = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(doc.html())
            if (m != null) hls = m.value
        }
        if (embed.isEmpty()) {
            val iframes = doc.select("iframe[src]").map { it.absUrl("src") }
                .filter { it.startsWith("http") }
            embed = iframes.firstOrNull { it.contains("playembed", true) } ?: ""
        }
        if (hls.startsWith("//")) hls = "https:$hls"
        if (embed.startsWith("//")) embed = "https:$embed"
        // playembed: iframe that cua web co dang /player/{hash}/sub
        if (embed.contains("playembed") && Regex("""/player/[A-Za-z0-9]+$""").containsMatchIn(embed)) {
            embed = "$embed/sub"
        }
        return PlayerLinks(hls = hls, embed = embed)
    }
}

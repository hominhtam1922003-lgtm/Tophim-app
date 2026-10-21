package com.example.tophimapp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Loc quang cao GHEP SAN trong playlist HLS (server-side ad insertion):
 * mot so nha phat ghep doan quang cao (vai chuc giay) vao DAU cung mot
 * file .m3u8 cua phim, ngan cach bang the #EXT-X-DISCONTINUITY. Phat
 * thang file goc thi phai xem het quang cao moi toi phim.
 *
 * Bo loc tai playlist ve, cat cac doan ngan o dau (moi doan < 180
 * giay) trong khi phan con lai du dai (>= 600 giay - chac chan la
 * phim), roi viet lai playlist chi con phan phim (URI cua tung
 * segment doi thanh tuyet doi de phat qua blob URL trong trang HLS
 * noi bo). Nguyen tac an toan: bat ky diem nao khong chac chan
 * (playlist co ma hoa EXT-X-KEY, dang fMP4 EXT-X-MAP, khong co dau
 * phan doan, tai ve loi...) deu tra "giu nguyen file goc" - chi giup
 * khi that su chac, khong bao gio lam hong nguon dang phat tot.
 */
object PlaylistCleaner {

    /** playUrl: URL se phat; overrideText: noi dung playlist da loc
     * (null = phat thang playUrl nhu cu). */
    data class Result(val playUrl: String, val overrideText: String?)

    fun clean(url: String, referer: String): Result {
        return try {
            cleanInner(url, referer)
        } catch (e: Exception) {
            Result(url, null)
        }
    }

    private fun cleanInner(url: String, referer: String): Result {
        val text = fetch(url, referer) ?: return Result(url, null)
        if (text.contains("#EXT-X-STREAM-INF")) {
            // Playlist tong (master): kiem tra playlist con dau tien.
            // Con sach -> phat master goc (giu co che chon chat
            // luong); con bi ghep QC -> phat ban da loc cua con.
            val variant = firstVariantUri(text, url) ?: return Result(url, null)
            val mediaText = fetch(variant, referer) ?: return Result(url, null)
            val stripped = stripAds(mediaText, variant) ?: return Result(url, null)
            return Result(variant, stripped)
        }
        val stripped = stripAds(text, url) ?: return Result(url, null)
        return Result(url, stripped)
    }

    private fun fetch(url: String, referer: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", TophimSource.UA)
        if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
        if (conn.responseCode !in 200..299) return null
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
            var line = r.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = r.readLine()
            }
        }
        val t = sb.toString()
        return if (t.contains("#EXTM3U")) t else null
    }

    private fun firstVariantUri(master: String, base: String): String? {
        val lines = master.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until lines.size) {
                    val u = lines[j].trim()
                    if (u.isNotEmpty() && !u.startsWith("#")) return resolve(base, u)
                }
            }
        }
        return null
    }

    private fun resolve(base: String, uri: String): String = try {
        URI(base).resolve(uri).toString()
    } catch (e: Exception) {
        uri
    }

    private class Section(val lines: ArrayList<String>, var duration: Double)

    /** Playlist da cat QC, hoac null neu khong can/khong nen cat. */
    private fun stripAds(media: String, base: String): String? {
        // Ma hoa hoac fMP4: cau truc phuc tap, khong dung vao
        if (media.contains("#EXT-X-KEY") || media.contains("#EXT-X-MAP")) return null
        val header = ArrayList<String>()
        val sections = ArrayList<Section>()
        var cur = Section(ArrayList(), 0.0)
        sections.add(cur)
        var inHeader = true
        var pendingDur: Double? = null
        var endlist = false
        for (raw in media.lines()) {
            val line = raw.trim()
            when {
                line == "#EXT-X-ENDLIST" -> endlist = true
                line == "#EXT-X-DISCONTINUITY" -> {
                    if (!inHeader) {
                        cur = Section(ArrayList(), 0.0)
                        sections.add(cur)
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    inHeader = false
                    pendingDur = line.removePrefix("#EXTINF:")
                        .substringBefore(",").toDoubleOrNull() ?: 0.0
                    cur.lines.add(line)
                }
                line.startsWith("#") -> {
                    if (inHeader) header.add(line) else cur.lines.add(line)
                }
                line.isEmpty() -> Unit
                else -> {
                    // Dong URI cua mot segment
                    inHeader = false
                    cur.lines.add(resolve(base, line))
                    cur.duration += pendingDur ?: 0.0
                    pendingDur = null
                }
            }
        }
        val real = sections.filter { it.duration > 0.0 }
        if (real.size < 2) return null
        // Cat dan cac doan ngan o DAU (QC ghep) khi phan con lai
        // van la phim dai; luon giu lai it nhat 1 doan
        var drop = 0
        while (real.size - drop > 1) {
            val first = real[drop]
            val rest = real.drop(drop + 1).sumOf { it.duration }
            if (first.duration in 0.1..179.9 && rest >= 600.0) drop++ else break
        }
        if (drop == 0) return null
        val kept = real.drop(drop)
        if (kept.sumOf { it.duration } < 600.0) return null
        var target = 0
        for (s in kept) {
            for (l in s.lines) {
                if (l.startsWith("#EXTINF:")) {
                    val d = l.removePrefix("#EXTINF:")
                        .substringBefore(",").toDoubleOrNull() ?: 0.0
                    if (d.toInt() + 1 > target) target = d.toInt() + 1
                }
            }
        }
        val out = StringBuilder()
        out.append("#EXTM3U\n")
        for (h in header) {
            if (h.startsWith("#EXTM3U")) continue
            if (h.startsWith("#EXT-X-MEDIA-SEQUENCE")) continue
            if (h.startsWith("#EXT-X-TARGETDURATION")) continue
            if (h.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) continue
            out.append(h).append('\n')
        }
        out.append("#EXT-X-TARGETDURATION:").append(if (target > 0) target else 10)
            .append('\n')
        out.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        kept.forEachIndexed { idx, s ->
            if (idx > 0) out.append("#EXT-X-DISCONTINUITY\n")
            for (l in s.lines) out.append(l).append('\n')
        }
        if (endlist) out.append("#EXT-X-ENDLIST\n")
        return out.toString()
    }
}

package com.example.tophimapp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Chong doi ten mien cho nguon truyen:
 * 1) Doc file config.json nam tren repo GitHub cua chu app (sua 1 dong la
 *    moi ban app dang cai tu cap nhat ten mien moi, khong can cai lai).
 * 2) Thu lan luot: ten mien trong config -> cac ten mien guong -> ten mien
 *    tot gan nhat (luu trong may) -> ten mien mac dinh.
 * 3) Ten mien nao tra ve dung trang TruyenQQ thi chot dung ten mien do.
 */
object RemoteConfig {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/hominhtam1922003-lgtm/Tophim-app/main/config.json"
    private const val PREFS = "remote_config"
    private const val KEY_LAST_GOOD = "last_good_base"

    private val latch = CountDownLatch(1)
    @Volatile private var started = false

    /** Goi tu MainActivity: chay ngam, khong chan giao dien. */
    fun resolveAsync(context: Context) {
        if (started) return
        started = true
        val appCtx = context.applicationContext
        thread { resolveBlocking(appCtx) }
    }

    /** Cac man hinh cho toi da [timeoutMs] de biet ten mien truoc khi tai. */
    fun awaitReady(timeoutMs: Long) {
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Bo qua: dung ten mien hien tai
        }
    }

    fun resolveBlocking(context: Context) {
        try {
            val candidates = ArrayList<String>()

            // 1) Cau hinh tu xa tren GitHub
            fetchConfig()?.let { json ->
                json.optString("base").trim().trimEnd('/').takeIf { it.startsWith("http") }
                    ?.let { candidates.add(it) }
                json.optJSONArray("mirrors")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().trimEnd('/').takeIf { it.startsWith("http") }
                            ?.let { candidates.add(it) }
                    }
                }
            }

            // 2) Ten mien tot gan nhat + mac dinh
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_LAST_GOOD, null)?.let { candidates.add(it) }
            candidates.add(TophimSource.DEFAULT_BASE)

            // 3) Thu tung ten mien, chot cai dau tien dung duoc
            for (candidate in candidates.distinct()) {
                val effective = probe(candidate)
                if (effective != null) {
                    TophimSource.activeBase = effective
                    prefs.edit().putString(KEY_LAST_GOOD, effective).apply()
                    break
                }
            }
        } catch (e: Exception) {
            // Giu nguyen ten mien dang dung
        } finally {
            latch.countDown()
        }
    }

    private fun fetchConfig(): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", TophimSource.UA)
            }
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Dung duoc = tai duoc trang chu va thay link truyen that (khong phai trang chan).
     * Tra ve ten mien thuc te (sau chuyen huong neu co), null neu khong dung duoc.
     */
    private fun probe(base: String): String? {
        return try {
            val doc = org.jsoup.Jsoup.connect("$base/")
                .userAgent(TophimSource.UA)
                .header("Accept-Language", "vi-VN,vi;q=0.9")
                .cookies(TophimSource.cfCookies())
                .timeout(8000)
                .followRedirects(true)
                .get()
            if (doc.title().contains("Just a moment", true)) return null
            if (doc.selectFirst("a[href*=/phim/]") == null) return null
            // Neu bi chuyen huong sang ten mien moi, hoc luon ten mien dich
            val m = Regex("""^(https?://[^/]+)""").find(doc.location())
            m?.value ?: base
        } catch (e: Exception) {
            null
        }
    }
}

package com.example.tophimapp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Chong doi ten mien cho nguon phim:
 * 1) Doc file config.json nam tren repo GitHub cua chu app (sua file do
 *    la moi ban app dang cai tu cap nhat, khong can cai lai):
 *      {"base": "https://cobephim.icu",
 *       "apiBase": "https://...darkbytes.xyz/api/v1",
 *       "mirrors": [...], "apiMirrors": [...]}
 * 2) Thu lan luot: gia tri trong config -> cac ten mien guong -> ten
 *    mien tot gan nhat (luu trong may) -> mac dinh trong code.
 * 3) API chot khi goi /movies/hot tra JSON co "result"; web chot khi
 *    mo duoc trang chu (bat ky phan hoi HTTP nao tu ten mien song).
 */
object RemoteConfig {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/hominhtam1922003-lgtm/Tophim-app/main/config.json"
    private const val PREFS = "remote_config"
    private const val KEY_LAST_GOOD = "last_good_base"
    private const val KEY_LAST_GOOD_API = "last_good_api"

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
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var base = TophimSource.DEFAULT_BASE
            var apiBase = TophimSource.DEFAULT_API_BASE

            fetchConfig()?.let { json ->
                json.optString("base").trim().trimEnd('/')
                    .takeIf { it.startsWith("http") }?.let { base = it }
                json.optString("apiBase").trim().trimEnd('/')
                    .takeIf { it.startsWith("http") }?.let { apiBase = it }

                val apiCandidates = ArrayList<String>()
                apiCandidates.add(apiBase)
                json.optJSONArray("apiMirrors")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().trimEnd('/')
                            .takeIf { it.startsWith("http") }?.let { apiCandidates.add(it) }
                    }
                }
                prefs.getString(KEY_LAST_GOOD_API, null)?.let { apiCandidates.add(it) }
                apiCandidates.add(TophimSource.DEFAULT_API_BASE)
                for (candidate in apiCandidates.distinct()) {
                    if (probeApi(candidate)) {
                        TophimSource.activeApiBase = candidate
                        prefs.edit().putString(KEY_LAST_GOOD_API, candidate).apply()
                        break
                    }
                }

                val baseCandidates = ArrayList<String>()
                baseCandidates.add(base)
                json.optJSONArray("mirrors")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().trimEnd('/')
                            .takeIf { it.startsWith("http") }?.let { baseCandidates.add(it) }
                    }
                }
                prefs.getString(KEY_LAST_GOOD, null)?.let { baseCandidates.add(it) }
                baseCandidates.add(TophimSource.DEFAULT_BASE)
                for (candidate in baseCandidates.distinct()) {
                    if (probeWeb(candidate)) {
                        TophimSource.activeBase = candidate
                        prefs.edit().putString(KEY_LAST_GOOD, candidate).apply()
                        break
                    }
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

    private fun httpGet(url: String, timeoutMs: Int): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", TophimSource.UA)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn?.disconnect()
        }
    }

    /** API dung duoc = /movies/hot tra JSON chua danh sach ket qua. */
    private fun probeApi(apiBase: String): Boolean {
        return try {
            val (code, body) = httpGet("$apiBase/movies/hot", 8000)
            code == 200 && body.contains("\"result\"")
        } catch (e: Exception) {
            false
        }
    }

    /** Web dung duoc = ten mien con song (tra phan hoi HTTP that). */
    private fun probeWeb(base: String): Boolean {
        return try {
            val (code, _) = httpGet("$base/", 8000)
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }
}

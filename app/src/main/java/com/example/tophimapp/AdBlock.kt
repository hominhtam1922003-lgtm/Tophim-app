package com.example.tophimapp

/**
 * Danh sach chan quang cao dung chung cho WebView tai du lieu va ca
 * trinh phat: bat ky request nao toi cac ten mien/dau hieu nay deu bi cat.
 * Gom ca cac quang cao ca cuoc da quan sat tren tophim.pro
 * (F8BET, QQ88, Hi88, Colatv, F168, SHBET, VSbet...).
 */
object AdBlock {

    private val HOST_MARKERS = listOf(
        // Mang quang cao / tracking pho bien
        "doubleclick", "googlesyndication", "googleadservices", "adservice",
        "ads.", ".ads", "ad-", "-ad.", "advertising", "adnxs", "adskeeper",
        "popads", "popcash", "propellerads", "exoclick", "adsterra", "hilltopads",
        "mgid", "outbrain", "taboola", "criteo", "pubmatic", "rubiconproject",
        "smartadserver", "amazon-adsystem", "adform", "openx", "revcontent",
        "bidvertiser", "trafficjunky", "juicyads", "clickadu", "adcash",
        // Quang cao ca cuoc / pop-under da thay tren trang nguon
        "hi88", "f8bet", "qq88", "shbet", "vsbet", "f168", "colatv",
        "score6.com", "dlvsb.io", "linkroyal", "bet88", "okvip", "jun88",
        "new88", "789bet", "kubet", "w88", "fb88", "vn88", "rikvip"
    )

    /** Cac host duoc phep cham toi tu trinh phat (trang xem + may chu video). */
    private val ALLOWED_PLAYER_HINTS = listOf("playembed", "tophim")

    fun isAd(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        val host = try {
            java.net.URI(lower).host ?: lower
        } catch (e: Exception) {
            lower
        }
        if (ALLOWED_PLAYER_HINTS.any { host.contains(it) }) return false
        return HOST_MARKERS.any { host.contains(it) }
    }
}

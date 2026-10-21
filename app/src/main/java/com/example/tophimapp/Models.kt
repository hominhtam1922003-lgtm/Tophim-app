package com.example.tophimapp

data class Movie(
    val title: String,
    val url: String,           // URL trang chi tiet /phim/{slug}
    val poster: String,
    val sub: String = ""       // dong phu: nam - chat luong - tap moi nhat
)

data class Episode(
    val name: String,          // "Tập 1"
    val url: String            // URL trang xem /xem/{slug}/...
)

data class ServerGroup(
    val name: String,          // "Máy chủ 1", "Máy chủ 2"...
    val episodes: List<Episode>
)

data class MovieDetail(
    val movie: Movie,
    val meta: String,          // nam / thoi luong / the loai / quoc gia... gop 1 chuoi
    val description: String,
    val servers: List<ServerGroup>
) {
    val totalEpisodes: Int get() = servers.maxOfOrNull { it.episodes.size } ?: 0
}

/** Cac nguon phat trich tu trang xem: HLS thang (.m3u8) va khung nhung playembed. */
data class PlayerLinks(
    val hls: String = "",
    val embed: String = ""
)

data class MovieHistoryItem(
    val movieUrl: String,
    val title: String,
    val poster: String,
    val episodeName: String,
    val episodeUrl: String,
    val watchedAt: Long
)

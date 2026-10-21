package com.example.tophimapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Luu local: phim yeu thich + lich su xem (SQLite, khong can dang nhap). */
class DbRepo(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "tophim.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE favorites(" +
                "url TEXT PRIMARY KEY, title TEXT, poster TEXT, " +
                "episode_count INTEGER DEFAULT 0, added_at INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE history(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, movie_url TEXT, title TEXT, poster TEXT, " +
                "episode_name TEXT, episode_url TEXT, watched_at INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS favorites")
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    // ---------- Yeu thich ----------

    fun isFavorite(url: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE url=?", arrayOf(url))
        val r = c.moveToFirst()
        c.close()
        return r
    }

    fun addFavorite(movie: Movie, episodeCount: Int) {
        val v = ContentValues().apply {
            put("url", movie.url)
            put("title", movie.title)
            put("poster", movie.poster)
            put("episode_count", episodeCount)
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("favorites", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateFavoriteEpisodeCount(url: String, episodeCount: Int) {
        val v = ContentValues().apply { put("episode_count", episodeCount) }
        writableDatabase.update("favorites", v, "url=?", arrayOf(url))
    }

    fun removeFavorite(url: String) {
        writableDatabase.delete("favorites", "url=?", arrayOf(url))
    }

    fun favorites(): List<Pair<Movie, Int>> {
        val list = ArrayList<Pair<Movie, Int>>()
        val c = readableDatabase.rawQuery(
            "SELECT url,title,poster,episode_count FROM favorites ORDER BY added_at DESC", null
        )
        while (c.moveToNext()) {
            list.add(
                Movie(c.getString(0), c.getString(1), c.getString(2) ?: "") to c.getInt(3)
            )
        }
        c.close()
        return list
    }

    // ---------- Lich su (gop theo phim: moi phim 1 dong) ----------

    fun addHistory(movie: Movie, episodeName: String, episodeUrl: String) {
        val v = ContentValues().apply {
            put("movie_url", movie.url)
            put("title", movie.title)
            put("poster", movie.poster)
            put("episode_name", episodeName)
            put("episode_url", episodeUrl)
            put("watched_at", System.currentTimeMillis())
        }
        writableDatabase.insert("history", null, v)
    }

    fun historyGrouped(): List<MovieHistoryItem> {
        val list = ArrayList<MovieHistoryItem>()
        val c = readableDatabase.rawQuery(
            "SELECT movie_url, title, poster, episode_name, episode_url, MAX(watched_at) AS w " +
                "FROM history GROUP BY movie_url ORDER BY w DESC LIMIT 100", null
        )
        while (c.moveToNext()) {
            list.add(
                MovieHistoryItem(
                    c.getString(0), c.getString(1), c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getString(4) ?: "", c.getLong(5)
                )
            )
        }
        c.close()
        return list
    }
}

package com.example.tophimapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Dinh ky kiem tra cac phim YEU THICH tren web nguon:
 * neu tong so tap lon hon moc da luu -> gui thong bao tap moi va cap nhat moc.
 * Chay nen bang WorkManager (xem MainActivity).
 */
class NewEpisodeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        RemoteConfig.resolveBlocking(applicationContext)
        val db = DbRepo(applicationContext)
        for ((movie, knownCount) in db.favorites()) {
            try {
                val detail = TophimSource.fetchDetail(movie.url)
                val total = detail.totalEpisodes
                if (total <= 0) continue
                if (knownCount > 0 && total > knownCount) {
                    notifyNewEpisode(movie, total)
                }
                if (total != knownCount) {
                    db.updateFavoriteEpisodeCount(movie.url, total)
                }
            } catch (e: Exception) {
                // Phim loi / bi chan tam thoi: bo qua, lan sau kiem tra lai
                continue
            }
        }
        return Result.success()
    }

    private fun notifyNewEpisode(movie: Movie, total: Int) {
        val ctx = applicationContext
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Tập mới", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Thông báo khi phim yêu thích có tập mới" }
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(ctx, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_URL, movie.url)
            putExtra(DetailActivity.EXTRA_TITLE, movie.title)
            putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, movie.url.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tập mới: ${movie.title}")
            .setContentText("Đã có $total tập. Chạm để xem ngay.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(movie.url.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "tap_moi"
    }
}

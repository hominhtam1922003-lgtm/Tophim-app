package com.example.tophimapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) show(HomeFragment())
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(HomeFragment())
                R.id.nav_single -> show(BrowseFragment.newInstance(BrowseFragment.KIND_SINGLE))
                R.id.nav_series -> show(BrowseFragment.newInstance(BrowseFragment.KIND_SERIES))
                R.id.nav_favorites -> {
                    // Thong bao tap moi gan voi phim yeu thich:
                    // chua co quyen thi xin lai tai day (Android 13+)
                    ensureNotificationPermission()
                    show(FavoritesFragment())
                }
                R.id.nav_history -> show(HistoryFragment())
            }
            true
        }

        ensureNotificationPermission()
        scheduleNewEpisodeChecks()
        // Xac dinh ten mien nguon dang song (chong web doi ten mien)
        RemoteConfig.resolveAsync(applicationContext)
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    /** Kiem tra tap moi cho phim yeu thich moi 3 gio (chi khi co mang). */
    private fun scheduleNewEpisodeChecks() {
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(3, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "new_episode_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

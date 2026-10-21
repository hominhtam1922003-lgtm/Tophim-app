package com.example.tophimapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: List<TextView>
    private var selectedTab: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // App da co toolbar rieng trong layout; tat action bar mac dinh
        // de khoi bi 2 thanh tieu de chong nhau
        supportActionBar?.hide()

        val tabHome = findViewById<TextView>(R.id.tabHome)
        val tabSingle = findViewById<TextView>(R.id.tabSingle)
        val tabSeries = findViewById<TextView>(R.id.tabSeries)
        val tabAdult = findViewById<TextView>(R.id.tabAdult)
        val tabFavorites = findViewById<TextView>(R.id.tabFavorites)
        val tabHistory = findViewById<TextView>(R.id.tabHistory)
        tabs = listOf(tabHome, tabSingle, tabSeries, tabAdult, tabFavorites, tabHistory)

        tabHome.setOnClickListener { select(tabHome); show(HomeFragment()) }
        tabSingle.setOnClickListener {
            select(tabSingle)
            show(BrowseFragment.newInstance(BrowseFragment.KIND_SINGLE))
        }
        tabSeries.setOnClickListener {
            select(tabSeries)
            show(BrowseFragment.newInstance(BrowseFragment.KIND_SERIES))
        }
        tabAdult.setOnClickListener {
            select(tabAdult)
            show(BrowseFragment.newInstance(BrowseFragment.KIND_ADULT))
        }
        tabFavorites.setOnClickListener {
            // Thong bao tap moi gan voi phim yeu thich:
            // chua co quyen thi xin lai tai day (Android 13+)
            ensureNotificationPermission()
            select(tabFavorites)
            show(FavoritesFragment())
        }
        tabHistory.setOnClickListener { select(tabHistory); show(HistoryFragment()) }

        if (savedInstanceState == null) {
            select(tabHome)
            show(HomeFragment())
        }

        ensureNotificationPermission()
        scheduleNewEpisodeChecks()
        // Xac dinh ten mien nguon dang song (chong web doi ten mien)
        RemoteConfig.resolveAsync(applicationContext)
    }

    /** To mau the dang chon (dam + mau chinh), cac the con lai ve xam. */
    private fun select(tab: TextView) {
        selectedTab = tab
        val primary = androidx.core.content.ContextCompat.getColor(this, R.color.tab_selected)
        for (t in tabs) {
            if (t == tab) {
                t.setTextColor(primary)
                t.setTypeface(null, Typeface.BOLD)
            } else {
                t.setTextColor(Color.GRAY)
                t.setTypeface(null, Typeface.NORMAL)
            }
        }
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

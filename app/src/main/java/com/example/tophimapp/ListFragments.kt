package com.example.tophimapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoritesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_simple, container, false)

    override fun onResume() {
        super.onResume()
        // Mo tab Yeu thich cung la luc xin quyen thong bao (de bao tap moi)
        if (activity is MainActivity) (activity as MainActivity).ensureNotificationPermission()
        val v = view ?: return
        val rv = v.findViewById<RecyclerView>(R.id.rvList)
        val tv = v.findViewById<TextView>(R.id.tvEmpty)
        val repo = DbRepo(requireContext())
        val favs = repo.favorites()
        tv.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
        tv.text = "Chưa có phim yêu thích.\nMở một phim và bấm nút Yêu thích."
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = MovieRowAdapter(
            favs.map { it.first },
            favs.associate { it.first.url to "Tổng số tập đã có: ${it.second}" }
        ) { openDetail(it) }
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, TophimSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }
}

class HistoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_simple, container, false)

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        val rv = v.findViewById<RecyclerView>(R.id.rvList)
        val tv = v.findViewById<TextView>(R.id.tvEmpty)
        val repo = DbRepo(requireContext())
        val items = repo.historyGrouped()
        tv.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        tv.text = "Chưa xem phim nào."
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = MovieRowAdapter(
            items.map { Movie(it.title, it.movieUrl, it.poster) },
            items.associate {
                it.movieUrl to "Đang xem: ${it.episodeName} • ${fmt.format(Date(it.watchedAt))}"
            }
        ) { movie -> openDetail(movie) }
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, TophimSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }
}

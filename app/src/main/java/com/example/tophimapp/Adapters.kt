package com.example.tophimapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Luoi poster phim (dung lai layout item_comic_grid: anh + ten + dong phu). */
class MovieGridAdapter(
    private var items: List<Movie>,
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivCover)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvSub: TextView = v.findViewById(R.id.tvLatest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comic_grid, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvTitle.text = m.title
        holder.tvSub.text = m.sub
        ImageLoader.cover(holder.iv, m.poster)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }
}

/** Danh sach dang dong (Yeu thich / Lich su). */
class MovieRowAdapter(
    private var items: List<Movie>,
    private val subs: Map<String, String> = emptyMap(),
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivCoverRow)
        val tvTitle: TextView = v.findViewById(R.id.tvTitleRow)
        val tvSub: TextView = v.findViewById(R.id.tvSubRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comic_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvTitle.text = m.title
        holder.tvSub.text = subs[m.url] ?: m.sub
        ImageLoader.cover(holder.iv, m.poster)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<Movie>, newSubs: Map<String, String> = emptyMap()) {
        items = list
        notifyDataSetChanged()
    }
}

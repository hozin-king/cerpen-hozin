package com.hozinking.cerpen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Daftar cerpen di Home. */
class CerpenAdapter(
    private val items: List<Cerpen>,
    private val onClick: (Cerpen) -> Unit
) : RecyclerView.Adapter<CerpenAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val judul: TextView = v.findViewById(R.id.judul)
        val genre: TextView = v.findViewById(R.id.genre)
        val meta: TextView = v.findViewById(R.id.meta)
        val desc: TextView = v.findViewById(R.id.deskripsi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cerpen, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.judul.text = c.judul
        h.genre.text = c.genre
        h.meta.text = "${c.totalEpisodes} episode · ${c.seasons.size} season"
        h.desc.text = c.deskripsi
        val bmp = if (c.cover.isNotEmpty()) {
            CoverCache.get(h.itemView.context, "covers/${c.cover}", 300)
        } else null
        if (bmp != null) {
            h.cover.setImageBitmap(bmp)
        } else {
            h.cover.setImageResource(R.drawable.cover_placeholder)
        }
        h.itemView.setOnClickListener { onClick(c) }
    }

    override fun getItemCount(): Int = items.size
}

/** Daftar berita teknologi di Home. */
class NewsAdapter(
    private val items: List<NewsArticle>
) : RecyclerView.Adapter<NewsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val summary: TextView = v.findViewById(R.id.summary)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_news, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = items[pos]
        h.title.text = a.title
        h.summary.text = a.summary
        h.meta.text = listOf(a.source, a.date).filter { it.isNotEmpty() }.joinToString(" · ")
        val bmp = if (!a.image.isNullOrEmpty()) {
            CoverCache.get(h.itemView.context, "news/${a.image}", 300)
        } else null
        if (bmp != null) {
            h.thumb.setImageBitmap(bmp)
            h.thumb.visibility = View.VISIBLE
        } else {
            h.thumb.setImageResource(R.drawable.news_placeholder)
            h.thumb.visibility = View.VISIBLE
        }
    }

    override fun getItemCount(): Int = items.size
}

/** Baris riwayat baca / bookmark di Home: "Judul Cerpen — Season X Episode Y: Judul Episode". */
class HistoryAdapter(
    private val items: List<Triple<Cerpen, Int, Episode>>,
    private val onClick: (cerpen: Cerpen, season: Int, episode: Episode) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.history_title)
        val meta: TextView = v.findViewById(R.id.history_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (cerpen, season, ep) = items[pos]
        h.title.text = "${cerpen.judul} — ${ep.judul}"
        h.meta.text = "Season $season · Episode ${ep.no} · ${cerpen.genre}"
        h.itemView.setOnClickListener { onClick(cerpen, season, ep) }
    }

    override fun getItemCount(): Int = items.size
}

/** Daftar episode di halaman detail, dikelompokkan per season. */
class EpisodeAdapter(
    private val cerpen: Cerpen,
    private val onClick: (season: Int, episode: Episode) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EPISODE = 1
    }

    /** Satu baris: header season atau satu episode. */
    private val rows: List<Any> = buildList {
        for (s in cerpen.seasons) {
            add(s)
            addAll(s.episodes)
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.header_label)
    }

    class EpVH(v: View) : RecyclerView.ViewHolder(v) {
        val no: TextView = v.findViewById(R.id.ep_no)
        val judul: TextView = v.findViewById(R.id.ep_judul)
        val status: TextView = v.findViewById(R.id.ep_status)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Season) TYPE_HEADER else TYPE_EPISODE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_section_header, parent, false))
        } else {
            EpVH(inf.inflate(R.layout.item_episode, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (h is HeaderVH && row is Season) {
            h.label.text = "Season ${row.season} · ${row.episodes.size} episode"
        } else if (h is EpVH && row is Episode) {
            // Cari season pemilik episode ini.
            var seasonNo = 1
            for (s in cerpen.seasons) {
                if (s.episodes.contains(row)) { seasonNo = s.season; break }
            }
            h.no.text = row.no.toString()
            val marked = ProgressStore.isBookmarked(h.itemView.context, cerpen.id, seasonNo, row.no)
            h.judul.text = if (marked) "🔖 ${row.judul}" else row.judul
            val done = ProgressStore.isDone(h.itemView.context, cerpen.id, seasonNo, row.no)
            h.status.text = if (done) "Sudah dibaca ✓" else "Belum dibaca"
            h.status.alpha = if (done) 1f else 0.55f
            h.itemView.setOnClickListener { onClick(seasonNo, row) }
        }
    }

    override fun getItemCount(): Int = rows.size
}

package com.hozinking.cerpen

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Baris daftar yang punya tombol Unduh + ikon sudah-diunduh (v1.1.5). */
interface DownloadRow {
    val downloadButton: Button
    val offlineIcon: ImageView
}

/** Daftar cerpen di Home. */
class CerpenAdapter(
    private val items: List<Cerpen>,
    private val onClick: (Cerpen) -> Unit,
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f,
    private val onDownload: (Cerpen, Int) -> Unit = { _, _ -> },
    /** Id cerpen terbaru (badge NEW); null = tidak ada badge. */
    private val newestId: String? = null,
    /** v1.1.31: stats dari stats-batch, kunci "kind|ref"; kosong = angka disembunyikan. */
    private var socialStats: Map<String, SocialApi.Stats> = emptyMap()
) : RecyclerView.Adapter<CerpenAdapter.VH>() {

    /** v1.1.31: terapkan hasil stats-batch tanpa membuat ulang adapter. */
    fun updateSocialStats(m: Map<String, SocialApi.Stats>) {
        socialStats = m
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v), DownloadRow {
        val cover: ImageView = v.findViewById(R.id.cover)
        val judul: TextView = v.findViewById(R.id.judul)
        val genre: TextView = v.findViewById(R.id.genre)
        val meta: TextView = v.findViewById(R.id.meta)
        val desc: TextView = v.findViewById(R.id.deskripsi)
        /** v1.1.31: "Dilihat X · Suka Y" (gone bila tak ada stats). */
        val socialText: TextView = v.findViewById(R.id.social_stats)
        val badgeNew: TextView = v.findViewById(R.id.badge_new)
        /** v1.1.7: badge "Baru" / "Season diperbarui" / "Episode diperbarui". */
        val badgeUpdated: TextView = v.findViewById(R.id.badge_updated)
        override val downloadButton: Button = v.findViewById(R.id.download_button)
        override val offlineIcon: ImageView = v.findViewById(R.id.offline_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cerpen, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.judul.text = c.judul
        h.judul.setTextColor(palette.ink)
        h.judul.textSize = 17f * textScale
        h.genre.text = c.genre
        h.genre.setTextColor(palette.accent)
        h.meta.text = "${c.totalEpisodes} episode · ${c.seasons.size} season"
        h.meta.setTextColor(palette.muted)
        h.desc.text = c.deskripsi
        h.desc.setTextColor(palette.inkSoft)
        h.desc.textSize = 14f * textScale
        // v1.1.31: view + like (gone bila stats-batch belum/gagal — tanpa 0 palsu).
        val st = socialStats[SocialApi.key("cerpen", c.id)]
        h.socialText.visibility = if (st == null) View.GONE else View.VISIBLE
        if (st != null) h.socialText.text =
            "Dilihat ${SocialApi.formatCount(st.views)} · Suka ${SocialApi.formatCount(st.likes)}"
        // v1.1.5: cover dimuat asinkron (lazy) — decode tidak pernah di UI thread.
        if (c.cover.isNotEmpty()) {
            CoverCache.loadInto(h.itemView.context, "covers/${c.cover}", 300, h.cover, R.drawable.cover_placeholder)
        } else {
            CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        }
        // v1.1.7: badge "diperbarui" didahulukan atas badge NEW — satu
        // kartu cukup satu badge. Badge diperbarui hilang begitu pengguna
        // membuka detail item ini (UpdatedStore.clearCerpen).
        val updatedText = UpdatedStore.badgeTextCerpen(h.itemView.context, c)
        if (updatedText != null) {
            h.badgeUpdated.text = updatedText
            h.badgeUpdated.visibility = View.VISIBLE
            h.badgeNew.visibility = View.GONE
        } else {
            h.badgeUpdated.visibility = View.GONE
            // v1.1.26: BARU bila series ini punya episode/season yang
            // first-seen dalam 7 hari terakhir — atau series terbaru.
            val hasNew = NewContentStore.hasNewContent(h.itemView.context, c)
            h.badgeNew.visibility = if (c.id == newestId || hasNew) View.VISIBLE else View.GONE
            if (hasNew) h.badgeNew.text = "BARU"
        }
        // Status unduh: sudah offline = ikon, belum = tombol Unduh.
        val ctx = h.itemView.context
        val offline = DataManager.isCerpenOffline(ctx, c)
        h.offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) h.offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        h.downloadButton.visibility = if (offline) View.GONE else View.VISIBLE
        h.downloadButton.text = "⬇ Unduh"
        h.downloadButton.isEnabled = true
        h.downloadButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onDownload(c, p)
        }
        h.itemView.setOnClickListener { onClick(c) }
    }

    override fun onViewRecycled(h: VH) {
        CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        super.onViewRecycled(h)
    }

    override fun getItemCount(): Int = items.size
}

/** Daftar berita teknologi di Home. */
class NewsAdapter(
    private val items: List<NewsArticle>,
    private val onClick: (NewsArticle) -> Unit = {},
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f,
    private val onDownload: (NewsArticle, Int) -> Unit = { _, _ -> },
    /** v1.1.31: stats dari stats-batch, kunci "kind|ref"; kosong = angka disembunyikan. */
    private var socialStats: Map<String, SocialApi.Stats> = emptyMap()
) : RecyclerView.Adapter<NewsAdapter.VH>() {

    /** v1.1.31: terapkan hasil stats-batch tanpa membuat ulang adapter. */
    fun updateSocialStats(m: Map<String, SocialApi.Stats>) {
        socialStats = m
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v), DownloadRow {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val summary: TextView = v.findViewById(R.id.summary)
        val meta: TextView = v.findViewById(R.id.meta)
        /** v1.1.31: "Dilihat X · Suka Y" (gone bila tak ada stats). */
        val socialText: TextView = v.findViewById(R.id.social_stats)
        val badgeNew: TextView = v.findViewById(R.id.badge_new)
        override val downloadButton: Button = v.findViewById(R.id.download_button)
        override val offlineIcon: ImageView = v.findViewById(R.id.offline_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_news, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = items[pos]
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.title.text = a.title
        h.title.setTextColor(palette.ink)
        h.title.textSize = 15f * textScale
        h.summary.text = a.summary
        h.summary.setTextColor(palette.inkSoft)
        h.summary.textSize = 13f * textScale
        // v1.1.5: tanggal tampil ("9 Nov 2026") + badge NEW (< 24 jam).
        val dateLabel = if (a.date.isNotEmpty()) ItemDates.format(a.date) else ""
        h.meta.text = listOf(a.source, dateLabel).filter { it.isNotEmpty() }.joinToString(" · ")
        h.meta.setTextColor(palette.muted)
        // v1.1.31: view + like (gone bila stats-batch belum/gagal — tanpa 0 palsu).
        val st = socialStats[SocialApi.key("berita", SocialApi.newsRef(a))]
        h.socialText.visibility = if (st == null) View.GONE else View.VISIBLE
        if (st != null) h.socialText.text =
            "Dilihat ${SocialApi.formatCount(st.views)} · Suka ${SocialApi.formatCount(st.likes)}"
        h.badgeNew.visibility = if (ItemDates.isNew(a.addedAt, a.date)) View.VISIBLE else View.GONE
        val firstImage = a.images.firstOrNull() ?: a.image
        // v1.1.29: thumbnail dimuat asinkron (progressive — thumb CMS
        // dulu, full menyusul di latar via CoverCache.loadInto); gambar
        // yang belum ada juga diunduh per item lewat tombol Unduh /
        // buka detail.
        if (!firstImage.isNullOrEmpty()) {
            CoverCache.loadInto(h.itemView.context, "news/$firstImage", 300, h.thumb, R.drawable.news_placeholder)
        } else {
            CoverCache.clear(h.thumb, R.drawable.news_placeholder)
        }
        h.thumb.visibility = View.VISIBLE
        val ctx = h.itemView.context
        val offline = DataManager.isNewsOffline(ctx, a)
        h.offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) h.offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        h.downloadButton.visibility = if (offline) View.GONE else View.VISIBLE
        h.downloadButton.text = "⬇ Unduh"
        h.downloadButton.isEnabled = true
        h.downloadButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onDownload(a, p)
        }
        h.itemView.setOnClickListener { onClick(a) }
    }

    override fun onViewRecycled(h: VH) {
        CoverCache.clear(h.thumb, R.drawable.news_placeholder)
        super.onViewRecycled(h)
    }

    override fun getItemCount(): Int = items.size
}

/** Baris riwayat baca / bookmark di Home: "Judul Cerpen — Season X Episode Y: Judul Episode". */
class HistoryAdapter(
    private val items: List<Triple<Cerpen, Int, Episode>>,
    private val onClick: (cerpen: Cerpen, season: Int, episode: Episode) -> Unit,
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f
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
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.title.text = "${cerpen.judul} — ${ep.judul}"
        h.title.setTextColor(palette.ink)
        h.title.textSize = 15f * textScale
        h.meta.text = "Season $season · Episode ${ep.no} · ${cerpen.genre}"
        h.meta.setTextColor(palette.muted)
        h.itemView.setOnClickListener { onClick(cerpen, season, ep) }
    }

    override fun getItemCount(): Int = items.size
}

/** Daftar episode di halaman detail, dikelompokkan per season. */
class EpisodeAdapter(
    private val cerpen: Cerpen,
    private val onClick: (season: Int, episode: Episode) -> Unit,
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f,
    /** v1.1.30: status kunci per episode (null = tanpa info koin). */
    private val lockOf: ((season: Int, episode: Episode) -> CoinGate.LockState)? = null
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
        /** v1.1.26: chip BARU di samping judul episode baru. */
        val badge: TextView = v.findViewById(R.id.ep_badge)
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
            // v1.1.26: season baru (first-seen <= 7 hari) dapat label.
            val seasonNew = NewContentStore.isSeasonNew(h.itemView.context, cerpen.id, row.season)
            h.label.text = "Season ${row.season} · ${row.episodes.size} episode" +
                if (seasonNew) " · SEASON BARU" else ""
            h.label.setTextColor(palette.accent)
        } else if (h is EpVH && row is Episode) {
            // Cari season pemilik episode ini.
            var seasonNo = 1
            for (s in cerpen.seasons) {
                if (s.episodes.contains(row)) { seasonNo = s.season; break }
            }
            h.no.text = row.no.toString()
            h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
            val marked = ProgressStore.isBookmarked(h.itemView.context, cerpen.id, seasonNo, row.no)
            h.judul.text = if (marked) "🔖 ${row.judul}" else row.judul
            h.judul.setTextColor(palette.ink)
            h.judul.textSize = 16f * textScale
            // v1.1.26: chip BARU bila episode ini first-seen <= 7 hari.
            h.badge.visibility =
                if (NewContentStore.isEpisodeNew(h.itemView.context, cerpen.id, seasonNo, row.no)) View.VISIBLE else View.GONE
            val done = ProgressStore.isDone(h.itemView.context, cerpen.id, seasonNo, row.no)
            // v1.1.30: status kunci dari cache koin (gratis/terbuka = tanpa teks).
            val lockText = lockOf?.invoke(seasonNo, row)?.let { lock ->
                when (lock) {
                    is CoinGate.LockState.Locked -> " · Terkunci ${lock.price} koin"
                    is CoinGate.LockState.Vip -> " · Gratis (VIP)"
                    else -> ""
                }
            } ?: ""
            h.status.text = (if (done) "Sudah dibaca ✓" else "Belum dibaca") + lockText
            h.status.setTextColor(palette.muted)
            h.itemView.setOnClickListener { onClick(seasonNo, row) }
        }
    }

    override fun getItemCount(): Int = rows.size
}

/** Daftar komik di Home (tab Komik) — memakai item cerpen yang sama. */
class KomikAdapter(
    private val items: List<Komik>,
    private val onClick: (Komik) -> Unit,
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f,
    private val onDownload: (Komik, Int) -> Unit = { _, _ -> },
    /** Id komik terbaru (badge NEW); null = tidak ada badge. */
    private val newestId: String? = null,
    /** v1.1.31: stats dari stats-batch, kunci "kind|ref"; kosong = angka disembunyikan. */
    private var socialStats: Map<String, SocialApi.Stats> = emptyMap()
) : RecyclerView.Adapter<KomikAdapter.VH>() {

    /** v1.1.31: terapkan hasil stats-batch tanpa membuat ulang adapter. */
    fun updateSocialStats(m: Map<String, SocialApi.Stats>) {
        socialStats = m
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v), DownloadRow {
        val cover: ImageView = v.findViewById(R.id.cover)
        val judul: TextView = v.findViewById(R.id.judul)
        val genre: TextView = v.findViewById(R.id.genre)
        val meta: TextView = v.findViewById(R.id.meta)
        val desc: TextView = v.findViewById(R.id.deskripsi)
        /** v1.1.31: "Dilihat X · Suka Y" (gone bila tak ada stats). */
        val socialText: TextView = v.findViewById(R.id.social_stats)
        val badgeNew: TextView = v.findViewById(R.id.badge_new)
        /** v1.1.7: badge "Baru" / "Episode diperbarui". */
        val badgeUpdated: TextView = v.findViewById(R.id.badge_updated)
        override val downloadButton: Button = v.findViewById(R.id.download_button)
        override val offlineIcon: ImageView = v.findViewById(R.id.offline_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cerpen, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val k = items[pos]
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.judul.text = k.judul
        h.judul.setTextColor(palette.ink)
        h.judul.textSize = 17f * textScale
        h.genre.text = k.genre
        h.genre.setTextColor(palette.accent)
        val ctx = h.itemView.context
        val (panelsDone, panelsTotal) = DataManager.komikPanelOfflineCount(ctx, k)
        val offline = DataManager.isKomikOffline(ctx, k)
        h.meta.text = if (offline) {
            "${k.totalEpisodes} episode · ${k.totalPanels} panel"
        } else if (panelsTotal > 0 && panelsDone > 0) {
            "${k.totalEpisodes} episode · ${k.totalPanels} panel · $panelsDone/$panelsTotal panel offline"
        } else {
            "${k.totalEpisodes} episode · ${k.totalPanels} panel"
        }
        h.meta.setTextColor(palette.muted)
        h.desc.text = k.deskripsi
        h.desc.setTextColor(palette.inkSoft)
        h.desc.textSize = 14f * textScale
        // v1.1.31: view + like (gone bila stats-batch belum/gagal — tanpa 0 palsu).
        val st = socialStats[SocialApi.key("komik", k.id)]
        h.socialText.visibility = if (st == null) View.GONE else View.VISIBLE
        if (st != null) h.socialText.text =
            "Dilihat ${SocialApi.formatCount(st.views)} · Suka ${SocialApi.formatCount(st.likes)}"
        if (k.cover.isNotEmpty()) {
            CoverCache.loadInto(ctx, "komik/covers/${k.cover}", 300, h.cover, R.drawable.cover_placeholder)
        } else {
            CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        }
        // v1.1.7: badge "diperbarui" didahulukan atas badge NEW.
        val updatedText = UpdatedStore.badgeTextKomik(ctx, k)
        if (updatedText != null) {
            h.badgeUpdated.text = updatedText
            h.badgeUpdated.visibility = View.VISIBLE
            h.badgeNew.visibility = View.GONE
        } else {
            h.badgeUpdated.visibility = View.GONE
            // v1.1.26: BARU bila komik ini punya episode first-seen <= 7 hari.
            val hasNew = NewContentStore.hasNewContent(ctx, k)
            h.badgeNew.visibility = if (k.id == newestId || hasNew) View.VISIBLE else View.GONE
            if (hasNew) h.badgeNew.text = "BARU"
        }
        h.offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) h.offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        h.downloadButton.visibility = if (offline) View.GONE else View.VISIBLE
        h.downloadButton.text = "⬇ Unduh"
        h.downloadButton.isEnabled = true
        h.downloadButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onDownload(k, p)
        }
        h.itemView.setOnClickListener { onClick(k) }
    }

    override fun onViewRecycled(h: VH) {
        CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        super.onViewRecycled(h)
    }

    override fun getItemCount(): Int = items.size
}

/** Satu baris daftar campur tab Cerbung (v1.1.12): cerpen atau cergam. */
sealed interface CerbungItem {
    val id: String
    val date: String
    data class CerpenItem(val cerpen: Cerpen) : CerbungItem {
        override val id: String get() = cerpen.id
        override val date: String get() = cerpen.date
    }
    data class CergamItem(val komik: Komik) : CerbungItem {
        override val id: String get() = komik.id
        override val date: String get() = komik.date
    }
}

/** Daftar campur Cerbung + Cergam di tab Cerbung (v1.1.12) — memakai
 * item cerpen yang sama; baris cergam ber-badge "Cergam" dan membuka
 * detail komik. Isi bind disalin dari CerpenAdapter/KomikAdapter. */
class CerbungAdapter(
    private val items: List<CerbungItem>,
    private val onCerpenClick: (Cerpen) -> Unit,
    private val onKomikClick: (Komik) -> Unit,
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f,
    private val onCerpenDownload: (Cerpen, Int) -> Unit = { _, _ -> },
    private val onKomikDownload: (Komik, Int) -> Unit = { _, _ -> },
    private val newestCerpenId: String? = null,
    private val newestKomikId: String? = null,
    /** v1.1.31: stats dari stats-batch, kunci "kind|ref"; kosong = angka disembunyikan. */
    private var socialStats: Map<String, SocialApi.Stats> = emptyMap()
) : RecyclerView.Adapter<CerbungAdapter.VH>() {

    /** v1.1.31: terapkan hasil stats-batch tanpa membuat ulang adapter. */
    fun updateSocialStats(m: Map<String, SocialApi.Stats>) {
        socialStats = m
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v), DownloadRow {
        val cover: ImageView = v.findViewById(R.id.cover)
        val judul: TextView = v.findViewById(R.id.judul)
        val genre: TextView = v.findViewById(R.id.genre)
        val meta: TextView = v.findViewById(R.id.meta)
        val desc: TextView = v.findViewById(R.id.deskripsi)
        /** v1.1.31: "Dilihat X · Suka Y" (gone bila tak ada stats). */
        val socialText: TextView = v.findViewById(R.id.social_stats)
        val badgeNew: TextView = v.findViewById(R.id.badge_new)
        val badgeUpdated: TextView = v.findViewById(R.id.badge_updated)
        val badgeCergam: TextView = v.findViewById(R.id.badge_cergam)
        override val downloadButton: Button = v.findViewById(R.id.download_button)
        override val offlineIcon: ImageView = v.findViewById(R.id.offline_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cerpen, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        when (val item = items[pos]) {
            is CerbungItem.CerpenItem -> bindCerpen(h, item.cerpen)
            is CerbungItem.CergamItem -> bindCergam(h, item.komik)
        }
    }

    private fun bindCerpen(h: VH, c: Cerpen) {
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.judul.text = c.judul
        h.judul.setTextColor(palette.ink)
        h.judul.textSize = 17f * textScale
        h.genre.text = c.genre
        h.genre.setTextColor(palette.accent)
        h.meta.text = "${c.totalEpisodes} episode · ${c.seasons.size} season"
        h.meta.setTextColor(palette.muted)
        h.desc.text = c.deskripsi
        h.desc.setTextColor(palette.inkSoft)
        h.desc.textSize = 14f * textScale
        // v1.1.31: view + like (gone bila stats-batch belum/gagal — tanpa 0 palsu).
        val stC = socialStats[SocialApi.key("cerpen", c.id)]
        h.socialText.visibility = if (stC == null) View.GONE else View.VISIBLE
        if (stC != null) h.socialText.text =
            "Dilihat ${SocialApi.formatCount(stC.views)} · Suka ${SocialApi.formatCount(stC.likes)}"
        if (c.cover.isNotEmpty()) {
            CoverCache.loadInto(h.itemView.context, "covers/${c.cover}", 300, h.cover, R.drawable.cover_placeholder)
        } else {
            CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        }
        h.badgeCergam.visibility = View.GONE
        val updatedText = UpdatedStore.badgeTextCerpen(h.itemView.context, c)
        if (updatedText != null) {
            h.badgeUpdated.text = updatedText
            h.badgeUpdated.visibility = View.VISIBLE
            h.badgeNew.visibility = View.GONE
        } else {
            h.badgeUpdated.visibility = View.GONE
            // v1.1.26: BARU bila ada episode/season first-seen <= 7 hari.
            val hasNew = NewContentStore.hasNewContent(h.itemView.context, c)
            h.badgeNew.visibility = if (c.id == newestCerpenId || hasNew) View.VISIBLE else View.GONE
            if (hasNew) h.badgeNew.text = "BARU"
        }
        val ctx = h.itemView.context
        val offline = DataManager.isCerpenOffline(ctx, c)
        h.offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) h.offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        h.downloadButton.visibility = if (offline) View.GONE else View.VISIBLE
        h.downloadButton.text = "⬇ Unduh"
        h.downloadButton.isEnabled = true
        h.downloadButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onCerpenDownload(c, p)
        }
        h.itemView.setOnClickListener { onCerpenClick(c) }
    }

    private fun bindCergam(h: VH, k: Komik) {
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.judul.text = k.judul
        h.judul.setTextColor(palette.ink)
        h.judul.textSize = 17f * textScale
        h.genre.text = k.genre
        h.genre.setTextColor(palette.accent)
        val ctx = h.itemView.context
        val (panelsDone, panelsTotal) = DataManager.komikPanelOfflineCount(ctx, k)
        val offline = DataManager.isKomikOffline(ctx, k)
        h.meta.text = if (offline) {
            "${k.totalEpisodes} episode · ${k.totalPanels} panel"
        } else if (panelsTotal > 0 && panelsDone > 0) {
            "${k.totalEpisodes} episode · ${k.totalPanels} panel · $panelsDone/$panelsTotal panel offline"
        } else {
            "${k.totalEpisodes} episode · ${k.totalPanels} panel"
        }
        h.meta.setTextColor(palette.muted)
        h.desc.text = k.deskripsi
        h.desc.setTextColor(palette.inkSoft)
        h.desc.textSize = 14f * textScale
        // v1.1.31: view + like (gone bila stats-batch belum/gagal — tanpa 0 palsu).
        val stK = socialStats[SocialApi.key("komik", k.id)]
        h.socialText.visibility = if (stK == null) View.GONE else View.VISIBLE
        if (stK != null) h.socialText.text =
            "Dilihat ${SocialApi.formatCount(stK.views)} · Suka ${SocialApi.formatCount(stK.likes)}"
        if (k.cover.isNotEmpty()) {
            CoverCache.loadInto(ctx, "komik/covers/${k.cover}", 300, h.cover, R.drawable.cover_placeholder)
        } else {
            CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        }
        h.badgeCergam.visibility = View.VISIBLE
        val updatedText = UpdatedStore.badgeTextKomik(ctx, k)
        if (updatedText != null) {
            h.badgeUpdated.text = updatedText
            h.badgeUpdated.visibility = View.VISIBLE
            h.badgeNew.visibility = View.GONE
        } else {
            h.badgeUpdated.visibility = View.GONE
            // v1.1.26: BARU bila ada episode first-seen <= 7 hari.
            val hasNew = NewContentStore.hasNewContent(ctx, k)
            h.badgeNew.visibility = if (k.id == newestKomikId || hasNew) View.VISIBLE else View.GONE
            if (hasNew) h.badgeNew.text = "BARU"
        }
        h.offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) h.offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        h.downloadButton.visibility = if (offline) View.GONE else View.VISIBLE
        h.downloadButton.text = "⬇ Unduh"
        h.downloadButton.isEnabled = true
        h.downloadButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onKomikDownload(k, p)
        }
        h.itemView.setOnClickListener { onKomikClick(k) }
    }

    override fun onViewRecycled(h: VH) {
        CoverCache.clear(h.cover, R.drawable.cover_placeholder)
        super.onViewRecycled(h)
    }

    override fun getItemCount(): Int = items.size
}

/** Daftar episode di halaman detail komik — memakai item episode yang sama. */
class KomikEpisodeAdapter(
    private val komik: Komik,
    private val onClick: (KomikEpisode) -> Unit,
    private val palette: ThemePalette = ThemeHelper.palette(ProgressStore.THEME_DAY),
    private val textScale: Float = 1f,
    private val onDownload: (KomikEpisode, Int) -> Unit = { _, _ -> },
    /** v1.1.30: status kunci per episode (null = tanpa info koin). */
    private val lockOf: ((episodeNo: Int) -> CoinGate.LockState)? = null
) : RecyclerView.Adapter<KomikEpisodeAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v), DownloadRow {
        val no: TextView = v.findViewById(R.id.ep_no)
        val judul: TextView = v.findViewById(R.id.ep_judul)
        val status: TextView = v.findViewById(R.id.ep_status)
        /** v1.1.26: chip BARU di samping judul episode baru. */
        val badge: TextView = v.findViewById(R.id.ep_badge)
        override val downloadButton: Button = v.findViewById(R.id.download_button)
        override val offlineIcon: ImageView = v.findViewById(R.id.offline_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = komik.episodes[pos]
        h.itemView.background = ThemeHelper.cardBackground(h.itemView.context, palette)
        h.no.text = e.no.toString()
        h.judul.text = e.judul
        h.judul.setTextColor(palette.ink)
        h.judul.textSize = 16f * textScale
        // v1.1.26: chip BARU bila episode ini first-seen <= 7 hari.
        h.badge.visibility =
            if (NewContentStore.isKomikEpisodeNew(h.itemView.context, komik.id, e.no)) View.VISIBLE else View.GONE
        val ctx = h.itemView.context
        val (done, total) = DataManager.komikEpisodePanelOfflineCount(ctx, e)
        val offline = DataManager.isKomikEpisodeOffline(ctx, e)
        // v1.1.30: status kunci dari cache koin (gratis/terbuka = tanpa teks).
        val lockText = lockOf?.invoke(e.no)?.let { lock ->
            when (lock) {
                is CoinGate.LockState.Locked -> " · Terkunci ${lock.price} koin"
                is CoinGate.LockState.Vip -> " · Gratis (VIP)"
                else -> ""
            }
        } ?: ""
        h.status.text = if (total == 0) {
            "${e.panels.size} panel$lockText"
        } else if (offline) {
            "${e.panels.size} panel · Tersimpan offline$lockText"
        } else {
            "${e.panels.size} panel · $done/$total offline$lockText"
        }
        h.status.setTextColor(palette.muted)
        h.offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) h.offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        h.downloadButton.visibility = if (offline) View.GONE else View.VISIBLE
        h.downloadButton.text = "⬇ Unduh episode ini"
        h.downloadButton.isEnabled = true
        h.downloadButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onDownload(e, p)
        }
        h.itemView.setOnClickListener { onClick(e) }
    }

    override fun getItemCount(): Int = komik.episodes.size
}

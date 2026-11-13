package com.hozinking.cerpen

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Halaman detail komik: cover + info + daftar episode (pola DetailActivity). */
class KomikDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KOMIK_ID = "komik_id"
    }

    private lateinit var root: View
    private lateinit var cover: ImageView
    private lateinit var judul: TextView
    private lateinit var genre: TextView
    private lateinit var meta: TextView
    private lateinit var deskripsi: TextView
    private lateinit var settingsButton: Button
    private lateinit var episodeList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var shareButton: Button
    private lateinit var continueButton: Button
    private lateinit var offlineIcon: ImageView
    private lateinit var offlineLabel: TextView

    private var komik: Komik? = null
    private var downloading = false
    private val downloadingEpisodes = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        root = findViewById(R.id.detail_root)
        cover = findViewById(R.id.detail_cover)
        judul = findViewById(R.id.detail_judul)
        genre = findViewById(R.id.detail_genre)
        meta = findViewById(R.id.detail_meta)
        deskripsi = findViewById(R.id.detail_deskripsi)
        settingsButton = findViewById(R.id.detail_settings_button)
        episodeList = findViewById(R.id.episode_list)
        loading = findViewById(R.id.loading)
        downloadButton = findViewById(R.id.detail_download_button)
        shareButton = findViewById(R.id.detail_share_button)
        continueButton = findViewById(R.id.detail_continue_button)
        offlineIcon = findViewById(R.id.detail_offline_icon)
        offlineLabel = findViewById(R.id.detail_offline_label)

        episodeList.layoutManager = LinearLayoutManager(this)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        applyThemeAndFont()

        val id = intent.getStringExtra(EXTRA_KOMIK_ID) ?: run { finish(); return }
        // v1.1.7: membuka detail = sudah melihat pembaruan — badge hilang.
        UpdatedStore.clearKomik(this, id)
        loading.visibility = View.VISIBLE
        Thread {
            val list = DataManager.loadKomik(this)
            val found = list.firstOrNull { it.id == id }
            runOnUiThread {
                loading.visibility = View.GONE
                if (found == null) {
                    finish()
                    return@runOnUiThread
                }
                komik = found
                bind(found)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        applyThemeAndFont()
        komik?.let {
            episodeList.adapter = episodeAdapter(it)
            refreshDownloadState()
            refreshContinueButton()
        }
        // v1.1.30: validasi ulang unlock dari server tiap kembali (bila
        // online) — tampilan kunci di-refresh dari /me, bukan cache lama.
        CoinUi.refreshMeAsync(this) {
            komik?.let {
                episodeList.adapter = episodeAdapter(it)
                refreshDownloadState()
                refreshContinueButton()
            }
        }
    }

    /** Terapkan tema + ukuran font global ke layar ini. */
    private fun applyThemeAndFont() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        root.setBackgroundColor(palette.bg)
        judul.setTextColor(palette.titleInk)
        genre.setTextColor(palette.accent)
        meta.setTextColor(palette.muted)
        deskripsi.setTextColor(palette.inkSoft)
        deskripsi.textSize = 15f * scale
    }

    private fun bind(k: Komik) {
        supportActionBar?.title = k.judul
        judul.text = k.judul
        genre.text = k.genre
        meta.text = "${k.totalEpisodes} episode · ${k.totalPanels} panel"
        deskripsi.text = k.deskripsi
        // v1.1.5: cover dimuat asinkron (decode tidak di UI thread).
        if (k.cover.isNotEmpty()) {
            CoverCache.loadInto(this, "komik/covers/${k.cover}", 600, cover, R.drawable.cover_placeholder)
        } else {
            CoverCache.clear(cover, R.drawable.cover_placeholder)
        }
        episodeList.adapter = episodeAdapter(k)
        refreshDownloadState()
        refreshContinueButton()
        downloadButton.setOnClickListener { downloadWholeKomik() }
        // v1.1.20: bagikan tautan ke komik ini (detail komik).
        shareButton.visibility = View.VISIBLE
        shareButton.setOnClickListener {
            ShareLink.shareContent(this, "komik", k.id)
        }
    }

    /** v1.1.13: tombol Lanjutkan bila ada progres baca komik ini
     *  (ProgressStore.getLastKomik). Panel dikunci ke jumlah panel
     *  episode saat ini supaya aman bila konten berubah. */
    private fun refreshContinueButton() {
        val k = komik ?: return
        val last = ProgressStore.getLastKomik(this, k.id)
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val ep = last?.let { k.episode(it.episode) }
        if (last == null || ep == null) {
            continueButton.visibility = View.GONE
            return
        }
        val panel = last.panel.coerceIn(0, (ep.panels.size - 1).coerceAtLeast(0))
        continueButton.text = "▶ Lanjutkan · Episode ${last.episode} · Panel ${panel + 1}"
        continueButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        continueButton.setTextColor(palette.onAccent)
        continueButton.visibility = View.VISIBLE
        continueButton.setOnClickListener { openReader(k.id, last.episode, panel) }
    }

    /** v1.1.30: adapter episode + status kunci dari cache koin. */
    private fun episodeAdapter(k: Komik): KomikEpisodeAdapter {
        val state = CoinStore.snapshot(this)
        val now = System.currentTimeMillis()
        val lastKomik = ProgressStore.getLastKomik(this, k.id)?.episode
        return KomikEpisodeAdapter(
            k, { ep -> openReader(k.id, ep.no) },
            ThemeHelper.palette(ProgressStore.getTheme(this)), ThemeHelper.fontScale(this),
            onDownload = { ep, pos -> downloadEpisode(k, ep, pos) },
            lockOf = { no ->
                CoinGate.lockState(
                    state, "komik", k.id, 1, no,
                    CoinStore.isKomikReadLocally(this, k.id, no) || lastKomik == no, now
                )
            }
        )
    }

    /** v1.1.5: tombol Unduh seluruh komik vs ikon sudah-offline. */
    private fun refreshDownloadState() {
        val k = komik ?: return
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val offline = DataManager.isKomikOffline(this, k)
        downloadButton.visibility = if (offline || downloading) View.GONE else View.VISIBLE
        if (!downloading) downloadButton.text = "⬇ Unduh untuk Offline"
        offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        offlineLabel.visibility = if (offline) View.VISIBLE else View.GONE
    }

    private fun downloadWholeKomik() {
        val k = komik ?: return
        if (downloading) return
        downloading = true
        downloadButton.visibility = View.VISIBLE
        downloadButton.isEnabled = false
        downloadButton.text = "Mengunduh…"
        DataManager.downloadKomik(this, k, onProgress = { done, total, _ ->
            downloadButton.text = "Mengunduh $done/$total…"
        }) { result ->
            downloading = false
            downloadButton.isEnabled = true
            refreshDownloadState()
            episodeList.adapter = episodeAdapter(k)
            Toast.makeText(
                this,
                if (result.failed == 0) "Komik tersimpan offline (${result.downloaded} panel baru)"
                else "${result.downloaded} panel diunduh, ${result.failed} gagal — coba lagi saat sinyal bagus",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** v1.1.5: unduh satu episode dari baris daftar episode. */
    private fun downloadEpisode(k: Komik, episode: KomikEpisode, pos: Int) {
        if (!downloadingEpisodes.add(episode.no)) return
        DataManager.downloadKomikEpisode(this, k, episode, onProgress = { done, total, _ ->
            val vh = episodeList.findViewHolderForAdapterPosition(pos) as? DownloadRow ?: return@downloadKomikEpisode
            vh.downloadButton.text = "Mengunduh $done/$total…"
            vh.downloadButton.isEnabled = false
        }) { result ->
            downloadingEpisodes.remove(episode.no)
            episodeList.adapter?.notifyItemChanged(pos)
            refreshDownloadState()
            Toast.makeText(
                this,
                if (result.failed == 0) "Episode tersimpan offline"
                else "Gagal mengunduh — periksa internet lalu coba lagi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** v1.1.30: buka episode lewat gerbang koin (gratis/terbuka/VIP/beli). */
    private fun openReader(komikId: String, episode: Int, panel: Int = 0) {
        val locallyRead = CoinStore.isKomikReadLocally(this, komikId, episode) ||
            ProgressStore.getLastKomik(this, komikId)?.episode == episode
        CoinUi.openEpisode(this, "komik", komikId, 1, episode, locallyRead) {
            val i = Intent(this, KomikReaderActivity::class.java)
            i.putExtra(KomikReaderActivity.EXTRA_KOMIK_ID, komikId)
            i.putExtra(KomikReaderActivity.EXTRA_EPISODE, episode)
            i.putExtra(KomikReaderActivity.EXTRA_PANEL, panel)
            startActivity(i)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

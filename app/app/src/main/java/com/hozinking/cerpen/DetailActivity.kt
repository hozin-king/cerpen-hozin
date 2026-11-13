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

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CERPEN_ID = "cerpen_id"
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

    private var cerpen: Cerpen? = null
    private var downloading = false

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

        val id = intent.getStringExtra(EXTRA_CERPEN_ID) ?: run { finish(); return }
        // v1.1.7: membuka detail = sudah melihat pembaruan — badge hilang.
        UpdatedStore.clearCerpen(this, id)
        loading.visibility = View.VISIBLE
        Thread {
            val list = DataManager.loadCerpen(this)
            val found = list.firstOrNull { it.id == id }
            runOnUiThread {
                loading.visibility = View.GONE
                if (found == null) {
                    finish()
                    return@runOnUiThread
                }
                cerpen = found
                bind(found)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        applyThemeAndFont()
        // Refresh status "sudah dibaca" tiap kembali dari Reader.
        cerpen?.let {
            val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
            val scale = ThemeHelper.fontScale(this)
            episodeList.adapter = episodeAdapter(it)
            refreshContinueButton()
        }
        // v1.1.30: validasi ulang unlock dari server tiap kembali (bila
        // online) — tampilan kunci di-refresh dari /me, bukan cache lama.
        CoinUi.refreshMeAsync(this) {
            cerpen?.let {
                episodeList.adapter = episodeAdapter(it)
                refreshContinueButton()
            }
        }
    }

    /** v1.1.30: adapter episode + status kunci dari cache koin. */
    private fun episodeAdapter(c: Cerpen): EpisodeAdapter {
        val state = CoinStore.snapshot(this)
        val now = System.currentTimeMillis()
        val history = ProgressStore.getHistory(this)
        return EpisodeAdapter(
            c, { season, ep -> openReader(c.id, season, ep.no) },
            ThemeHelper.palette(ProgressStore.getTheme(this)), ThemeHelper.fontScale(this),
            lockOf = { season, ep ->
                CoinGate.lockState(
                    state, "cerpen", c.id, season, ep.no,
                    isReadLocally(c.id, season, ep.no, history), now
                )
            }
        )
    }

    /** Episode sudah dibaca lokal sebelum sistem koin = sudah dimiliki (§3). */
    private fun isReadLocally(
        cerpenId: String, season: Int, no: Int,
        history: List<ProgressStore.HistoryEntry>
    ): Boolean {
        if (ProgressStore.isDone(this, cerpenId, season, no)) return true
        return history.any { it.cerpenId == cerpenId && it.season == season && it.episode == no }
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

    private fun bind(c: Cerpen) {
        supportActionBar?.title = c.judul
        judul.text = c.judul
        genre.text = c.genre
        meta.text = "${c.totalEpisodes} episode · ${c.seasons.size} season"
        deskripsi.text = c.deskripsi
        // v1.1.5: cover dimuat asinkron (decode tidak di UI thread).
        if (c.cover.isNotEmpty()) {
            CoverCache.loadInto(this, "covers/${c.cover}", 600, cover, R.drawable.cover_placeholder)
        } else {
            CoverCache.clear(cover, R.drawable.cover_placeholder)
        }
        episodeList.adapter = episodeAdapter(c)
        refreshDownloadState()
        refreshContinueButton()
        downloadButton.setOnClickListener { downloadCover() }
        // v1.1.20: bagikan tautan ke cerita ini (detail series).
        shareButton.visibility = View.VISIBLE
        shareButton.setOnClickListener {
            ShareLink.shareContent(this, "cerpen", c.id)
        }
    }

    /** v1.1.13: tombol Lanjutkan bila progres terakhir adalah cerita ini.
     *  Pakai data yang sama dengan kartu "Lanjutkan Membaca" di tab Cerbung
     *  (ProgressStore.getLast); Reader sudah memulihkan posisi scroll
     *  episode itu sendiri, jadi cukup buka episode-nya. */
    private fun refreshContinueButton() {
        val c = cerpen ?: return
        val last = ProgressStore.getLast(this)
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val ep = last?.takeIf { it.cerpenId == c.id }?.let { c.episode(it.season, it.episode) }
        if (last == null || ep == null) {
            continueButton.visibility = View.GONE
            return
        }
        val seasonPart = if (last.season > 1) "Season ${last.season} · " else ""
        val titlePart = if (ep.judul.isNotBlank()) ": ${ep.judul}" else ""
        continueButton.text = "▶ Lanjutkan · ${seasonPart}Episode ${last.episode}$titlePart"
        continueButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        continueButton.setTextColor(palette.onAccent)
        continueButton.visibility = View.VISIBLE
        continueButton.setOnClickListener { openReader(c.id, last.season, last.episode) }
    }

    /** v1.1.5: tombol Unduh cover vs ikon sudah-offline. */
    private fun refreshDownloadState() {
        val c = cerpen ?: return
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val offline = DataManager.isCerpenOffline(this, c)
        downloadButton.visibility = if (offline || downloading) View.GONE else View.VISIBLE
        if (!downloading) downloadButton.text = "⬇ Unduh untuk Offline"
        offlineIcon.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) offlineIcon.imageTintList = ColorStateList.valueOf(palette.accent)
        // v1.1.30.1: label offline ikut palet (dulu @color/muted statis).
        offlineLabel.setTextColor(palette.muted)
        offlineLabel.visibility = if (offline) View.VISIBLE else View.GONE
    }

    private fun downloadCover() {
        val c = cerpen ?: return
        if (downloading) return
        downloading = true
        downloadButton.visibility = View.VISIBLE
        downloadButton.isEnabled = false
        downloadButton.text = "Mengunduh…"
        DataManager.downloadCerpen(this, c, onProgress = { done, total, _ ->
            downloadButton.text = "Mengunduh $done/$total…"
        }) { result ->
            downloading = false
            downloadButton.isEnabled = true
            refreshDownloadState()
            Toast.makeText(
                this,
                if (result.failed == 0) "Cover tersimpan offline" else "Gagal mengunduh — periksa internet lalu coba lagi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** v1.1.30: buka episode lewat gerbang koin (gratis/terbuka/VIP/beli). */
    private fun openReader(cerpenId: String, season: Int, episode: Int) {
        val locallyRead = ProgressStore.isDone(this, cerpenId, season, episode) ||
            ProgressStore.getHistory(this).any {
                it.cerpenId == cerpenId && it.season == season && it.episode == episode
            }
        CoinUi.openEpisode(this, "cerpen", cerpenId, season, episode, locallyRead) {
            val i = Intent(this, ReaderActivity::class.java)
            i.putExtra(ReaderActivity.EXTRA_CERPEN_ID, cerpenId)
            i.putExtra(ReaderActivity.EXTRA_SEASON, season)
            i.putExtra(ReaderActivity.EXTRA_EPISODE, episode)
            startActivity(i)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

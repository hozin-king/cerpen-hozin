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
            episodeList.adapter = EpisodeAdapter(it, { season, ep -> openReader(it.id, season, ep.no) }, palette, scale)
            refreshContinueButton()
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
        episodeList.adapter = EpisodeAdapter(c, { season, ep -> openReader(c.id, season, ep.no) }, ThemeHelper.palette(ProgressStore.getTheme(this)), ThemeHelper.fontScale(this))
        refreshDownloadState()
        refreshContinueButton()
        downloadButton.setOnClickListener { downloadCover() }
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

    private fun openReader(cerpenId: String, season: Int, episode: Int) {
        val i = Intent(this, ReaderActivity::class.java)
        i.putExtra(ReaderActivity.EXTRA_CERPEN_ID, cerpenId)
        i.putExtra(ReaderActivity.EXTRA_SEASON, season)
        i.putExtra(ReaderActivity.EXTRA_EPISODE, episode)
        startActivity(i)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

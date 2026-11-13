package com.hozinking.cerpen

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Pembaca komik: panel tampil vertikal berurutan — gambar lalu teks
 * dialog di bawahnya. Gambar didekripsi in-memory di thread latar
 * (DataManager.decryptImageBytes via CoverCache), tampilan dibangun
 * di UI thread setelah semua panel siap (5 panel per episode).
 */
class KomikReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KOMIK_ID = "komik_id"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_PANEL = "panel"
    }

    private lateinit var root: View
    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var loading: ProgressBar

    private var komikId: String = ""
    private var episodeNo: Int = 1
    private var startPanel: Int = 0
    private var currentKomik: Komik? = null
    private var currentEpisode: KomikEpisode? = null
    private var downloading = false
    private var downloadButton: Button? = null
    private val panelViews = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_komik_reader)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        root = findViewById(R.id.komik_reader_root)
        scrollView = findViewById(R.id.komik_reader_scroll)
        container = findViewById(R.id.komik_reader_container)
        titleText = findViewById(R.id.komik_reader_title)
        metaText = findViewById(R.id.komik_reader_meta)
        loading = findViewById(R.id.loading)

        val komikIdExtra = intent.getStringExtra(EXTRA_KOMIK_ID) ?: run { finish(); return }
        val episodeNoExtra = intent.getIntExtra(EXTRA_EPISODE, 1)
        komikId = komikIdExtra
        episodeNo = episodeNoExtra
        startPanel = intent.getIntExtra(EXTRA_PANEL, 0)

        applyTheme()
        loadEpisode()
    }

    /** Muat episode (dekripsi panel di thread latar) lalu bind.
     *  v1.1.25: panel dimuat sebagai Drawable supaya panel GIF ikut
     *  beranimasi (AnimatedImageDrawable); panel statis tetap Bitmap. */
    private fun loadEpisode() {
        loading.visibility = View.VISIBLE
        Thread {
            val komik = DataManager.loadKomik(this).firstOrNull { it.id == komikId }
            val episode = komik?.episode(episodeNo)
            val drawables: List<Drawable?> = episode?.panels?.map { panel ->
                if (panel.image.isNotEmpty()) {
                    CoverCache.getDrawable(this, "komik/panels/${panel.image}", 600)
                } else null
            } ?: emptyList()
            runOnUiThread {
                loading.visibility = View.GONE
                if (komik == null || episode == null) {
                    finish()
                    return@runOnUiThread
                }
                bind(komik, episode, drawables)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    /** v1.1.13: simpan episode + panel paling atas yang terlihat saat
     *  keluar reader (dipakai tombol Lanjutkan di halaman detail). */
    private fun saveProgress() {
        currentEpisode ?: return
        if (panelViews.isEmpty()) return
        val y = scrollView.scrollY
        var idx = 0
        for (i in panelViews.indices) {
            if (panelViews[i].top <= y) idx = i
        }
        ProgressStore.saveLastKomik(this, komikId, episodeNo, idx)
    }

    private fun bind(komik: Komik, episode: KomikEpisode, drawables: List<Drawable?>) {
        currentKomik = komik
        currentEpisode = episode
        supportActionBar?.title = komik.judul
        titleText.text = episode.judul
        metaText.text = "${komik.judul} · Episode ${episode.no} · ${episode.panels.size} panel"
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        val d = resources.displayMetrics.density
        container.removeAllViews()
        panelViews.clear()
        downloadButton = null
        // v1.1.5: bila ada panel yang belum terunduh, tampilkan tombol
        // "Unduh episode ini" di atas panel (bukan pesan jalan buntu).
        val missing = drawables.count { it == null }
        if (missing > 0) {
            val (done, total) = DataManager.komikEpisodePanelOfflineCount(this, episode)
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * d).toInt()
                }
                text = "⬇ Unduh episode ini ($done/$total panel)"
                setTextColor(palette.onAccent)
                setOnClickListener { downloadEpisode() }
            }
            downloadButton = btn
            container.addView(btn)
        }
        for ((index, panel) in episode.panels.withIndex()) {
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Panel ${index + 1}"
            }
            panelViews.add(imageView)
            val d = drawables.getOrNull(index)
            if (d != null) {
                imageView.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.start()
            } else {
                imageView.setImageResource(R.drawable.cover_placeholder)
                // Panel belum terunduh — tombol "Unduh episode ini" sudah
                // ada di atas; di sini cukup penanda singkat.
                val hintView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * d).toInt()
                    }
                    text = "Panel belum terunduh — ketuk tombol unduh di atas."
                    setTextColor(palette.muted)
                    textSize = 13f * scale
                }
                container.addView(imageView)
                container.addView(hintView)
                // Lanjut ke teks dialog di bawah (teks selalu ada di JSON).
                val teksView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * d).toInt()
                        bottomMargin = (20 * d).toInt()
                    }
                    text = panel.teks
                    setTextColor(palette.inkSoft)
                    textSize = 15f * scale
                    setLineSpacing(0f, 1.4f)
                }
                container.addView(teksView)
                continue
            }
            container.addView(imageView)
            val teksView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * d).toInt()
                    bottomMargin = (20 * d).toInt()
                }
                text = panel.teks
                setTextColor(palette.inkSoft)
                textSize = 15f * scale
                setLineSpacing(0f, 1.4f)
            }
            container.addView(teksView)
        }
        // v1.1.13: buka di panel terakhir bila diminta (tombol Lanjutkan);
        // bila tidak, mulai dari atas seperti biasa.
        val target = startPanel.coerceIn(0, (episode.panels.size - 1).coerceAtLeast(0))
        if (target > 0) {
            scrollView.post { scrollView.scrollTo(0, panelViews.getOrNull(target)?.top ?: 0) }
        } else {
            scrollView.scrollTo(0, 0)
        }
    }

    /** v1.1.5: unduh episode ini, lalu muat ulang panel. */
    private fun downloadEpisode() {
        val komik = currentKomik ?: return
        val episode = currentEpisode ?: return
        if (downloading) return
        downloading = true
        val btn = downloadButton
        btn?.isEnabled = false
        btn?.text = "Mengunduh…"
        DataManager.downloadKomikEpisode(this, komik, episode, onProgress = { done, total, _ ->
            btn?.text = "Mengunduh $done/$total…"
        }) { result ->
            downloading = false
            Toast.makeText(
                this,
                if (result.failed == 0) "Episode tersimpan offline"
                else "${result.downloaded} panel diunduh, ${result.failed} gagal — coba lagi saat sinyal bagus",
                Toast.LENGTH_LONG
            ).show()
            loadEpisode()
        }
    }

    private fun applyTheme() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        root.setBackgroundColor(palette.bg)
        titleText.setTextColor(palette.titleInk)
        metaText.setTextColor(palette.muted)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

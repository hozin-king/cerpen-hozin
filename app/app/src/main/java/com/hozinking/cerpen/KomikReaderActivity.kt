package com.hozinking.cerpen

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
    }

    private lateinit var root: View
    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var loading: ProgressBar

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

        val komikId = intent.getStringExtra(EXTRA_KOMIK_ID) ?: run { finish(); return }
        val episodeNo = intent.getIntExtra(EXTRA_EPISODE, 1)

        applyTheme()

        loading.visibility = View.VISIBLE
        Thread {
            val komik = DataManager.loadKomik(this).firstOrNull { it.id == komikId }
            val episode = komik?.episode(episodeNo)
            val bitmaps: List<Bitmap?> = episode?.panels?.map { panel ->
                if (panel.image.isNotEmpty()) {
                    CoverCache.get(this, "komik/panels/${panel.image}", 600)
                } else null
            } ?: emptyList()
            runOnUiThread {
                loading.visibility = View.GONE
                if (komik == null || episode == null) {
                    finish()
                    return@runOnUiThread
                }
                bind(komik, episode, bitmaps)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun bind(komik: Komik, episode: KomikEpisode, bitmaps: List<Bitmap?>) {
        supportActionBar?.title = komik.judul
        titleText.text = episode.judul
        metaText.text = "${komik.judul} · Episode ${episode.no} · ${episode.panels.size} panel"
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        val d = resources.displayMetrics.density
        container.removeAllViews()
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
            val bmp = bitmaps.getOrNull(index)
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
            } else {
                imageView.setImageResource(R.drawable.cover_placeholder)
                // Gambar komik on-demand (tidak di-bundle APK): beri tahu
                // pengguna cara mengunduhnya, jangan tampilkan placeholder diam.
                val hintView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * d).toInt()
                    }
                    text = "Panel belum terunduh — kembali lalu tarik ke bawah di tab Komik untuk mengunduh."
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
        scrollView.scrollTo(0, 0)
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

package com.hozinking.cerpen

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Pembaca artikel berita lengkap (v1.1): isi penuh tanpa pemotongan.
 *
 * v1.1.5: galeri header DIHAPUS — gambar tampil INLINE di dalam alur
 * teks (disebar merata antar paragraf, lihat NewsBodyLayout), karena
 * data berita TIDAK menyimpan posisi gambar di artikel asli. Gambar
 * dimuat asinkron (decode tidak di UI thread). Tombol Unduh mengunduh
 * gambar artikel ini (dari bundel repo atau URL asli yang dienkripsi
 * lokal). Membuka detail otomatis mengunduh gambar yang belum ada
 * supaya gambar langsung tampil di alur teks.
 */
class NewsDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "news_title"
        const val EXTRA_SOURCE = "news_source"
        const val EXTRA_DATE = "news_date"
        const val EXTRA_SUMMARY = "news_summary"
        const val EXTRA_DETAILS = "news_details"
        const val EXTRA_IMAGES = "news_images"
        const val EXTRA_IMAGE_URLS = "news_image_urls"
        const val EXTRA_ADDED_AT = "news_added_at"
    }

    private lateinit var root: View
    private lateinit var bodyContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var downloadButton: Button
    private lateinit var shareButton: Button

    private var article: NewsArticle? = null
    private var downloading = false
    private var lastTheme = -1
    // v1.1.18: animator denyut kata berwarna, satu per blok teks.
    private val pulseAnimators = mutableListOf<ValueAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        root = findViewById(R.id.news_detail_root)
        bodyContainer = findViewById(R.id.news_body_container)
        titleText = findViewById(R.id.news_title)
        metaText = findViewById(R.id.news_meta)
        downloadButton = findViewById(R.id.news_download_button)
        shareButton = findViewById(R.id.news_share_button)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""
        val date = intent.getStringExtra(EXTRA_DATE) ?: ""
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        val details = intent.getStringExtra(EXTRA_DETAILS) ?: ""
        val images = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: arrayListOf()
        val imageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS) ?: arrayListOf()
        val addedAt = intent.getStringExtra(EXTRA_ADDED_AT) ?: ""
        article = NewsArticle(
            title = title, summary = summary, source = source, date = date,
            image = images.firstOrNull(), images = images, details = details,
            imageUrls = imageUrls, addedAt = addedAt
        )

        supportActionBar?.title = if (title.length > 28) title.take(28) + "…" else title
        titleText.text = title
        val dateLabel = if (date.isNotEmpty()) ItemDates.format(date) else ""
        metaText.text = listOf(source, dateLabel).filter { it.isNotEmpty() }.joinToString(" · ")

        downloadButton.setOnClickListener { startDownload() }
        // v1.1.20: bagikan tautan ke artikel ini (content_id = judul —
        // NewsArticle tidak punya id; judul juga kunci unduh offline-nya).
        shareButton.setOnClickListener {
            val a = article ?: return@setOnClickListener
            ShareLink.shareContent(this, "berita", a.title)
        }
        applyThemeAndFont()
        lastTheme = ProgressStore.getTheme(this)
        // Teks dulu (placeholder untuk gambar), gambar menyusul asinkron.
        buildBody(null)
        loadImages()
        // Unduh otomatis gambar yang belum ada — artikel dibuka untuk dibaca.
        refreshDownloadButton()
        if (article?.let { !DataManager.isNewsOffline(this, it) } == true) {
            startDownload()
        }
    }

    override fun onResume() {
        super.onResume()
        val theme = ProgressStore.getTheme(this)
        applyThemeAndFont()
        // Rebuild isi hanya bila tema berubah (warna teks ikut palet).
        if (theme != lastTheme) {
            lastTheme = theme
            buildBody(null)
            loadImages()
        }
        // v1.1.18: nyalakan lagi denyut yang dimatikan di onPause.
        // (Bila buildBody baru jalan di atas, animator sudah ada — lewati.)
        if (pulseAnimators.isEmpty()) restartPulse()
        refreshDownloadButton()
    }

    override fun onPause() {
        super.onPause()
        // v1.1.18: matikan denyut saat layar tidak terlihat (hemat baterai).
        for (anim in pulseAnimators) PulseText.stop(anim)
        pulseAnimators.clear()
    }

    // v1.1.18: nyalakan denyut di semua blok teks yang sedang tampil.
    private fun restartPulse() {
        for (i in 0 until bodyContainer.childCount) {
            val child = bodyContainer.getChildAt(i)
            if (child is TextView) PulseText.start(child)?.let { pulseAnimators += it }
        }
    }

    /**
     * Muat bitmap gambar artikel di thread latar, lalu bangun ulang isi.
     * v1.1.29 progressive: tahap 1 tampilkan lokal / thumbnail CMS,
     * tahap 2 unduh full di latar (disimpan .enc lokal) lalu bangun
     * ulang bila ada gambar penuh yang baru tiba. Tidak pernah tampil
     * gambar rusak/setengah — tiap bitmap hanya dipakai setelah byte
     * lengkap + dekripsi GCM lolos.
     */
    private fun loadImages() {
        val a = article ?: return
        if (a.images.isEmpty()) return
        Thread {
            val first: List<Bitmap?> = a.images.map { path ->
                if (path.isEmpty()) null
                else CoverCache.get(this, "news/$path", 800)
                    ?: DataManager.fetchCmsImageBytes(this, "news/$path", true)
                        ?.let { CoverCache.decodeBytes(it, 800) }
            }
            runOnUiThread { buildBody(first) }
            val full: List<Bitmap?> = a.images.mapIndexed { i, path ->
                if (path.isEmpty()) null
                else if (DataManager.isImageOffline(this, "news/$path")) first[i]
                else DataManager.fetchCmsImageBytes(this, "news/$path", false)
                    ?.let { CoverCache.decodeBytes(it, 800) } ?: first[i]
            }
            if (full != first) runOnUiThread { buildBody(full) }
        }.start()
    }

    /**
     * Bangun isi: blok paragraf teks + gambar inline yang disebar
     * merata di antaranya. [bitmaps] null = semua gambar masih
     * placeholder (dipakai sebelum loadImages selesai).
     */
    private fun buildBody(bitmaps: List<Bitmap?>?) {
        val a = article ?: return
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val textSize = ProgressStore.getTextSizeSp(this)
        val d = resources.displayMetrics.density
        bodyContainer.removeAllViews()
        // v1.1.18: hentikan denyut lama — TextView lama dibuang di atas.
        for (anim in pulseAnimators) PulseText.stop(anim)
        pulseAnimators.clear()
        val bodyText = if (a.details.isNotBlank()) a.details else a.summary
        val blocks = bodyText.split("\n\n").filter { it.isNotBlank() }
        val positions = NewsBodyLayout.insertAfter(blocks.size, a.images.size)

        fun addImage(idx: Int) {
            val iv = ImageView(this)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = (12 * d).toInt()
            params.bottomMargin = (12 * d).toInt()
            iv.layoutParams = params
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.contentDescription = "Gambar artikel ${idx + 1}"
            val bmp = bitmaps?.getOrNull(idx)
            if (bmp != null) iv.setImageBitmap(bmp)
            else iv.setImageResource(R.drawable.news_placeholder)
            bodyContainer.addView(iv)
        }

        if (blocks.isEmpty()) {
            for (i in a.images.indices) addImage(i)
            return
        }
        // v1.1.17: tiap paragraf berita ditulis Markdown — render native
        // via Markwon (tanpa WebView). Baris tunggal (\n) tetap ganti
        // baris supaya artikel lama tampil sama persis.
        // v1.1.18: + coret (~~teks~~) dan warna (<font color="#...">teks</font>).
        val markwon = Markwon.builder(this)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create { it.addHandler(FontColorHandler()) })
            .build()
        for ((blockIdx, block) in blocks.withIndex()) {
            val tv = TextView(this)
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            markwon.setMarkdown(tv, block)
            tv.movementMethod = LinkMovementMethod.getInstance()
            // v1.1.18: kata berwarna di blok ini berdenyut halus.
            PulseText.start(tv)?.let { pulseAnimators += it }
            tv.setTextColor(palette.ink)
            tv.textSize = textSize
            tv.setLineSpacing(0f, 1.6f)
            bodyContainer.addView(tv)
            positions.forEachIndexed { imgIdx, afterIdx ->
                if (afterIdx == blockIdx) addImage(imgIdx)
            }
        }
    }

    private fun refreshDownloadButton() {
        val a = article ?: return
        val offline = DataManager.isNewsOffline(this, a)
        downloadButton.visibility =
            if (a.images.isEmpty() || offline || downloading) View.GONE else View.VISIBLE
        if (!downloading) downloadButton.text = "⬇ Unduh gambar untuk Offline"
    }

    private fun startDownload() {
        val a = article ?: return
        if (downloading) return
        downloading = true
        downloadButton.visibility = View.VISIBLE
        downloadButton.isEnabled = false
        downloadButton.text = "Mengunduh…"
        DataManager.downloadNewsItem(this, a, onProgress = { done, total, _ ->
            downloadButton.text = "Mengunduh $done/$total…"
        }) { result ->
            downloading = false
            downloadButton.isEnabled = true
            refreshDownloadButton()
            loadImages()
            Toast.makeText(
                this,
                if (result.failed == 0) "Gambar tersimpan offline"
                else "${result.downloaded} gambar diunduh, ${result.failed} gagal — coba lagi saat sinyal bagus",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyThemeAndFont() {
        val theme = ProgressStore.getTheme(this)
        val palette = ThemeHelper.palette(theme)
        root.setBackgroundColor(palette.bg)
        titleText.setTextColor(palette.titleInk)
        metaText.setTextColor(palette.muted)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

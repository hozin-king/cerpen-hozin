package com.hozinking.cerpen

import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pembaca artikel berita lengkap (v1.1): galeri gambar horizontal + isi
 * penuh tanpa pemotongan. Kompatibel dengan data lama (1 gambar + summary):
 * galeri memakai `images`, fallback ke `image`; isi memakai `details`,
 * fallback ke `summary`.
 */
class NewsDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "news_title"
        const val EXTRA_SOURCE = "news_source"
        const val EXTRA_DATE = "news_date"
        const val EXTRA_SUMMARY = "news_summary"
        const val EXTRA_DETAILS = "news_details"
        const val EXTRA_IMAGES = "news_images"
    }

    private lateinit var root: View
    private lateinit var galleryScroll: HorizontalScrollView
    private lateinit var gallery: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var bodyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        root = findViewById(R.id.news_detail_root)
        galleryScroll = findViewById(R.id.news_gallery_scroll)
        gallery = findViewById(R.id.news_gallery)
        titleText = findViewById(R.id.news_title)
        metaText = findViewById(R.id.news_meta)
        bodyText = findViewById(R.id.news_body)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""
        val date = intent.getStringExtra(EXTRA_DATE) ?: ""
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        val details = intent.getStringExtra(EXTRA_DETAILS) ?: ""
        val images = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: arrayListOf()

        supportActionBar?.title = if (title.length > 28) title.take(28) + "…" else title
        titleText.text = title
        metaText.text = listOf(source, date).filter { it.isNotEmpty() }.joinToString(" · ")
        // Isi lengkap tanpa maxLines/pemotongan; fallback ke ringkasan.
        bodyText.text = if (details.isNotBlank()) details else summary

        buildGallery(images)
        applyThemeAndFont()
    }

    override fun onResume() {
        super.onResume()
        applyThemeAndFont()
    }

    private fun buildGallery(images: List<String>) {
        gallery.removeAllViews()
        if (images.isEmpty()) {
            galleryScroll.visibility = View.GONE
            return
        }
        galleryScroll.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        for (path in images) {
            val bmp = CoverCache.get(this, "news/$path", 800)
            val iv = ImageView(this)
            val params = LinearLayout.LayoutParams(
                (240 * density).toInt(),
                (160 * density).toInt()
            )
            params.marginEnd = (8 * density).toInt()
            iv.layoutParams = params
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            if (bmp != null) {
                iv.setImageBitmap(bmp)
            } else {
                iv.setImageResource(R.drawable.news_placeholder)
            }
            gallery.addView(iv)
        }
    }

    private fun applyThemeAndFont() {
        val theme = ProgressStore.getTheme(this)
        val palette = ThemeHelper.palette(theme)
        root.setBackgroundColor(palette.bg)
        titleText.setTextColor(palette.titleInk)
        metaText.setTextColor(palette.muted)
        bodyText.setTextColor(palette.ink)
        bodyText.textSize = ProgressStore.getTextSizeSp(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

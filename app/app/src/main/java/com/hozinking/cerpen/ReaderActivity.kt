package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CERPEN_ID = "cerpen_id"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
    }

    private lateinit var scrollView: ScrollView
    private lateinit var bodyText: TextView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var nextButton: Button
    private lateinit var smallerButton: Button
    private lateinit var biggerButton: Button
    private lateinit var themeButton: Button
    private lateinit var bookmarkButton: Button
    private lateinit var settingsButton: Button
    private lateinit var loading: ProgressBar
    private lateinit var readerRoot: View

    private var cerpen: Cerpen? = null
    private var seasonNo: Int = 1
    private var episodeNo: Int = 1
    private var textSizeSp: Float = 17f
    private var theme: Int = ProgressStore.THEME_DAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        readerRoot = findViewById(R.id.reader_root)
        scrollView = findViewById(R.id.reader_scroll)
        bodyText = findViewById(R.id.reader_body)
        titleText = findViewById(R.id.reader_title)
        metaText = findViewById(R.id.reader_meta)
        nextButton = findViewById(R.id.next_button)
        smallerButton = findViewById(R.id.text_smaller)
        biggerButton = findViewById(R.id.text_bigger)
        themeButton = findViewById(R.id.theme_button)
        bookmarkButton = findViewById(R.id.bookmark_button)
        settingsButton = findViewById(R.id.reader_settings_button)
        loading = findViewById(R.id.loading)

        val cerpenId = intent.getStringExtra(EXTRA_CERPEN_ID) ?: run { finish(); return }
        seasonNo = intent.getIntExtra(EXTRA_SEASON, 1)
        episodeNo = intent.getIntExtra(EXTRA_EPISODE, 1)
        textSizeSp = ProgressStore.getTextSizeSp(this)
        theme = ProgressStore.getTheme(this)

        smallerButton.setOnClickListener {
            textSizeSp = (textSizeSp - 1f).coerceAtLeast(13f)
            applyTextSize()
        }
        biggerButton.setOnClickListener {
            textSizeSp = (textSizeSp + 1f).coerceAtMost(24f)
            applyTextSize()
        }
        themeButton.setOnClickListener {
            theme = (theme + 1) % 3
            ProgressStore.setTheme(this, theme)
            applyTheme()
        }
        bookmarkButton.setOnClickListener {
            val c = cerpen ?: return@setOnClickListener
            val now = ProgressStore.toggleBookmark(this, c.id, seasonNo, episodeNo)
            updateBookmarkLabel()
            Toast.makeText(
                this,
                if (now) "Ditandai 🔖" else "Tanda dihapus",
                Toast.LENGTH_SHORT
            ).show()
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Simpan progres saat scroll.
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            saveProgress()
        }

        loading.visibility = View.VISIBLE
        Thread {
            val list = DataManager.loadCerpen(this)
            val found = list.firstOrNull { it.id == cerpenId }
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

    private fun bind(c: Cerpen) {
        val ep = c.episode(seasonNo, episodeNo) ?: run { finish(); return }
        supportActionBar?.title = c.judul
        titleText.text = ep.judul
        metaText.text = "${c.judul} · Season $seasonNo · Episode ${ep.no}"
        // v1.1.17: isi episode ditulis Markdown — render native via Markwon
        // (tanpa WebView). SoftBreakAddsNewLine: baris tunggal (\n) tetap
        // ganti baris, jadi episode lama tampil sama persis.
        val markwon = Markwon.builder(this)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .build()
        markwon.setMarkdown(bodyText, ep.isi)
        bodyText.movementMethod = LinkMovementMethod.getInstance()
        applyTextSize()
        applyTheme()
        updateBookmarkLabel()

        // Catat ke riwayat baca.
        ProgressStore.addHistory(this, c.id, seasonNo, episodeNo)

        val next = c.nextEpisode(seasonNo, episodeNo)
        if (next != null) {
            nextButton.visibility = View.VISIBLE
            nextButton.text = "Episode Berikutnya: ${next.second.judul}"
            nextButton.setOnClickListener {
                saveProgress()
                val i = Intent(this, ReaderActivity::class.java)
                i.putExtra(EXTRA_CERPEN_ID, c.id)
                i.putExtra(EXTRA_SEASON, next.first)
                i.putExtra(EXTRA_EPISODE, next.second.no)
                startActivity(i)
                finish()
            }
        } else {
            nextButton.visibility = View.VISIBLE
            nextButton.text = "Tamat — Kembali ke Daftar"
            nextButton.setOnClickListener { finish() }
        }

        // Kembalikan posisi baca bila ini episode terakhir yang dibuka.
        val last = ProgressStore.getLast(this)
        if (last != null && last.cerpenId == c.id && last.season == seasonNo && last.episode == episodeNo) {
            scrollView.post {
                val content = scrollView.getChildAt(0) ?: return@post
                val max = (content.height - scrollView.height).coerceAtLeast(1)
                val y = (max * last.percent / 100f).toInt()
                scrollView.scrollTo(0, y)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Terapkan ulang bila tema/ukuran font diubah dari Pengaturan.
        textSizeSp = ProgressStore.getTextSizeSp(this)
        theme = ProgressStore.getTheme(this)
        applyTextSize()
        applyTheme()
    }

    private fun applyTextSize() {
        bodyText.textSize = textSizeSp
        ProgressStore.setTextSizeSp(this, textSizeSp)
    }

    private fun applyTheme() {
        val palette = ThemeHelper.palette(theme)
        readerRoot.setBackgroundColor(palette.bg)
        bodyText.setTextColor(palette.ink)
        titleText.setTextColor(palette.titleInk)
        metaText.setTextColor(palette.muted)
        themeButton.text = when (theme) {
            ProgressStore.THEME_NIGHT -> "Tema: Malam"
            ProgressStore.THEME_SEPIA -> "Tema: Sepia"
            else -> "Tema: Siang"
        }
    }

    private fun updateBookmarkLabel() {
        val c = cerpen ?: return
        val marked = ProgressStore.isBookmarked(this, c.id, seasonNo, episodeNo)
        bookmarkButton.text = if (marked) "🔖 Ditandai" else "☆ Tandai"
    }

    private fun saveProgress() {
        val c = cerpen ?: return
        val content = scrollView.getChildAt(0) ?: return
        val max = (content.height - scrollView.height).coerceAtLeast(1)
        val y = scrollView.scrollY.coerceIn(0, max)
        val percent = (y * 100 / max).coerceIn(0, 100)
        ProgressStore.saveLast(this, c.id, seasonNo, episodeNo, percent)
        if (percent >= 95) {
            ProgressStore.markDone(this, c.id, seasonNo, episodeNo)
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onSupportNavigateUp(): Boolean {
        saveProgress()
        finish()
        return true
    }
}

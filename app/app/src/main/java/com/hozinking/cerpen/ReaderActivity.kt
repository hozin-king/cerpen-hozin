package com.hozinking.cerpen

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.html.HtmlPlugin

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
    // v1.1.18: animator denyut kata berwarna (null bila episode ini
    // tidak punya kata berwarna — lihat PulseText).
    private var pulseAnimator: ValueAnimator? = null
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

    // v1.1.30: pelacak waktu baca (leaderboard) — hanya bila sudah login.
    private val tracker = ReadingTracker()
    private val tickHandler = Handler(Looper.getMainLooper())
    private var trackingActive = false
    /** true setelah bindContent (episode tampil) — penjaga restart ticker. */
    private var bound = false
    private val tickRunnable: Runnable = Runnable { onTick() }

    /** Satu detak pelacak baca (1 dtk): hitung + flush bila waktunya. */
    private fun onTick() {
        if (!trackingActive) return
        tracker.tick()
        if (tracker.flushDue()) flushReading()
        tickHandler.postDelayed(tickRunnable, 1000)
    }

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

        // Simpan progres saat scroll (+ sinyal aktivitas pelacak baca).
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            saveProgress()
            tracker.noteActive()
            if (scrollView.scrollY > 0) tracker.noteScrolled(scrollView.scrollY)
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

    /**
     * v1.1.30: pertahanan lapis kedua — reader memeriksa ulang gerbang dari
     * cache sebelum menampilkan isi (menangkap deep-link / intent langsung).
     * Ditolak -> tutup. Lolos -> tampilkan isi + mulai pelacak baca.
     */
    private fun bind(c: Cerpen) {
        val ep = c.episode(seasonNo, episodeNo) ?: run { finish(); return }
        val locallyRead = ProgressStore.isDone(this, c.id, seasonNo, episodeNo) ||
            ProgressStore.getHistory(this).any {
                it.cerpenId == c.id && it.season == seasonNo && it.episode == episodeNo
            }
        CoinUi.ensureUnlocked(this, "cerpen", c.id, seasonNo, episodeNo, locallyRead,
            onAllowed = { bindContent(c, ep) },
            onDenied = { finish() })
    }

    private fun bindContent(c: Cerpen, ep: Episode) {
        supportActionBar?.title = c.judul
        titleText.text = ep.judul
        metaText.text = "${c.judul} · Season $seasonNo · Episode ${ep.no}"
        // v1.1.17: isi episode ditulis Markdown — render native via Markwon
        // (tanpa WebView). SoftBreakAddsNewLine: baris tunggal (\n) tetap
        // ganti baris, jadi episode lama tampil sama persis.
        // v1.1.18: + coret (~~teks~~) dan warna (<font color="#...">teks</font>).
        val markwon = Markwon.builder(this)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create { it.addHandler(FontColorHandler()) })
            .build()
        markwon.setMarkdown(bodyText, ep.isi)
        bodyText.movementMethod = LinkMovementMethod.getInstance()
        // v1.1.18: kata berwarna berdenyut halus (pilihan user).
        PulseText.stop(pulseAnimator)
        pulseAnimator = PulseText.start(bodyText)
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
                // v1.1.30: episode berikutnya juga lewat gerbang koin.
                val nextLocallyRead = ProgressStore.isDone(this, c.id, next.first, next.second.no) ||
                    ProgressStore.getHistory(this).any {
                        it.cerpenId == c.id && it.season == next.first && it.episode == next.second.no
                    }
                CoinUi.openEpisode(this, "cerpen", c.id, next.first, next.second.no, nextLocallyRead) {
                    val i = Intent(this, ReaderActivity::class.java)
                    i.putExtra(EXTRA_CERPEN_ID, c.id)
                    i.putExtra(EXTRA_SEASON, next.first)
                    i.putExtra(EXTRA_EPISODE, next.second.no)
                    startActivity(i)
                    finish()
                }
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

        // v1.1.30: episode tampil (gerbang lolos) — mulai pelacak baca.
        bound = true
        startTracking()
    }

    override fun onResume() {
        super.onResume()
        // Terapkan ulang bila tema/ukuran font diubah dari Pengaturan.
        textSizeSp = ProgressStore.getTextSizeSp(this)
        theme = ProgressStore.getTheme(this)
        applyTextSize()
        applyTheme()
        // v1.1.18: nyalakan lagi denyut yang dimatikan di onPause.
        if (pulseAnimator == null) pulseAnimator = PulseText.start(bodyText)
        // v1.1.30: lanjutkan pelacak baca (hanya bila sudah login).
        if (bound && trackingActive) {
            tracker.noteVisible(true)
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000)
        }
    }

    /** v1.1.30: kirim detik baca tertunda ke server (best-effort). */
    private fun flushReading(force: Boolean = false) {
        val c = cerpen ?: return
        if (!trackingActive) return
        if (!force && !tracker.flushDue()) return
        val seconds = tracker.takeFlush()
        if (seconds <= 0) return
        Thread {
            val r = CoinApi.reportReading(this, "cerpen", c.id, seasonNo, episodeNo, seconds, tracker.maxScroll)
            if (r.status in 200..299) {
                tracker.confirmFlushed(r.json?.optInt("stored", 0) ?: 0)
            }
        }.start()
    }

    /** v1.1.30: mulai pelacak baca untuk episode ini (dipanggil dari bind). */
    private fun startTracking() {
        trackingActive = CoinStore.hasSession(this)
        if (!trackingActive) return
        tracker.noteVisible(true)
        tracker.noteActive()
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.postDelayed(tickRunnable, 1000)
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
        // v1.1.30: hentikan pelacak baca + kirim sisa detik (best-effort).
        // trackingActive sengaja TIDAK dimatikan — onResume menyalakan
        // lagi tickernya bila episode masih tampil.
        tickHandler.removeCallbacks(tickRunnable)
        tracker.noteVisible(false)
        flushReading(force = true)
        // v1.1.18: matikan denyut saat layar tidak terlihat (hemat baterai).
        PulseText.stop(pulseAnimator)
        pulseAnimator = null
    }

    override fun onSupportNavigateUp(): Boolean {
        saveProgress()
        finish()
        return true
    }
}

package com.hozinking.cerpen

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Pembaca komik gaya Webtoon (v1.1.26): RecyclerView vertikal continuous
 * scroll, panel full-width tanpa distorsi aspek, indikator "Panel X/Y" +
 * progress bar tipis, tap layar sembunyikan/tampilkan chrome (judul +
 * progress). Panel dimuat malas per item via CoverCache.loadInto — panel
 * GIF tetap beranimasi (AnimatedImageDrawable, seperti v1.1.25); tetangga
 * di-prefetch supaya scroll mulus. Tanpa WebView.
 */
class KomikReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KOMIK_ID = "komik_id"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_PANEL = "panel"
    }

    private lateinit var root: View
    private lateinit var headerBox: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var progressBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var counterText: TextView
    private lateinit var downloadButton: Button

    private var komikId: String = ""
    private var episodeNo: Int = 1
    private var startPanel: Int = 0
    private var currentKomik: Komik? = null
    private var currentEpisode: KomikEpisode? = null
    private var downloading = false
    private var chromeVisible = true

    // v1.1.30: pelacak waktu baca (leaderboard) — hanya bila sudah login.
    private val tracker = ReadingTracker()
    private val tickHandler = Handler(Looper.getMainLooper())
    private var trackingActive = false
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
        setContentView(R.layout.activity_komik_reader)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        root = findViewById(R.id.komik_reader_root)
        headerBox = findViewById(R.id.komik_reader_header)
        titleText = findViewById(R.id.komik_reader_title)
        metaText = findViewById(R.id.komik_reader_meta)
        loading = findViewById(R.id.loading)
        recycler = findViewById(R.id.komik_reader_list)
        progressBox = findViewById(R.id.komik_reader_progress_box)
        progressBar = findViewById(R.id.komik_reader_progress)
        counterText = findViewById(R.id.komik_reader_counter)
        downloadButton = findViewById(R.id.komik_reader_download)

        komikId = intent.getStringExtra(EXTRA_KOMIK_ID) ?: run { finish(); return }
        episodeNo = intent.getIntExtra(EXTRA_EPISODE, 1)
        startPanel = intent.getIntExtra(EXTRA_PANEL, 0)

        layoutManager = LinearLayoutManager(this)
        layoutManager.initialPrefetchItemCount = 2
        recycler.layoutManager = layoutManager
        recycler.setItemViewCacheSize(2)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateProgress()
                // v1.1.30: sinyal aktivitas pelacak baca.
                tracker.noteActive()
                val pos = layoutManager.findFirstVisibleItemPosition()
                if (pos > 0) tracker.noteScrolled(pos)
            }
        })
        recycler.addOnItemTouchListener(TapToToggleChrome())
        downloadButton.setOnClickListener { downloadEpisode() }

        applyTheme()
        loadEpisode()
    }

    /** Muat metadata episode di thread latar, lalu pasang adapter. */
    private fun loadEpisode() {
        loading.visibility = View.VISIBLE
        Thread {
            val komik = DataManager.loadKomik(this).firstOrNull { it.id == komikId }
            val episode = komik?.episode(episodeNo)
            runOnUiThread {
                loading.visibility = View.GONE
                if (komik == null || episode == null) {
                    finish()
                    return@runOnUiThread
                }
                bind(komik, episode)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        // v1.1.30: lanjutkan pelacak baca (hanya bila sudah login).
        if (bound && trackingActive) {
            tracker.noteVisible(true)
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.postDelayed(tickRunnable, 1000)
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        // v1.1.30: hentikan pelacak baca + kirim sisa detik (best-effort).
        tickHandler.removeCallbacks(tickRunnable)
        tracker.noteVisible(false)
        flushReading(force = true)
    }

    /** v1.1.30: kirim detik baca tertunda ke server (best-effort). */
    private fun flushReading(force: Boolean = false) {
        if (currentEpisode == null) return
        if (!trackingActive) return
        if (!force && !tracker.flushDue()) return
        val seconds = tracker.takeFlush()
        if (seconds <= 0) return
        Thread {
            val r = CoinApi.reportReading(this, "komik", komikId, 1, episodeNo, seconds, tracker.maxScroll)
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

    /** v1.1.13: simpan episode + panel paling atas yang terlihat saat
     *  keluar reader (dipakai tombol Lanjutkan di halaman detail). */
    private fun saveProgress() {
        currentEpisode ?: return
        val pos = layoutManager.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        ProgressStore.saveLastKomik(this, komikId, episodeNo, pos)
    }

    /**
     * v1.1.30: pertahanan lapis kedua — reader memeriksa ulang gerbang dari
     * cache sebelum menampilkan isi (menangkap deep-link / intent langsung).
     * Ditolak -> tutup. Lolos -> tampilkan isi + mulai pelacak baca.
     */
    private fun bind(komik: Komik, episode: KomikEpisode) {
        val locallyRead = CoinStore.isKomikReadLocally(this, komik.id, episode.no) ||
            ProgressStore.getLastKomik(this, komik.id)?.episode == episode.no
        CoinUi.ensureUnlocked(this, "komik", komik.id, 1, episode.no, locallyRead,
            onAllowed = { bindContent(komik, episode) },
            onDenied = { finish() })
    }

    private fun bindContent(komik: Komik, episode: KomikEpisode) {
        currentKomik = komik
        currentEpisode = episode
        // v1.1.30: catat episode ini sudah dibaca lokal (grandfathering, §3).
        CoinStore.markKomikRead(this, komik.id, episode.no)
        supportActionBar?.title = komik.judul
        titleText.text = episode.judul
        metaText.text = "${komik.judul} · Episode ${episode.no} · ${episode.panels.size} panel"
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        // v1.1.5: bila ada panel yang belum terunduh, tampilkan tombol
        // "Unduh episode ini" di atas daftar (bukan pesan jalan buntu).
        val (done, total) = DataManager.komikEpisodePanelOfflineCount(this, episode)
        val missing = total - done
        if (missing > 0) {
            downloadButton.visibility = View.VISIBLE
            downloadButton.text = "⬇ Unduh episode ini ($done/$total panel)"
            downloadButton.isEnabled = !downloading
        } else {
            downloadButton.visibility = View.GONE
        }
        recycler.adapter = PanelAdapter(episode, palette, scale)
        // v1.1.13: buka di panel terakhir bila diminta (tombol Lanjutkan);
        // bila tidak, mulai dari atas seperti biasa.
        val target = startPanel.coerceIn(0, (episode.panels.size - 1).coerceAtLeast(0))
        recycler.post {
            if (target > 0) layoutManager.scrollToPosition(target)
            updateProgress()
        }
        updateProgress()

        // v1.1.30: episode tampil (gerbang lolos) — mulai pelacak baca.
        bound = true
        startTracking()
    }

    /** Panel pertama yang terlihat -> "Panel X/Y" + progress bar. */
    private fun updateProgress() {
        val episode = currentEpisode ?: return
        val total = episode.panels.size
        if (total == 0) return
        val pos = layoutManager.findFirstVisibleItemPosition().coerceIn(0, total - 1)
        counterText.text = "Panel ${pos + 1}/$total"
        progressBar.max = total
        progressBar.progress = pos + 1
    }

    /** Tap di mana saja pada panel = sembunyikan/tampilkan chrome. */
    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        headerBox.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        progressBox.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        if (chromeVisible) supportActionBar?.show() else supportActionBar?.hide()
    }

    /** Single-tap (bukan scroll) pada RecyclerView -> toggle chrome. */
    private inner class TapToToggleChrome : RecyclerView.OnItemTouchListener {
        private val detector = GestureDetector(
            this@KomikReaderActivity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    toggleChrome()
                    return true
                }
            }
        )

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            detector.onTouchEvent(e)
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
    }

    /** ViewHolder satu panel — di level Activity (bukan di dalam
     *  PanelAdapter: kelas bersarang di inner class tidak diizinkan
     *  saat dipakai sebagai argumen supertype adapter). */
    class PanelVH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.panel_image)
        val teks: TextView = v.findViewById(R.id.panel_teks)
        val hint: TextView = v.findViewById(R.id.panel_hint)
    }

    /** Satu panel: gambar (malas, via CoverCache) + teks dialog di bawahnya. */
    private inner class PanelAdapter(
        private val episode: KomikEpisode,
        private val palette: ThemePalette,
        private val scale: Float
    ) : RecyclerView.Adapter<PanelVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_komik_panel, parent, false)
            return PanelVH(v)
        }

        override fun onBindViewHolder(h: PanelVH, pos: Int) {
            val panel = episode.panels[pos]
            h.teks.text = panel.teks
            h.teks.setTextColor(palette.inkSoft)
            h.teks.textSize = 15f * scale
            // v1.1.30.1: penanda panel belum terunduh ikut palet.
            h.hint.setTextColor(palette.muted)
            h.hint.visibility = View.GONE
            if (panel.image.isNotEmpty()) {
                CoverCache.loadInto(
                    h.itemView.context,
                    "komik/panels/${panel.image}",
                    600,
                    h.image,
                    R.drawable.cover_placeholder
                )
            } else {
                // Panel belum terunduh — placeholder + penanda singkat;
                // teks dialog tetap tampil (selalu ada di JSON).
                CoverCache.clear(h.image, R.drawable.cover_placeholder)
                h.hint.visibility = View.VISIBLE
            }
        }

        override fun onViewRecycled(h: PanelVH) {
            // Hentikan animasi GIF yang sedang berjalan di view ini.
            CoverCache.clear(h.image, R.drawable.cover_placeholder)
            super.onViewRecycled(h)
        }

        override fun getItemCount(): Int = episode.panels.size
    }

    /** v1.1.5: unduh episode ini, lalu muat ulang panel. */
    private fun downloadEpisode() {
        val komik = currentKomik ?: return
        val episode = currentEpisode ?: return
        if (downloading) return
        downloading = true
        downloadButton.isEnabled = false
        downloadButton.text = "Mengunduh…"
        DataManager.downloadKomikEpisode(this, komik, episode, onProgress = { done, total, _ ->
            downloadButton.text = "Mengunduh $done/$total…"
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
        counterText.setTextColor(palette.muted)
        progressBar.progressTintList = ColorStateList.valueOf(palette.accent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

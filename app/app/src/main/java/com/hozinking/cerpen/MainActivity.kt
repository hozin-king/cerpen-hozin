package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var appTitle: TextView
    private lateinit var appSubtitle: TextView
    private lateinit var appFooter: TextView
    private lateinit var settingsButton: Button
    private lateinit var tabCerpenButton: Button
    private lateinit var tabBeritaButton: Button
    private lateinit var tabKomikButton: Button
    private lateinit var cerpenTab: LinearLayout
    private lateinit var newsTab: LinearLayout
    private lateinit var komikTab: LinearLayout
    private lateinit var historyTitle: TextView
    private lateinit var bookmarkTitle: TextView
    private lateinit var allCerpenTitle: TextView
    private lateinit var newsTitle: TextView
    private lateinit var newsSubtitle: TextView
    private lateinit var komikTitle: TextView
    private lateinit var komikSubtitle: TextView
    private lateinit var continueSection: LinearLayout
    private lateinit var continueTitle: TextView
    private lateinit var continueMeta: TextView
    private lateinit var continueButton: Button
    private lateinit var searchInput: EditText
    private lateinit var genreRow: LinearLayout
    private lateinit var cerpenList: RecyclerView
    private lateinit var historySection: LinearLayout
    private lateinit var historyList: RecyclerView
    private lateinit var bookmarkSection: LinearLayout
    private lateinit var bookmarkList: RecyclerView
    private lateinit var newsList: RecyclerView
    private lateinit var komikList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var refreshButton: Button
    private lateinit var downloadAllButton: Button
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var syncProgressText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var cerpenData: List<Cerpen> = emptyList()
    private var newsData: List<NewsArticle> = emptyList()
    private var komikData: List<Komik> = emptyList()
    private var query: String = ""
    private var selectedGenre: String = "Semua"
    private var selectedTab: Int = 0 // 0 = Cerpen, 1 = Berita, 2 = Komik

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.main_root)
        appTitle = findViewById(R.id.app_title)
        appSubtitle = findViewById(R.id.app_subtitle)
        appFooter = findViewById(R.id.app_footer)
        settingsButton = findViewById(R.id.main_settings_button)
        tabCerpenButton = findViewById(R.id.tab_cerpen_button)
        tabBeritaButton = findViewById(R.id.tab_berita_button)
        tabKomikButton = findViewById(R.id.tab_komik_button)
        cerpenTab = findViewById(R.id.cerpen_tab)
        newsTab = findViewById(R.id.news_tab)
        komikTab = findViewById(R.id.komik_tab)
        historyTitle = findViewById(R.id.history_title)
        bookmarkTitle = findViewById(R.id.bookmark_title)
        allCerpenTitle = findViewById(R.id.all_cerpen_title)
        newsTitle = findViewById(R.id.news_title)
        newsSubtitle = findViewById(R.id.news_subtitle)
        komikTitle = findViewById(R.id.komik_title)
        komikSubtitle = findViewById(R.id.komik_subtitle)
        continueSection = findViewById(R.id.continue_section)
        continueTitle = findViewById(R.id.continue_title)
        continueMeta = findViewById(R.id.continue_meta)
        continueButton = findViewById(R.id.continue_button)
        searchInput = findViewById(R.id.search_input)
        genreRow = findViewById(R.id.genre_row)
        cerpenList = findViewById(R.id.cerpen_list)
        historySection = findViewById(R.id.history_section)
        historyList = findViewById(R.id.history_list)
        bookmarkSection = findViewById(R.id.bookmark_section)
        bookmarkList = findViewById(R.id.bookmark_list)
        newsList = findViewById(R.id.news_list)
        komikList = findViewById(R.id.komik_list)
        loading = findViewById(R.id.loading)
        refreshButton = findViewById(R.id.refresh_button)
        downloadAllButton = findViewById(R.id.download_all_button)
        syncProgressBar = findViewById(R.id.sync_progress_bar)
        syncProgressText = findViewById(R.id.sync_progress_text)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        // v1.2: pull-to-refresh memakai jalur sync yang SAMA dengan tombol
        // "Perbarui Konten" (syncNow) — tidak ada jalur sync kedua.
        swipeRefresh.setOnRefreshListener { syncNow() }

        cerpenList.layoutManager = LinearLayoutManager(this)
        historyList.layoutManager = LinearLayoutManager(this)
        bookmarkList.layoutManager = LinearLayoutManager(this)
        newsList.layoutManager = LinearLayoutManager(this)
        komikList.layoutManager = LinearLayoutManager(this)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim() ?: ""
                applyCerpenFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshButton.setOnClickListener { syncNow() }
        downloadAllButton.setOnClickListener { downloadAll() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tabCerpenButton.setOnClickListener { showTab(0) }
        tabBeritaButton.setOnClickListener { showTab(1) }
        tabKomikButton.setOnClickListener { showTab(2) }

        applyThemeAndFont()
        showTab(selectedTab)
        loadContent()
        // Sync HANYA manual (tombol Perbarui) — hemat baterai, tanpa proses background.
    }

    override fun onResume() {
        super.onResume()
        applyThemeAndFont()
        showContinueReading()
        showHistoryAndBookmarks()
        if (cerpenData.isNotEmpty()) {
            applyCerpenFilter()
        }
        if (komikData.isNotEmpty()) {
            applyKomikList()
        }
    }

    /** Terapkan tema + ukuran font global ke layar ini (dipanggil tiap resume). */
    private fun applyThemeAndFont() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        root.setBackgroundColor(palette.bg)
        appTitle.setTextColor(palette.titleInk)
        appSubtitle.setTextColor(palette.muted)
        appFooter.setTextColor(palette.muted)
        // v1.2: footer menampilkan versi asli dari BuildConfig (bukan hardcode).
        appFooter.text = "Cerpen Hozin v${BuildConfig.VERSION_NAME} · semua konten tersimpan di perangkat"
        swipeRefresh.setColorSchemeColors(palette.accent)
        historyTitle.setTextColor(palette.ink)
        bookmarkTitle.setTextColor(palette.ink)
        allCerpenTitle.setTextColor(palette.ink)
        newsTitle.setTextColor(palette.ink)
        newsSubtitle.setTextColor(palette.muted)
        komikTitle.setTextColor(palette.ink)
        komikSubtitle.setTextColor(palette.muted)
        continueTitle.setTextColor(palette.ink)
        continueMeta.setTextColor(palette.muted)
        searchInput.setTextColor(palette.ink)
        searchInput.setHintTextColor(palette.muted)
        searchInput.background = ThemeHelper.cardBackground(this, palette)
        continueSection.background = ThemeHelper.cardBackground(this, palette)
        showTab(selectedTab)
    }

    private fun showTab(tab: Int) {
        selectedTab = tab
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        cerpenTab.visibility = if (tab == 0) View.VISIBLE else View.GONE
        newsTab.visibility = if (tab == 1) View.VISIBLE else View.GONE
        komikTab.visibility = if (tab == 2) View.VISIBLE else View.GONE
        styleTabButton(tabCerpenButton, tab == 0, palette)
        styleTabButton(tabBeritaButton, tab == 1, palette)
        styleTabButton(tabKomikButton, tab == 2, palette)
    }

    private fun styleTabButton(button: Button, selected: Boolean, palette: ThemePalette) {
        if (selected) {
            button.setBackgroundColor(palette.accent)
            button.setTextColor(palette.onAccent)
        } else {
            button.setBackgroundColor(palette.card)
            button.setTextColor(palette.accent)
        }
    }

    private fun openNewsDetail(article: NewsArticle) {
        val i = Intent(this, NewsDetailActivity::class.java)
        i.putExtra(NewsDetailActivity.EXTRA_TITLE, article.title)
        i.putExtra(NewsDetailActivity.EXTRA_SOURCE, article.source)
        i.putExtra(NewsDetailActivity.EXTRA_DATE, article.date)
        i.putExtra(NewsDetailActivity.EXTRA_SUMMARY, article.summary)
        i.putExtra(NewsDetailActivity.EXTRA_DETAILS, article.details)
        i.putStringArrayListExtra(
            NewsDetailActivity.EXTRA_IMAGES,
            ArrayList(article.images)
        )
        startActivity(i)
    }

    private fun loadContent() {
        loading.visibility = View.VISIBLE
        Thread {
            val cerpen = DataManager.loadCerpen(this)
            val news = DataManager.loadNews(this)
            val komik = DataManager.loadKomik(this)
            runOnUiThread {
                cerpenData = cerpen
                newsData = news
                komikData = komik
                loading.visibility = View.GONE
                buildGenreChips()
                applyCerpenFilter()
                applyKomikList()
                val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
                val scale = ThemeHelper.fontScale(this)
                newsList.adapter = NewsAdapter(news, { openNewsDetail(it) }, palette, scale)
                showContinueReading()
                showHistoryAndBookmarks()
            }
        }.start()
    }

    private fun applyKomikList() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        komikList.adapter = KomikAdapter(komikData, { openKomikDetail(it) }, palette, scale)
    }

    private fun openKomikDetail(komik: Komik) {
        val i = Intent(this, KomikDetailActivity::class.java)
        i.putExtra(KomikDetailActivity.EXTRA_KOMIK_ID, komik.id)
        startActivity(i)
    }

    private fun buildGenreChips() {
        genreRow.removeAllViews()
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val genres = listOf("Semua") + cerpenData.map { it.genre }.distinct()
        for (g in genres) {
            val chip = Button(this).apply {
                text = g
                textSize = 13f
                isSingleLine = true
                // BUG 3: Button bawaan punya minWidth 88dp/minHeight 48dp dan
                // padding 0 begitu setBackgroundColor() mengganti background
                // 9-patch bawaan (yang memberi inset) dengan ColorDrawable —
                // teks menempel/terpotong di tepi chip. Nol-kan minimum
                // bawaan dan beri padding eksplisit agar lebar chip selalu
                // = lebar teks + padding, untuk SEMUA genre.
                minimumWidth = 0
                minimumHeight = 0
                val d = resources.displayMetrics.density
                setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                setTextColor(
                    if (g == selectedGenre) palette.onAccent
                    else palette.accent
                )
                setBackgroundColor(
                    if (g == selectedGenre) palette.accent
                    else palette.card
                )
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 8
                layoutParams = params
                setOnClickListener {
                    selectedGenre = g
                    buildGenreChips()
                    applyCerpenFilter()
                }
            }
            genreRow.addView(chip)
        }
    }

    private fun applyCerpenFilter() {
        val q = query.lowercase()
        val filtered = cerpenData.filter { c ->
            (selectedGenre == "Semua" || c.genre == selectedGenre) &&
                (q.isEmpty() || c.judul.lowercase().contains(q) || c.genre.lowercase().contains(q))
        }
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        cerpenList.adapter = CerpenAdapter(filtered, { openDetail(it) }, palette, scale)
    }

    private fun showContinueReading() {
        val last = ProgressStore.getLast(this)
        if (last == null) {
            continueSection.visibility = View.GONE
            return
        }
        val cerpen = cerpenData.firstOrNull { it.id == last.cerpenId } ?: run {
            continueSection.visibility = View.GONE
            return
        }
        val ep = cerpen.episode(last.season, last.episode)
        continueSection.visibility = View.VISIBLE
        continueTitle.text = cerpen.judul
        val epTitle = ep?.judul ?: "Episode ${last.episode}"
        continueMeta.text = "Season ${last.season} · Episode ${last.episode}: $epTitle · ${last.percent}% dibaca"
        continueButton.setOnClickListener {
            openReader(cerpen.id, last.season, last.episode)
        }
    }

    private fun showHistoryAndBookmarks() {
        // Riwayat baca.
        val history = ProgressStore.getHistory(this).mapNotNull { h ->
            val c = cerpenData.firstOrNull { it.id == h.cerpenId } ?: return@mapNotNull null
            val ep = c.episode(h.season, h.episode) ?: return@mapNotNull null
            Triple(c, h.season, ep)
        }
        if (history.isEmpty()) {
            historySection.visibility = View.GONE
        } else {
            historySection.visibility = View.VISIBLE
            val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
            val scale = ThemeHelper.fontScale(this)
            historyList.adapter = HistoryAdapter(history, { c, s, e -> openReader(c.id, s, e.no) }, palette, scale)
        }
        // Bookmark.
        val marks = ProgressStore.getBookmarks(this).mapNotNull { b ->
            val c = cerpenData.firstOrNull { it.id == b.cerpenId } ?: return@mapNotNull null
            val ep = c.episode(b.season, b.episode) ?: return@mapNotNull null
            Triple(c, b.season, ep)
        }
        if (marks.isEmpty()) {
            bookmarkSection.visibility = View.GONE
        } else {
            bookmarkSection.visibility = View.VISIBLE
            val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
            val scale = ThemeHelper.fontScale(this)
            bookmarkList.adapter = HistoryAdapter(marks, { c, s, e -> openReader(c.id, s, e.no) }, palette, scale)
        }
    }

    private fun openDetail(cerpen: Cerpen) {
        val i = Intent(this, DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_CERPEN_ID, cerpen.id)
        startActivity(i)
    }

    private fun openReader(cerpenId: String, season: Int, episode: Int) {
        val i = Intent(this, ReaderActivity::class.java)
        i.putExtra(ReaderActivity.EXTRA_CERPEN_ID, cerpenId)
        i.putExtra(ReaderActivity.EXTRA_SEASON, season)
        i.putExtra(ReaderActivity.EXTRA_EPISODE, episode)
        startActivity(i)
    }

    /** Matikan kedua tombol sync selama satu proses berjalan. */
    private fun setSyncBusy(busy: Boolean) {
        refreshButton.isEnabled = !busy
        downloadAllButton.isEnabled = !busy
    }

    /** Tampilkan progres sync: bilah determinan bila total diketahui. */
    private fun showSyncProgress(done: Int, total: Int, label: String) {
        syncProgressBar.visibility = View.VISIBLE
        syncProgressText.visibility = View.VISIBLE
        if (total > 0) {
            syncProgressBar.isIndeterminate = false
            syncProgressBar.max = total
            syncProgressBar.progress = done
            syncProgressText.text = "$label ($done/$total)"
        } else {
            syncProgressBar.isIndeterminate = true
            syncProgressText.text = label
        }
    }

    private fun hideSyncProgress() {
        syncProgressBar.visibility = View.GONE
        syncProgressText.visibility = View.GONE
    }

    private fun syncNow() {
        // Cegah sync ganda (tombol + tarik bersamaan).
        if (!refreshButton.isEnabled) return
        setSyncBusy(true)
        refreshButton.text = "Memperbarui..."
        swipeRefresh.isRefreshing = true
        DataManager.syncFromGithub(this, onProgress = { done, total, label ->
            showSyncProgress(done, total, label)
        }) { changed ->
            setSyncBusy(false)
            refreshButton.text = "Perbarui Konten"
            swipeRefresh.isRefreshing = false
            hideSyncProgress()
            if (changed) {
                loadContent()
                Toast.makeText(this, "Konten diperbarui", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Sudah terbaru / tidak ada internet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Unduh SEMUA konten untuk dibaca offline (cover cerpen, cover +
     * panel komik, gambar berita) dengan progres + ringkasan di akhir,
     * supaya tidak ada lagi cover/panel kosong saat tanpa internet.
     */
    private fun downloadAll() {
        if (!refreshButton.isEnabled) return
        setSyncBusy(true)
        downloadAllButton.text = "Mengunduh..."
        swipeRefresh.isRefreshing = true
        DataManager.downloadAllForOffline(this, onProgress = { done, total, label ->
            showSyncProgress(done, total, label)
        }) { result ->
            setSyncBusy(false)
            downloadAllButton.text = "Unduh Semua untuk Offline"
            swipeRefresh.isRefreshing = false
            hideSyncProgress()
            loadContent()
            if (result.downloaded == 0 && result.alreadyHad == 0) {
                Toast.makeText(this, "Tidak ada internet / daftar konten gagal diunduh", Toast.LENGTH_SHORT).show()
            } else if (result.failed == 0) {
                Toast.makeText(
                    this,
                    "Semua konten sudah offline (${result.downloaded} baru, ${result.alreadyHad} sudah ada)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "${result.downloaded} file diunduh, ${result.failed} gagal — coba lagi saat sinyal bagus",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

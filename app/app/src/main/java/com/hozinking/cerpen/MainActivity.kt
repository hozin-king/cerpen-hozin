package com.hozinking.cerpen

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

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
    /** Id item terbaru per daftar (badge NEW); dihitung tiap loadContent. */
    private var newestCerpenId: String? = null
    private var newestKomikId: String? = null
    /** Kunci unduh yang sedang berjalan — cegah ketuk ganda. */
    private val downloadingKeys = HashSet<String>()

    // ============ AUTO-UPDATE APLIKASI (v1.1.5) ============
    // Cek rilis GitHub terbaru; dialog HANYA untuk update kode aplikasi —
    // konten tetap di-sync DataManager tanpa update APK.
    private val updatePrefs by lazy { getSharedPreferences("update_check", MODE_PRIVATE) }
    private var updateDialog: AlertDialog? = null
    private var updateProgressDialog: AlertDialog? = null
    private var updateDownloadCancelled = false

    // v1.1.5: InvalidSetHasFixedSize di-suppress — false positive dari
    // detector lint (file-scoped): history_list/bookmark_list memang
    // wrap_content BY DESIGN (daftar pendek, expand penuh di ScrollView,
    // nested scrolling mati), dan keberadaannya membuat SEMUA
    // setHasFixedSize() di file ini ditandai. cerpen/news/komik_list
    // tingginya TETAP (480/560/480dp), jadi setHasFixedSize(true) valid.
    @SuppressLint("InvalidSetHasFixedSize")
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

        // v1.1.5: ukuran tetap agar recycling aktif (daftar 916 berita).
        cerpenList.setHasFixedSize(true)
        newsList.setHasFixedSize(true)
        komikList.setHasFixedSize(true)

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
        // v1.1.6: konten auto-update — tiap buka aplikasi (throttle 30 mnt)
        // langsung cek manifest GitHub dan unduh yang baru, tanpa ketuk
        // tombol Perbarui. Gagal (tanpa internet) = diam, konten lama
        // tetap bisa dibaca.
        checkForAppUpdate()
        autoSyncContent()
    }

    override fun onResume() {
        super.onResume()
        applyThemeAndFont()
        showContinueReading()
        showHistoryAndBookmarks()
        showPendingInstallIfReady()
        checkForAppUpdate()
        autoSyncContent()
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
        i.putStringArrayListExtra(
            NewsDetailActivity.EXTRA_IMAGE_URLS,
            ArrayList(article.imageUrls)
        )
        i.putExtra(NewsDetailActivity.EXTRA_ADDED_AT, article.addedAt)
        startActivity(i)
    }

    private fun loadContent() {
        loading.visibility = View.VISIBLE
        Thread {
            val cerpen = DataManager.loadCerpen(this)
            val news = DataManager.loadNews(this)
            val komik = DataManager.loadKomik(this)
            runOnUiThread {
                // v1.1.5: semua daftar urut terbaru dulu. Berita: addedAt
                // (ISO) menurun; cerpen/komik: nomor id menurun.
                cerpenData = cerpen.sortedByDescending { ItemDates.trailingNumber(it.id) }
                newsData = news.sortedByDescending { it.addedAt.ifEmpty { it.date } }
                komikData = komik.sortedByDescending { ItemDates.trailingNumber(it.id) }
                newestCerpenId = cerpenData.firstOrNull()?.id
                newestKomikId = komikData.firstOrNull()?.id
                loading.visibility = View.GONE
                buildGenreChips()
                applyCerpenFilter()
                applyKomikList()
                val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
                val scale = ThemeHelper.fontScale(this)
                newsList.adapter = NewsAdapter(
                    newsData, { openNewsDetail(it) }, palette, scale,
                    onDownload = { a, pos -> downloadNews(a, pos) }
                )
                showContinueReading()
                showHistoryAndBookmarks()
            }
        }.start()
    }

    private fun applyKomikList() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        komikList.adapter = KomikAdapter(
            komikData, { openKomikDetail(it) }, palette, scale,
            onDownload = { k, pos -> downloadKomik(k, pos) },
            newestId = newestKomikId
        )
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
        cerpenList.adapter = CerpenAdapter(
            filtered, { openDetail(it) }, palette, scale,
            onDownload = { c, pos -> downloadCerpen(c, pos) },
            newestId = newestCerpenId
        )
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

    // ============ UNDUH PER KONTEN (v1.1.5) ============
    // Progres tampil di tombol baris itu ("Mengunduh 3/10…"); selesai
    // -> baris di-refresh (tombol jadi ikon unduh) + toast ringkasan.

    /** Update tombol Unduh baris yang sedang terlihat. */
    private fun setRowDownloading(list: RecyclerView, pos: Int, text: String) {
        val vh = list.findViewHolderForAdapterPosition(pos) as? DownloadRow ?: return
        vh.downloadButton.text = text
        vh.downloadButton.isEnabled = false
    }

    /** Refresh satu baris setelah unduh selesai (ikon/badge dihitung ulang). */
    private fun refreshRow(list: RecyclerView, pos: Int) {
        list.adapter?.notifyItemChanged(pos)
    }

    private fun downloadCerpen(c: Cerpen, pos: Int) {
        if (!downloadingKeys.add("cerpen:${c.id}")) return
        DataManager.downloadCerpen(this, c, onProgress = { done, total, _ ->
            setRowDownloading(cerpenList, pos, "Mengunduh $done/$total…")
        }) { result ->
            downloadingKeys.remove("cerpen:${c.id}")
            refreshRow(cerpenList, pos)
            Toast.makeText(this, downloadToast(result, "Cover"), Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadNews(a: NewsArticle, pos: Int) {
        if (!downloadingKeys.add("news:${a.title}")) return
        DataManager.downloadNewsItem(this, a, onProgress = { done, total, _ ->
            setRowDownloading(newsList, pos, "Mengunduh $done/$total…")
        }) { result ->
            downloadingKeys.remove("news:${a.title}")
            refreshRow(newsList, pos)
            Toast.makeText(this, downloadToast(result, "Gambar"), Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadKomik(k: Komik, pos: Int) {
        if (!downloadingKeys.add("komik:${k.id}")) return
        DataManager.downloadKomik(this, k, onProgress = { done, total, _ ->
            setRowDownloading(komikList, pos, "Mengunduh $done/$total…")
        }) { result ->
            downloadingKeys.remove("komik:${k.id}")
            refreshRow(komikList, pos)
            Toast.makeText(this, downloadToast(result, "Panel"), Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadToast(result: SyncResult, what: String): String {
        return if (result.downloaded == 0 && result.failed == 0) {
            "Sudah tersimpan offline"
        } else if (result.failed == 0) {
            "$what tersimpan offline (${result.downloaded} baru)"
        } else if (result.downloaded == 0) {
            "Gagal mengunduh — periksa internet lalu coba lagi"
        } else {
            "${result.downloaded} $what diunduh, ${result.failed} gagal — coba lagi saat sinyal bagus"
        }
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

    // ============ AUTO-UPDATE KONTEN (v1.1.6) ============
    // Tiap buka aplikasi / kembali ke aplikasi: cek manifest GitHub dan
    // unduh konten baru (cerpen/berita/komik) secara otomatis, tanpa
    // ketuk tombol Perbarui. Throttle maks 1x per 30 menit (dicatat di
    // prefs yang sama dengan cek update aplikasi). Fail-open: tanpa
    // internet atau gagal = diam, konten lama tetap bisa dibaca. Toast
    // HANYA bila ada konten baru — bila sudah terbaru, diam.
    private fun autoSyncContent() {
        // Jangan tumpuk dengan sync manual yang sedang berjalan.
        if (!refreshButton.isEnabled) return
        val now = System.currentTimeMillis()
        val last = updatePrefs.getLong("last_auto_sync_ms", 0)
        if (now - last < 30 * 60 * 1000L) return
        updatePrefs.edit().putLong("last_auto_sync_ms", now).apply()
        setSyncBusy(true)
        DataManager.syncFromGithub(this, onProgress = { done, total, label ->
            showSyncProgress(done, total, label)
        }) { changed ->
            setSyncBusy(false)
            hideSyncProgress()
            if (changed) {
                loadContent()
                Toast.makeText(this, "Konten baru diunduh", Toast.LENGTH_SHORT).show()
            }
        }
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

    // ============ AUTO-UPDATE APLIKASI (v1.1.5) ============
    // Sumber: GitHub Releases API (otomatis, tanpa file versi manual).
    // Fail-open: cek gagal (tanpa internet / API error) = diam, aplikasi
    // tetap jalan normal. Dialog ini HANYA update kode — konten di-sync
    // DataManager dan bisa update tanpa update APK.

    /** Cek update: onCreate + onResume, throttle maks 1x per 6 jam. */
    private fun checkForAppUpdate() {
        val installed = BuildConfig.VERSION_NAME
        // Force yang tertunda (tersimpan di prefs) tampil lagi tanpa jaringan.
        val forceTag = updatePrefs.getString("force_tag", null)
        val forceUrl = updatePrefs.getString("force_apk_url", null)
        if (forceTag != null && UpdateCheck.compareVersions(forceTag, installed) > 0) {
            val release = UpdateCheck.ReleaseInfo(
                forceTag, forceTag.removePrefix("v").removePrefix("V"), forceUrl,
                updatePrefs.getLong("force_apk_size", 0),
                updatePrefs.getString("force_apk_digest", null)
            )
            showUpdateDialog(UpdateCheck.Decision.Force(release))
            return
        }
        val now = System.currentTimeMillis()
        val last = updatePrefs.getLong("last_check_ms", 0)
        if (now - last < 6 * 60 * 60 * 1000L) return
        updatePrefs.edit().putLong("last_check_ms", now).apply()
        Thread {
            val decision = try {
                UpdateCheck.check(installed)
            } catch (e: Exception) {
                UpdateCheck.Decision.None
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (decision) {
                    is UpdateCheck.Decision.None -> Unit
                    is UpdateCheck.Decision.Soft -> {
                        // Sekali ditutup untuk versi itu, jangan muncul lagi
                        // sampai ada versi yang LEBIH baru.
                        if (updatePrefs.getString("dismissed_tag", null) == decision.release.tag) return@runOnUiThread
                        showUpdateDialog(decision)
                    }
                    is UpdateCheck.Decision.Force -> {
                        updatePrefs.edit()
                            .putString("force_tag", decision.release.tag)
                            .putString("force_apk_url", decision.release.apkUrl)
                            .putLong("force_apk_size", decision.release.apkSize)
                            .putString("force_apk_digest", decision.release.apkDigest)
                            .apply()
                        showUpdateDialog(decision)
                    }
                }
            }
        }.start()
    }

    /** Tampilkan dialog update (biasa atau paksa). */
    private fun showUpdateDialog(decision: UpdateCheck.Decision) {
        if (updateDialog?.isShowing == true) return
        val release = when (decision) {
            is UpdateCheck.Decision.Soft -> decision.release
            is UpdateCheck.Decision.Force -> decision.release
            else -> return
        }
        val forced = decision is UpdateCheck.Decision.Force
        val builder = AlertDialog.Builder(this)
            .setTitle(if (forced) "Update wajib" else "Versi baru tersedia")
            .setMessage(
                if (forced) {
                    "Cerpen Hozin v${release.version} sudah tersedia dan versi kamu " +
                        "sudah tertinggal 3 rilis. Perbarui dulu untuk lanjut memakai aplikasi."
                } else {
                    "Cerpen Hozin v${release.version} sudah tersedia " +
                        "(versi kamu v${BuildConfig.VERSION_NAME}). " +
                        "Perbarui untuk fitur terbaru dan perbaikan."
                }
            )
            .setPositiveButton(if (forced) "Update Sekarang" else "Update") { _, _ ->
                startApkDownload(release)
            }
            .setCancelable(!forced)
        if (!forced) {
            builder.setNegativeButton("Nanti") { _, _ ->
                updatePrefs.edit().putString("dismissed_tag", release.tag).apply()
            }
        }
        val dialog = builder.create()
        if (forced) {
            // Blokir tombol back selama dialog paksa tampil.
            dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        }
        dialog.setOnDismissListener { updateDialog = null }
        updateDialog = dialog
        dialog.show()
    }

    /**
     * Unduh APK di dalam aplikasi dengan dialog progres. Bila APK tag ini
     * sudah terunduh, langsung instal tanpa unduh ulang.
     */
    private fun startApkDownload(release: UpdateCheck.ReleaseInfo) {
        val tag = release.tag
        val existing = AppUpdate.apkFileFor(this, tag)
        if (existing.exists() && existing.length() > 0) {
            installDownloadedApk(release, existing)
            return
        }
        val url = release.apkUrl
        if (url.isNullOrEmpty()) {
            // API tidak menemukan aset .apk — fallback browser.
            Toast.makeText(this, "Link APK tidak ditemukan — dibuka lewat browser", Toast.LENGTH_LONG).show()
            AppUpdate.openInBrowser(this)
            return
        }
        updateDownloadCancelled = false
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val statusText = TextView(this).apply { text = "Mengunduh… 0%" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        layout.addView(statusText)
        layout.addView(bar)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Mengunduh update v${release.version}")
            .setView(layout)
            .setNegativeButton("Batal") { _, _ -> updateDownloadCancelled = true }
            .setCancelable(false)
            .create()
        updateProgressDialog = progressDialog
        progressDialog.show()
        Thread {
            val file = AppUpdate.downloadApk(this, tag, url, onProgress = { p ->
                runOnUiThread {
                    if (p >= 0) {
                        bar.isIndeterminate = false
                        bar.progress = p
                        statusText.text = "Mengunduh… $p%"
                    }
                }
            }, isCancelled = { updateDownloadCancelled })
            runOnUiThread {
                updateProgressDialog?.dismiss()
                updateProgressDialog = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file != null) {
                    installDownloadedApk(release, file)
                } else if (!updateDownloadCancelled) {
                    Toast.makeText(this, "Unduh gagal — dibuka lewat browser", Toast.LENGTH_LONG).show()
                    AppUpdate.openInBrowser(this)
                }
            }
        }.start()
    }

    /**
     * Instal APK terunduh; bila belum diizinkan, arahkan ke Settings.
     *
     * APK SELALU diverifikasi dulu (ukuran + digest bila ada + package +
     * signer cocok dengan aplikasi terpasang) di thread background —
     * termasuk APK lama dari cache, yang tidak pernah dipasang hanya
     * karena nama/tag cocok. Gagal verifikasi = file dihapus, installer
     * TIDAK dipanggil, pengguna diarahkan ke halaman rilis resmi.
     */
    private fun installDownloadedApk(release: UpdateCheck.ReleaseInfo, apk: File) {
        if (!AppUpdate.canInstall(this)) {
            updatePrefs.edit().putString("pending_install_tag", release.tag).apply()
            Toast.makeText(
                this,
                "Izinkan 'Instal aplikasi tak dikenal' untuk Cerpen Hozin, lalu kembali ke aplikasi",
                Toast.LENGTH_LONG
            ).show()
            AppUpdate.openUnknownSourcesSettings(this)
            return
        }
        updatePrefs.edit().remove("pending_install_tag").apply()
        Thread {
            val result = AppUpdate.verifyApk(this, apk, release.apkDigest, release.apkSize)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ApkUpdateVerification.installationAllowed(result)) {
                    try {
                        apk.delete()
                    } catch (ignored: Exception) {
                    }
                    Toast.makeText(
                        this,
                        "Update tidak bisa diverifikasi — file dihapus. Coba lagi atau unduh dari halaman rilis resmi.",
                        Toast.LENGTH_LONG
                    ).show()
                    AppUpdate.openInBrowser(this)
                    return@runOnUiThread
                }
                if (!AppUpdate.installApk(this, apk)) {
                    Toast.makeText(this, "Tidak bisa membuka installer — dibuka lewat browser", Toast.LENGTH_LONG).show()
                    AppUpdate.openInBrowser(this)
                }
            }
        }.start()
    }

    /**
     * Setelah pengguna kembali dari Settings izin instal: APK sudah
     * terunduh, tawarkan instalasi langsung tanpa unduh ulang.
     */
    private fun showPendingInstallIfReady() {
        if (updateDialog?.isShowing == true) return
        val tag = updatePrefs.getString("pending_install_tag", null) ?: return
        val apk = AppUpdate.apkFileFor(this, tag)
        if (!apk.exists() || apk.length() == 0L) {
            updatePrefs.edit().remove("pending_install_tag").apply()
            return
        }
        val release = UpdateCheck.ReleaseInfo(tag, tag.removePrefix("v").removePrefix("V"), null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update sudah terunduh")
            .setMessage("APK Cerpen Hozin ${release.version} sudah terunduh. Instal sekarang?")
            .setPositiveButton("Instal Sekarang") { _, _ -> installDownloadedApk(release, apk) }
            .setNegativeButton("Nanti") { _, _ ->
                updatePrefs.edit().remove("pending_install_tag").apply()
            }
            .setCancelable(true)
            .create()
        dialog.setOnDismissListener { updateDialog = null }
        updateDialog = dialog
        dialog.show()
    }
}

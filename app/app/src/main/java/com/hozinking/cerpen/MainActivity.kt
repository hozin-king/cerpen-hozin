package com.hozinking.cerpen

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
    private lateinit var mainScroll: ScrollView
    // v1.1.10: bottom dock kaca — satu sel = ikon + label; tab 3 = Koleksi.
    private data class DockItem(val cell: View, val icon: TextView, val label: TextView, val tab: Int)
    private lateinit var dockBar: LinearLayout
    private lateinit var dockBarBg: GlassView
    private lateinit var dockCari: View
    private lateinit var dockCariBg: GlassView
    private lateinit var dockCariIcon: TextView
    // v1.1.14: remote config — banner di atas daftar + spacer dock (tempat bubble Cari).
    private lateinit var remoteBanner: TextView
    private lateinit var dockSpacer: View
    private lateinit var dockItems: List<DockItem>
    private lateinit var cerpenTab: LinearLayout
    private lateinit var newsTab: LinearLayout
    private lateinit var komikTab: LinearLayout
    private lateinit var koleksiTab: LinearLayout
    private lateinit var koleksiTitle: TextView
    private lateinit var koleksiSubtitle: TextView
    private lateinit var koleksiSettingsButton: Button
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
    private lateinit var searchNewsInput: EditText
    private lateinit var searchKomikInput: EditText
    private lateinit var genreRow: LinearLayout
    private lateinit var genreScroll: HorizontalScrollView
    private lateinit var typeRow: LinearLayout
    private lateinit var cerpenList: RecyclerView
    private lateinit var historySection: LinearLayout
    private lateinit var historyList: RecyclerView
    private lateinit var bookmarkSection: LinearLayout
    private lateinit var bookmarkList: RecyclerView
    private lateinit var newsList: RecyclerView
    private lateinit var komikList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var syncProgressText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var cerpenData: List<Cerpen> = emptyList()
    private var newsData: List<NewsArticle> = emptyList()
    private var komikData: List<Komik> = emptyList()
    private var query: String = ""
    private var newsQuery: String = ""
    private var komikQuery: String = ""
    private var selectedGenre: String = "Semua"
    /** Filter jenis daftar Cerbung (v1.1.12): Semua / Cerbung / Cergam. */
    private var selectedType: String = "Semua"
    private var selectedTab: Int = 0 // 0 = Cerpen, 1 = Berita, 2 = Komik, 3 = Koleksi
    /** Remote config aktif (v1.1.14) + warna aksen override-nya (null = palette.accent). */
    private var remoteConfig: RemoteConfig = RemoteConfig.DEFAULT
    private var remoteAccent: Int? = null
    /** v1.1.20: token tautan berbagi yang menunggu konten selesai dimuat. */
    private var pendingShareToken: String? = null
    /** true saat satu proses sync sedang berjalan — cegah tumpuk. */
    private var syncBusy = false
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
        mainScroll = findViewById(R.id.main_scroll)
        dockBar = findViewById(R.id.dock_bar)
        dockBarBg = findViewById(R.id.dock_bar_bg)
        dockCari = findViewById(R.id.dock_cari)
        dockCariBg = findViewById(R.id.dock_cari_bg)
        dockCariIcon = findViewById(R.id.dock_cari_icon)
        remoteBanner = findViewById(R.id.remote_banner)
        dockSpacer = findViewById(R.id.dock_spacer)
        dockItems = listOf(
            DockItem(findViewById(R.id.dock_cerbung), findViewById(R.id.dock_cerbung_icon), findViewById(R.id.dock_cerbung_label), 0),
            DockItem(findViewById(R.id.dock_berita), findViewById(R.id.dock_berita_icon), findViewById(R.id.dock_berita_label), 1),
            DockItem(findViewById(R.id.dock_komik), findViewById(R.id.dock_komik_icon), findViewById(R.id.dock_komik_label), 2),
            DockItem(findViewById(R.id.dock_koleksi), findViewById(R.id.dock_koleksi_icon), findViewById(R.id.dock_koleksi_label), 3)
        )
        cerpenTab = findViewById(R.id.cerpen_tab)
        newsTab = findViewById(R.id.news_tab)
        komikTab = findViewById(R.id.komik_tab)
        koleksiTab = findViewById(R.id.koleksi_tab)
        koleksiTitle = findViewById(R.id.koleksi_title)
        koleksiSubtitle = findViewById(R.id.koleksi_subtitle)
        koleksiSettingsButton = findViewById(R.id.koleksi_settings_button)
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
        searchNewsInput = findViewById(R.id.search_news_input)
        searchKomikInput = findViewById(R.id.search_komik_input)
        genreRow = findViewById(R.id.genre_row)
        genreScroll = findViewById(R.id.genre_scroll)
        typeRow = findViewById(R.id.type_row)
        // v1.1.12: latar kaca dock menangkap konten di baliknya.
        dockBarBg.backdropView = mainScroll
        dockCariBg.backdropView = mainScroll
        buildTypeChips()
        cerpenList = findViewById(R.id.cerpen_list)
        historySection = findViewById(R.id.history_section)
        historyList = findViewById(R.id.history_list)
        bookmarkSection = findViewById(R.id.bookmark_section)
        bookmarkList = findViewById(R.id.bookmark_list)
        newsList = findViewById(R.id.news_list)
        komikList = findViewById(R.id.komik_list)
        loading = findViewById(R.id.loading)
        syncProgressBar = findViewById(R.id.sync_progress_bar)
        syncProgressText = findViewById(R.id.sync_progress_text)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        // v1.2: pull-to-refresh = satu-satunya pemicu sync manual (syncNow).
        swipeRefresh.setOnRefreshListener { syncNow() }
        // v1.1.17: SwipeRefreshLayout hanya melihat child langsung
        // (ScrollView), jadi tarikan ke bawah saat RecyclerView tab aktif
        // sedang ter-scroll ikut ke-intercept jadi refresh. Callback ini
        // menganggap "bisa scroll ke atas" bila ScrollView ATAU daftar
        // tab aktif masih bisa scroll — refresh hanya saat semuanya di atas.
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            mainScroll.canScrollVertically(-1) || when (selectedTab) {
                0 -> cerpenList.canScrollVertically(-1)
                1 -> newsList.canScrollVertically(-1)
                2 -> komikList.canScrollVertically(-1)
                else -> historyList.canScrollVertically(-1) ||
                    bookmarkList.canScrollVertically(-1)
            }
        }

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

        // v1.1.11: cari di Berita (judul/sumber) + Komik (judul/genre),
        // pola sama dengan search Cerpen di atas.
        searchNewsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                newsQuery = s?.toString()?.trim() ?: ""
                applyNewsList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchKomikInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                komikQuery = s?.toString()?.trim() ?: ""
                applyKomikList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        koleksiSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dockItems.forEach { item ->
            // v1.1.11: ketuk-ulang tab aktif = scroll ke atas + refresh.
            item.cell.setOnClickListener {
                if (item.tab == selectedTab) onTabReselected(item.tab) else showTab(item.tab)
            }
        }
        dockCari.setOnClickListener { focusSearch() }

        // v1.1.12: bubble Cari muncul dengan morphing — scale + overshoot,
        // menempel ke tepi pill (leher cair didekati overlap, tanpa path).
        dockCari.scaleX = 0.5f
        dockCari.scaleY = 0.5f
        dockCari.translationY = 28f * resources.displayMetrics.density
        dockCari.animate()
            .scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(150)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        applyThemeAndFont()
        showTab(selectedTab)
        loadContent()
        // v1.1.20: dibuka lewat tautan berbagi — token ditahan sampai
        // daftar konten selesai dimuat (lihat loadContent), baru di-resolve.
        pendingShareToken = ShareLink.extractTokenFromPath(intent?.data?.path)
        // v1.1.14: remote config — terapkan cache dulu, lalu fetch
        // non-blocking (gagal = diam, cache/DEFAULT tetap dipakai).
        remoteConfig = RemoteConfig.loadCached(this)
        applyRemoteConfig(remoteConfig)
        fetchRemoteConfig()
        // v1.1.6: konten auto-update — tiap buka aplikasi (throttle 30 mnt)
        // langsung cek manifest GitHub dan unduh yang baru, tanpa aksi
        // pengguna. Gagal (tanpa internet) = diam, konten lama
        // tetap bisa dibaca.
        // v1.1.23: hapus APK update yang instalnya sudah sukses
        // (versi terpasang >= tag APK) — file tidak lagi dibutuhkan.
        // Gagal/batal instal = file tetap ada untuk retry.
        AppUpdate.deleteInstalledApks(this, BuildConfig.VERSION_NAME)
        checkForAppUpdate()
        autoSyncContent()
    }

    override fun onResume() {
        super.onResume()
        // v1.1.15: cek update manual dari Pengaturan — jalan sekali saat kembali.
        if (updatePrefs.getBoolean("manual_check_requested", false)) {
            updatePrefs.edit().remove("manual_check_requested").apply()
            checkForAppUpdateManual()
        }
        applyThemeAndFont()
        showContinueReading()
        showHistoryAndBookmarks()
        showPendingInstallIfReady()
        checkForAppUpdate()
        autoSyncContent()
        if (cerpenData.isNotEmpty()) {
            applyCerpenFilter()
        }
        if (newsData.isNotEmpty()) {
            applyNewsList()
        }
        if (komikData.isNotEmpty()) {
            applyKomikList()
        }
        refreshGlass()
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
        koleksiTitle.setTextColor(palette.ink)
        koleksiSubtitle.setTextColor(palette.muted)
        continueTitle.setTextColor(palette.ink)
        continueMeta.setTextColor(palette.muted)
        searchInput.setTextColor(palette.ink)
        searchInput.setHintTextColor(palette.muted)
        searchInput.background = ThemeHelper.cardBackground(this, palette)
        searchNewsInput.setTextColor(palette.ink)
        searchNewsInput.setHintTextColor(palette.muted)
        searchNewsInput.background = ThemeHelper.cardBackground(this, palette)
        searchKomikInput.setTextColor(palette.ink)
        searchKomikInput.setHintTextColor(palette.muted)
        searchKomikInput.background = ThemeHelper.cardBackground(this, palette)
        continueSection.background = ThemeHelper.cardBackground(this, palette)
        // v1.1.12: dock + bubble digambar GlassView (blur latar + tint +
        // stroke); dockBar/dockCari sendiri transparan.
        dockBarBg.setGlass(palette, GlassView.SHAPE_PILL)
        dockCariBg.setGlass(palette, GlassView.SHAPE_OVAL)
        dockCariIcon.setTextColor(palette.onAccent)
        // v1.1.14: terapkan lagi urutan/label/banner/aksen config (tema
        // bisa berubah dari Settings — styling banner ikut palet baru).
        applyRemoteConfig(remoteConfig)
    }

    // ============ REMOTE CONFIG (v1.1.14) ============
    // Tab dock, banner, dan aksen dari CMS — tanpa toast/error ke user
    // bila fetch gagal (cache terakhir / DEFAULT tetap dipakai).

    /** Fetch config di thread background; sukses -> simpan + terapkan. */
    private fun fetchRemoteConfig() {
        Thread {
            val fetched = RemoteConfig.fetch(this) ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyRemoteConfig(fetched)
            }
        }.start()
    }

    /** Indeks tab (0..3) yang visible menurut config aktif. */
    private fun visibleTabIndices(): List<Int> =
        remoteConfig.tabs.mapNotNull { t ->
            RemoteConfig.tabIndex(t.id).takeIf { it >= 0 && t.visible }
        }

    /**
     * Terapkan config ke dock + banner + aksen. Urutan sel dock mengikuti
     * urutan `tabs`; spacer (tempat bubble Cari) tetap di tengah (posisi
     * ke-3). Semua tab hidden -> tampilkan semua dengan default.
     */
    private fun applyRemoteConfig(config: RemoteConfig) {
        remoteConfig = config
        remoteAccent = config.accentColor()
        val tabs = if (config.tabs.any { it.visible }) config.tabs else RemoteConfig.DEFAULT.tabs
        dockBar.removeAllViews()
        var spacerAdded = false
        tabs.forEachIndexed { i, t ->
            if (i == 2) {
                dockBar.addView(dockSpacer)
                spacerAdded = true
            }
            val item = dockItems.firstOrNull { it.tab == RemoteConfig.tabIndex(t.id) } ?: return@forEachIndexed
            item.label.text = t.label.ifEmpty { RemoteConfig.defaultLabel(t.id) }
            item.cell.visibility = if (t.visible) View.VISIBLE else View.GONE
            dockBar.addView(item.cell)
        }
        if (!spacerAdded) dockBar.addView(dockSpacer)
        // Banner: satu untuk semua tab, maks 140 karakter; kosong = sembunyi.
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val bannerText = config.banner.trim().take(RemoteConfig.MAX_BANNER_CHARS)
        if (bannerText.isEmpty()) {
            remoteBanner.visibility = View.GONE
        } else {
            remoteBanner.text = bannerText
            remoteBanner.setTextColor(palette.ink)
            remoteBanner.background = ThemeHelper.cardBackground(this, palette)
            remoteBanner.visibility = View.VISIBLE
        }
        // Bila tab aktif disembunyikan -> showTab pindah ke visible pertama.
        showTab(selectedTab)
    }

    /** Minta tangkap ulang blur kaca (saat konten berubah). */
    private fun refreshGlass() {
        dockBarBg.scheduleRefresh()
        dockCariBg.scheduleRefresh()
    }

    private fun showTab(tab: Int) {
        // v1.1.14: tab yang disembunyikan config tidak bisa dibuka —
        // pindah ke tab visible pertama (fail-closed bila semua hidden).
        val visible = visibleTabIndices()
        val effective = if (tab in visible) tab else visible.firstOrNull() ?: 0
        selectedTab = effective
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        cerpenTab.visibility = if (effective == 0) View.VISIBLE else View.GONE
        newsTab.visibility = if (effective == 1) View.VISIBLE else View.GONE
        komikTab.visibility = if (effective == 2) View.VISIBLE else View.GONE
        koleksiTab.visibility = if (effective == 3) View.VISIBLE else View.GONE
        dockItems.forEach { styleDockItem(it, it.tab == effective, palette) }
    }

    private fun styleDockItem(item: DockItem, selected: Boolean, palette: ThemePalette) {
        // v1.1.14: aksen config menggantikan palette.accent HANYA untuk
        // label/ikon tab aktif di dock — warna lain tidak berubah.
        val color = if (selected) remoteAccent ?: palette.accent else palette.muted
        item.icon.setTextColor(color)
        item.label.setTextColor(color)
    }

    /** Bubble Cari (v1.1.11): fokus ke kolom cari TAB YANG SEDANG
     * AKTIF, scroll ke atas, tampilkan keyboard. Koleksi tidak punya
     * kolom cari — tetap lompat ke Cerpen seperti dulu. */
    private fun focusSearch() {
        val target: EditText = when (selectedTab) {
            1 -> searchNewsInput
            2 -> searchKomikInput
            else -> {
                if (selectedTab == 3) showTab(0)
                searchInput
            }
        }
        mainScroll.post {
            mainScroll.smoothScrollTo(0, 0)
            target.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Ketuk-ulang tab aktif (v1.1.11): scroll ke atas + refresh
     * konten (syncNow tanpa throttle, sama seperti pull-to-refresh).
     * Koleksi isinya lokal — scroll saja, tanpa sync. */
    private fun onTabReselected(tab: Int) {
        mainScroll.post { mainScroll.smoothScrollTo(0, 0) }
        when (tab) {
            0 -> { cerpenList.scrollToPosition(0); syncNow() }
            1 -> { newsList.scrollToPosition(0); syncNow() }
            2 -> { komikList.scrollToPosition(0); syncNow() }
            3 -> Unit
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
                applyNewsList()
                applyKomikList()
                showContinueReading()
                showHistoryAndBookmarks()
                refreshGlass()
                // v1.1.20: konten sudah ada — resolve tautan berbagi yang
                // menunggu (bila ada); gagal = diam di daftar biasa.
                pendingShareToken?.let { token ->
                    pendingShareToken = null
                    handleShareToken(token)
                }
            }
        }.start()
    }

    /** Daftar berita (difilter query cari bila ada) — v1.1.11. */
    private fun applyNewsList() {
        val q = newsQuery.lowercase()
        val filtered = if (q.isEmpty()) newsData else newsData.filter { a ->
            a.title.lowercase().contains(q) || a.source.lowercase().contains(q)
        }
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        newsList.adapter = NewsAdapter(
            filtered, { openNewsDetail(it) }, palette, scale,
            onDownload = { a, pos -> downloadNews(a, pos) }
        )
    }

    private fun applyKomikList() {
        val q = komikQuery.lowercase()
        val filtered = if (q.isEmpty()) komikData else komikData.filter { k ->
            k.judul.lowercase().contains(q) || k.genre.lowercase().contains(q)
        }
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        komikList.adapter = KomikAdapter(
            filtered, { openKomikDetail(it) }, palette, scale,
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

    /** Chip filter jenis (v1.1.12): Semua / Cerbung / Cergam. Genre hanya
     * memfilter cerpen — saat jenis = Cergam, baris genre disembunyikan. */
    private fun buildTypeChips() {
        typeRow.removeAllViews()
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        for (t in listOf("Semua", "Cerbung", "Cergam")) {
            val chip = Button(this).apply {
                text = t
                textSize = 13f
                isSingleLine = true
                minimumWidth = 0
                minimumHeight = 0
                val d = resources.displayMetrics.density
                setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                setTextColor(
                    if (t == selectedType) palette.onAccent
                    else palette.accent
                )
                setBackgroundColor(
                    if (t == selectedType) palette.accent
                    else palette.card
                )
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 8
                layoutParams = params
                setOnClickListener {
                    selectedType = t
                    buildTypeChips()
                    applyCerpenFilter()
                }
            }
            typeRow.addView(chip)
        }
    }

    /** Daftar Cerbung campur cerpen + cergam (v1.1.12), terbaru dulu. */
    private fun applyCerpenFilter() {
        val q = query.lowercase()
        val cerpenItems = if (selectedType == "Cergam") emptyList() else cerpenData.filter { c ->
            (selectedGenre == "Semua" || c.genre == selectedGenre) &&
                (q.isEmpty() || c.judul.lowercase().contains(q) || c.genre.lowercase().contains(q))
        }.map { CerbungItem.CerpenItem(it) }
        val komikItems = if (selectedType == "Cerbung") emptyList() else komikData.filter { k ->
            q.isEmpty() || k.judul.lowercase().contains(q) || k.genre.lowercase().contains(q)
        }.map { CerbungItem.CergamItem(it) }
        val mixed = (cerpenItems + komikItems).sortedWith(
            compareByDescending<CerbungItem> { it.date }
                .thenByDescending { ItemDates.trailingNumber(it.id) }
        )
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        cerpenList.adapter = CerbungAdapter(
            mixed, { openDetail(it) }, { openKomikDetail(it) }, palette, scale,
            onCerpenDownload = { c, pos -> downloadCerpen(c, pos) },
            onKomikDownload = { k, pos -> downloadKomik(k, pos) },
            newestCerpenId = newestCerpenId,
            newestKomikId = newestKomikId
        )
        genreScroll.visibility = if (selectedType == "Cergam") View.GONE else View.VISIBLE
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

    // ============ DEEP LINK TAUTAN BERBAGI (v1.1.20) ============
    // Tautan https://hozinking.my.id/s/<token> masuk lewat intent-filter
    // di manifest. Resolve di thread latar; gagal (404 / tanpa internet
    // / konten tidak ada) = diam di daftar biasa (fail-open, tanpa crash
    // dan tanpa dialog error). Berita dicari berdasarkan judul — sama
    // seperti kunci unduh offline-nya (NewsArticle tidak punya id).

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val token = ShareLink.extractTokenFromPath(intent.data?.path) ?: return
        if (cerpenData.isNotEmpty() || newsData.isNotEmpty() || komikData.isNotEmpty()) {
            handleShareToken(token)
        } else {
            pendingShareToken = token
        }
    }

    /** Resolve token lalu navigasi; gagal = tetap di daftar biasa. */
    private fun handleShareToken(token: String) {
        Thread {
            val resolved = ShareLink.resolveShare(token) ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                navigateResolved(resolved)
            }
        }.start()
    }

    private fun navigateResolved(r: ShareLink.ResolvedShare) {
        when (r.contentType) {
            "cerpen" -> {
                val c = cerpenData.firstOrNull { it.id == r.contentId } ?: return
                val season = r.season
                val episode = r.episodeNo
                if (season != null && episode != null && c.episode(season, episode) != null) {
                    openReader(c.id, season, episode)
                } else {
                    openDetail(c)
                }
            }
            "komik" -> {
                val k = komikData.firstOrNull { it.id == r.contentId } ?: return
                val episode = r.episodeNo
                if (episode != null && k.episode(episode) != null) {
                    val i = Intent(this, KomikReaderActivity::class.java)
                    i.putExtra(KomikReaderActivity.EXTRA_KOMIK_ID, k.id)
                    i.putExtra(KomikReaderActivity.EXTRA_EPISODE, episode)
                    startActivity(i)
                } else {
                    openKomikDetail(k)
                }
            }
            "berita" -> {
                val a = newsData.firstOrNull { it.title == r.contentId } ?: return
                openNewsDetail(a)
            }
            else -> Unit
        }
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

    /** Tandai satu proses sync sedang berjalan (ganti tombol yang dihapus). */
    private fun setSyncBusy(busy: Boolean) {
        syncBusy = busy
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
    // aksi pengguna. Throttle maks 1x per 30 menit (dicatat di prefs
    // yang sama dengan cek update aplikasi). Fail-open: tanpa internet
    // atau gagal = diam, konten lama tetap bisa dibaca. Toast HANYA
    // bila ada konten baru — bila sudah terbaru, diam.
    private fun autoSyncContent() {
        // Jangan tumpuk dengan sync manual yang sedang berjalan.
        if (syncBusy) return
        val now = System.currentTimeMillis()
        val last = updatePrefs.getLong("last_auto_sync_ms", 0)
        if (now - last < 30 * 60 * 1000L) return
        updatePrefs.edit().putLong("last_auto_sync_ms", now).apply()
        setSyncBusy(true)
        DataManager.syncContent(this, onProgress = { done, total, label ->
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
        // v1.1.14: pull-to-refresh juga me-refresh remote config
        // (non-blocking, sejajar dengan sync konten — tetap jalan walau
        // sync konten sedang sibuk).
        fetchRemoteConfig()
        // Cegah sync ganda (auto + tarik bersamaan).
        if (syncBusy) return
        setSyncBusy(true)
        swipeRefresh.isRefreshing = true
        DataManager.syncContent(this, onProgress = { done, total, label ->
            showSyncProgress(done, total, label)
        }) { changed ->
            setSyncBusy(false)
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

    // ============ AUTO-UPDATE APLIKASI (v1.1.5) ============
    // Sumber: GitHub Releases API (otomatis, tanpa file versi manual).
    // Fail-open: cek gagal (tanpa internet / API error) = diam, aplikasi
    // tetap jalan normal. Dialog ini HANYA update kode — konten di-sync
    // DataManager dan bisa update tanpa update APK.

    /**
     * Cek update MANUAL (v1.1.15, tombol "Periksa Pembaruan" di Pengaturan):
     * lewati throttle 6 jam + abaikan dismissed_tag. Hasil SELALU
     * diberitahu: ada update -> dialog seperti biasa; sudah terbaru ->
     * toast; gagal jaringan/respons aneh -> toast gagal (jangan diam).
     * Throttle auto-check 6 jam TIDAK diubah (last_check_ms tetap
     * ditulis agar auto-check tidak langsung mengulang).
     */
    private fun checkForAppUpdateManual() {
        val installed = BuildConfig.VERSION_NAME
        updatePrefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
        Thread {
            val latestJson = UpdateCheck.fetchText(UpdateCheck.LATEST_RELEASE_URL)
            if (latestJson == null) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal memeriksa pembaruan — periksa internet lalu coba lagi", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val latest = UpdateCheck.parseLatestRelease(latestJson)
            if (latest == null) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal memeriksa pembaruan — respons tidak dikenal", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            // v1.1.23: signer tidak cocok = paksa update meski versi
            // mengklaim terbaru (jangan toast "Sudah versi terbaru").
            if (isSignerMismatch()) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showUpdateDialog(UpdateCheck.Decision.SignerMismatch(latest))
                }
                return@Thread
            }
            if (UpdateCheck.compareVersions(latest.version, installed) <= 0) {
                runOnUiThread {
                    Toast.makeText(this, "Sudah versi terbaru (v$installed)", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            val tagsJson = UpdateCheck.fetchText(UpdateCheck.RELEASES_URL)
            val tags = if (tagsJson == null) emptyList() else UpdateCheck.parseReleaseTags(tagsJson)
            val decision = UpdateCheck.decide(installed, latest, tags)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (decision is UpdateCheck.Decision.Force) {
                    updatePrefs.edit()
                        .putString("force_tag", decision.release.tag)
                        .putString("force_apk_url", decision.release.apkUrl)
                        .apply()
                }
                showUpdateDialog(decision)
            }
        }.start()
    }

    /** Cek update: onCreate + onResume, throttle maks 1x per 6 jam. */
    private fun checkForAppUpdate() {
        val installed = BuildConfig.VERSION_NAME
        // v1.1.23: pulihkan dialog yang tertunda (rotasi / proses mati).
        if (restorePendingUpdateDialog(installed)) return
        // v1.1.23: cek SHA signer lokal (tanpa jaringan). Bila tidak
        // cocok, throttle dilewati — mismatch harus dicek tiap buka.
        val signerMismatch = isSignerMismatch()
        // Force yang tertunda (tersimpan di prefs) tampil lagi tanpa jaringan.
        val forceTag = updatePrefs.getString("force_tag", null)
        val forceUrl = updatePrefs.getString("force_apk_url", null)
        if (forceTag != null && UpdateCheck.compareVersions(forceTag, installed) > 0) {
            val release = UpdateCheck.ReleaseInfo(
                forceTag, forceTag.removePrefix("v").removePrefix("V"), forceUrl
            )
            showUpdateDialog(UpdateCheck.Decision.Force(release))
            return
        }
        val now = System.currentTimeMillis()
        val last = updatePrefs.getLong("last_check_ms", 0)
        if (!signerMismatch && now - last < 6 * 60 * 60 * 1000L) return
        updatePrefs.edit().putLong("last_check_ms", now).apply()
        Thread {
            val latestJson = UpdateCheck.fetchText(UpdateCheck.LATEST_RELEASE_URL)
            val latest = latestJson?.let { UpdateCheck.parseLatestRelease(it) }
            val decision = when {
                // Signer salah = paksa update apa pun kata versi. Tanpa
                // jaringan, rilis sintetis (tanpa apkUrl) — tombol Update
                // membuka halaman rilis lewat browser.
                signerMismatch -> UpdateCheck.Decision.SignerMismatch(
                    latest ?: UpdateCheck.ReleaseInfo("mismatch", installed, null)
                )
                latest == null -> UpdateCheck.Decision.None
                else -> {
                    val tagsJson = UpdateCheck.fetchText(UpdateCheck.RELEASES_URL)
                    val tags = if (tagsJson == null) emptyList() else UpdateCheck.parseReleaseTags(tagsJson)
                    UpdateCheck.decide(installed, latest, tags)
                }
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
                            .apply()
                        showUpdateDialog(decision)
                    }
                    is UpdateCheck.Decision.SignerMismatch -> showUpdateDialog(decision)
                }
            }
        }.start()
    }

    /** v1.1.23: true bila SHA signer terpasang bisa dibaca DAN tidak cocok resmi. */
    private fun isSignerMismatch(): Boolean {
        val sha = AppUpdate.installedSignerSha256(this) ?: return false
        return !UpdateCheck.signerMatches(sha)
    }

    /** v1.1.23: hapus penanda dialog update yang tertunda. */
    private fun clearPendingUpdate() {
        updatePrefs.edit()
            .remove("pending_update_tag")
            .remove("pending_update_version")
            .remove("pending_update_url")
            .remove("pending_update_kind")
            .apply()
    }

    /**
     * v1.1.23: tampilkan lagi dialog update yang tertunda (rotasi /
     * proses mati). True bila dialog ditampilkan — pemanggil tidak perlu
     * cek lanjut. False bila tidak ada yang tertunda / sudah tidak relevan.
     */
    private fun restorePendingUpdateDialog(installed: String): Boolean {
        val tag = updatePrefs.getString("pending_update_tag", null) ?: return false
        val version = updatePrefs.getString("pending_update_version", null) ?: return false
        val url = updatePrefs.getString("pending_update_url", null)
        val kind = updatePrefs.getString("pending_update_kind", null) ?: return false
        if (kind == "mismatch") {
            // Tanda tangan masih salah? Bila sudah resmi, hapus penanda.
            if (!isSignerMismatch()) {
                clearPendingUpdate()
                return false
            }
            showUpdateDialog(
                UpdateCheck.Decision.SignerMismatch(UpdateCheck.ReleaseInfo(tag, version, url))
            )
            return true
        }
        if (UpdateCheck.compareVersions(installed, version) >= 0) {
            clearPendingUpdate()
            return false
        }
        if (kind == "soft" && updatePrefs.getString("dismissed_tag", null) == tag) {
            clearPendingUpdate()
            return false
        }
        val release = UpdateCheck.ReleaseInfo(tag, version, url)
        showUpdateDialog(
            if (kind == "force") UpdateCheck.Decision.Force(release)
            else UpdateCheck.Decision.Soft(release)
        )
        return true
    }

    /** Tampilkan dialog update kaca iOS (biasa, paksa, atau signer mismatch). */
    private fun showUpdateDialog(decision: UpdateCheck.Decision) {
        if (updateDialog?.isShowing == true) return
        val (release, kind) = when (decision) {
            is UpdateCheck.Decision.Soft -> decision.release to "soft"
            is UpdateCheck.Decision.Force -> decision.release to "force"
            is UpdateCheck.Decision.SignerMismatch -> decision.release to "mismatch"
            else -> return
        }
        val forced = kind != "soft"
        val title = when (kind) {
            "force" -> "Update wajib"
            "mismatch" -> "Aplikasi tidak resmi"
            else -> "Versi baru tersedia"
        }
        val message = when (kind) {
            "force" -> "Cerpen Hozin v${release.version} sudah tersedia dan versi kamu " +
                "sudah tertinggal 3 rilis. Perbarui dulu untuk lanjut memakai aplikasi."
            "mismatch" -> "Aplikasi yang terpasang tidak ditandatangani oleh " +
                "pengembang resmi Cerpen Hozin. Instal ulang dari rilis resmi " +
                "agar tetap aman dan bisa menerima update."
            else -> "Cerpen Hozin v${release.version} sudah tersedia " +
                "(versi kamu v${BuildConfig.VERSION_NAME}). " +
                "Perbarui untuk fitur terbaru dan perbaikan."
        }
        // Simpan penanda agar dialog tampil lagi setelah rotasi / proses mati.
        updatePrefs.edit()
            .putString("pending_update_tag", release.tag)
            .putString("pending_update_version", release.version)
            .putString("pending_update_url", release.apkUrl)
            .putString("pending_update_kind", kind)
            .apply()
        val dialog = GlassDialog.create(
            context = this,
            title = title,
            message = message,
            negative = if (forced) null else GlassDialog.Button("Nanti", accent = false) {
                updatePrefs.edit().putString("dismissed_tag", release.tag).apply()
                clearPendingUpdate()
            },
            positive = GlassDialog.Button(if (forced) "Update Sekarang" else "Update", accent = true) {
                clearPendingUpdate()
                startApkDownload(release)
            }
        )
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
            AppUpdate.openInBrowser(this, null)
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
                    AppUpdate.openInBrowser(this, url)
                }
            }
        }.start()
    }

    /** Instal APK terunduh; bila belum diizinkan, arahkan ke Settings. */
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
        if (!AppUpdate.installApk(this, apk)) {
            Toast.makeText(this, "Tidak bisa membuka installer — dibuka lewat browser", Toast.LENGTH_LONG).show()
            AppUpdate.openInBrowser(this, release.apkUrl)
        }
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

package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Konten yang sudah dibeli (v1.1.32): daftar unlock milik akun dari
 * server (GET /api/cerpen-coins/history — sumber kebenaran, bukan
 * file lokal saja). Judul diselesaikan lokal dari katalog; ketuk baris
 * membuka reader konten itu (lewat gerbang koin — baris di sini sudah
 * pasti dimiliki, jadi gerbang lolos dari cache).
 * Offline: daftar unlock dari cache perangkat (CoinStore).
 */
class PurchasedActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var titleText: TextView
    private lateinit var boughtList: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var offlineNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchased)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Konten Dibeli"

        root = findViewById(R.id.purchased_root)
        titleText = findViewById(R.id.purchased_title)
        boughtList = findViewById(R.id.purchased_list)
        emptyText = findViewById(R.id.purchased_empty)
        offlineNote = findViewById(R.id.purchased_offline_note)

        refreshTheme()
        load()
    }

    private fun refreshTheme() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        root.setBackgroundColor(palette.bg)
        titleText.setTextColor(palette.titleInk)
        emptyText.setTextColor(palette.muted)
        offlineNote.setTextColor(palette.muted)
    }

    private fun load() {
        // Sinkron unlock dulu supaya gerbang di baris daftar akurat.
        CoinUi.refreshMeAsync(this)
        Thread {
            val history = CoinApi.fetchHistory(this)
            val cerpenTitles = DataManager.loadCerpen(this).associate { it.id to it.judul }
            val komikTitles = DataManager.loadKomik(this).associate { it.id to it.judul }
            val newsTitles = DataManager.loadNews(this).associate { it.id to it.title }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (history == null) {
                    // Offline / gagal: unlock dari cache perangkat.
                    offlineNote.visibility = View.VISIBLE
                    showCachedUnlocks(cerpenTitles, komikTitles, newsTitles)
                } else {
                    offlineNote.visibility = View.GONE
                    showUnlocks(history.unlocks, cerpenTitles, komikTitles, newsTitles)
                }
            }
        }.start()
    }

    private fun titleFor(
        kind: String,
        targetId: String,
        cerpenTitles: Map<String, String>,
        komikTitles: Map<String, String>,
        newsTitles: Map<String, String>
    ): String {
        return when (kind) {
            "komik" -> komikTitles[targetId] ?: targetId
            "berita" -> newsTitles[targetId] ?: targetId
            else -> cerpenTitles[targetId] ?: targetId
        }
    }

    private fun openedLabel(createdAtRaw: Long): String {
        if (createdAtRaw <= 0) return "Dibuka"
        val ms = if (createdAtRaw < 1_000_000_000_000L) createdAtRaw * 1000 else createdAtRaw
        return try {
            "Dibuka " + SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date(ms))
        } catch (e: Exception) {
            "Dibuka"
        }
    }

    private fun showUnlocks(
        unlocks: List<CoinApi.HistoryUnlock>,
        cerpenTitles: Map<String, String>,
        komikTitles: Map<String, String>,
        newsTitles: Map<String, String>
    ) {
        boughtList.removeAllViews()
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        for (u in unlocks) {
            val title = titleFor(u.kind, u.targetId, cerpenTitles, komikTitles, newsTitles)
            val seasonPart = if (u.kind == "cerpen" && u.season > 1) " · S${u.season}" else ""
            addRow(boughtList, "$title$seasonPart · Episode ${u.no}", openedLabel(u.createdAtRaw), palette) {
                openOwned(u.kind, u.targetId, u.season, u.no)
            }
        }
        emptyText.visibility = if (unlocks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCachedUnlocks(
        cerpenTitles: Map<String, String>,
        komikTitles: Map<String, String>,
        newsTitles: Map<String, String>
    ) {
        boughtList.removeAllViews()
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val keys = CoinStore.snapshot(this).unlocks
        for (key in keys) {
            val parts = key.split(":")
            if (parts.size != 4) continue
            val kind = parts[0]
            val targetId = parts[1]
            val season = parts[2].toIntOrNull() ?: 1
            val no = parts[3].toIntOrNull() ?: 0
            val title = titleFor(kind, targetId, cerpenTitles, komikTitles, newsTitles)
            val seasonPart = if (kind == "cerpen" && season > 1) " · S$season" else ""
            addRow(boughtList, "$title$seasonPart · Episode $no", "Dibuka", palette) {
                openOwned(kind, targetId, season, no)
            }
        }
        emptyText.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addRow(
        parent: LinearLayout,
        title: String,
        meta: String,
        palette: ThemePalette,
        onClick: () -> Unit
    ) {
        val row = TextView(this).apply {
            text = "$title\n$meta"
            setTextColor(palette.ink)
            textSize = 15f
            setPadding(12, 12, 12, 12)
            background = ThemeHelper.cardBackground(this@PurchasedActivity, palette)
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 8
        parent.addView(row, params)
    }

    private fun openOwned(kind: String, targetId: String, season: Int, no: Int) {
        CoinUi.openEpisode(this, kind, targetId, season, no, locallyRead = true) {
            when (kind) {
                "komik" -> {
                    val i = Intent(this, KomikReaderActivity::class.java)
                    i.putExtra(KomikReaderActivity.EXTRA_KOMIK_ID, targetId)
                    i.putExtra(KomikReaderActivity.EXTRA_EPISODE, no)
                    startActivity(i)
                }
                "berita" -> {
                    val a = DataManager.loadNews(this).firstOrNull { it.id == targetId }
                    if (a != null) {
                        val i = Intent(this, NewsDetailActivity::class.java)
                        i.putExtra(NewsDetailActivity.EXTRA_TITLE, a.title)
                        i.putExtra(NewsDetailActivity.EXTRA_SOURCE, a.source)
                        i.putExtra(NewsDetailActivity.EXTRA_DATE, a.date)
                        i.putExtra(NewsDetailActivity.EXTRA_SUMMARY, a.summary)
                        i.putExtra(NewsDetailActivity.EXTRA_DETAILS, a.details)
                        i.putStringArrayListExtra(NewsDetailActivity.EXTRA_IMAGES, ArrayList(a.images))
                        i.putStringArrayListExtra(NewsDetailActivity.EXTRA_IMAGE_URLS, ArrayList(a.imageUrls))
                        i.putExtra(NewsDetailActivity.EXTRA_ADDED_AT, a.addedAt)
                        i.putExtra(NewsDetailActivity.EXTRA_ID, a.id)
                        startActivity(i)
                    }
                }
                else -> {
                    val i = Intent(this, ReaderActivity::class.java)
                    i.putExtra(ReaderActivity.EXTRA_CERPEN_ID, targetId)
                    i.putExtra(ReaderActivity.EXTRA_SEASON, season)
                    i.putExtra(ReaderActivity.EXTRA_EPISODE, no)
                    startActivity(i)
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Riwayat konten (v1.1.30): yang sudah dibuka (unlock server) + terakhir
 * dibaca (pelacak v81). Judul diambil dari katalog lokal (DataManager).
 * Tap baris -> buka reader lewat gerbang koin (baris di sini sudah pasti
 * dimiliki/dibaca, jadi gerbang lolos dari cache).
 * Offline: tampilkan riwayat lokal perangkat (ProgressStore).
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var boughtList: LinearLayout
    private lateinit var readList: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var offlineNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Riwayat"

        root = findViewById(R.id.history_root)
        boughtList = findViewById(R.id.history_bought_list)
        readList = findViewById(R.id.history_read_list)
        emptyText = findViewById(R.id.history_empty)
        offlineNote = findViewById(R.id.history_offline_note)

        refreshTheme()
        load()
    }

    private fun refreshTheme() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        root.setBackgroundColor(palette.bg)
    }

    private fun load() {
        // Sinkron unlock dulu supaya gerbang di baris riwayat akurat.
        CoinUi.refreshMeAsync(this)
        Thread {
            val history = CoinApi.fetchHistory(this)
            val cerpenTitles = DataManager.loadCerpen(this).associate { it.id to it.judul }
            val komikTitles = DataManager.loadKomik(this).associate { it.id to it.judul }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (history == null) {
                    // Offline / gagal: riwayat lokal perangkat.
                    offlineNote.visibility = View.VISIBLE
                    showLocalHistory(cerpenTitles)
                } else {
                    offlineNote.visibility = View.GONE
                    showServerHistory(history, cerpenTitles, komikTitles)
                }
            }
        }.start()
    }

    private fun titleFor(kind: String, targetId: String, cerpenTitles: Map<String, String>, komikTitles: Map<String, String>): String {
        return when (kind) {
            "komik" -> komikTitles[targetId] ?: targetId
            else -> cerpenTitles[targetId] ?: targetId
        }
    }

    private fun showServerHistory(
        history: CoinApi.HistoryResult,
        cerpenTitles: Map<String, String>,
        komikTitles: Map<String, String>
    ) {
        boughtList.removeAllViews()
        readList.removeAllViews()
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))

        for (u in history.unlocks.take(30)) {
            val title = titleFor(u.kind, u.targetId, cerpenTitles, komikTitles)
            val seasonPart = if (u.kind == "cerpen" && u.season > 1) " · S${u.season}" else ""
            addRow(boughtList, "$title$seasonPart · Episode ${u.no}", "Dibuka", palette) {
                openOwned(u.kind, u.targetId, u.season, u.no)
            }
        }
        for (r in history.reading.take(30)) {
            val title = titleFor(r.kind, r.targetId, cerpenTitles, komikTitles)
            val seasonPart = if (r.kind == "cerpen" && r.season > 1) " · S${r.season}" else ""
            val mins = (r.seconds / 60).coerceAtLeast(1)
            val dayPart = if (r.lastDay.isNotBlank()) " · ${r.lastDay}" else ""
            addRow(readList, "$title$seasonPart · Episode ${r.no}", "${mins} mnt$dayPart", palette) {
                openOwned(r.kind, r.targetId, r.season, r.no)
            }
        }
        emptyText.visibility =
            if (history.unlocks.isEmpty() && history.reading.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showLocalHistory(cerpenTitles: Map<String, String>) {
        boughtList.removeAllViews()
        readList.removeAllViews()
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val local = ProgressStore.getHistory(this)
        for (h in local) {
            val title = cerpenTitles[h.cerpenId] ?: h.cerpenId
            val seasonPart = if (h.season > 1) " · S${h.season}" else ""
            addRow(readList, "$title$seasonPart · Episode ${h.episode}", "Dibaca di perangkat ini", palette) {
                openOwned("cerpen", h.cerpenId, h.season, h.episode)
            }
        }
        emptyText.visibility = if (local.isEmpty()) View.VISIBLE else View.GONE
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
            background = ThemeHelper.cardBackground(this@HistoryActivity, palette)
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
            if (kind == "komik") {
                val i = Intent(this, KomikReaderActivity::class.java)
                i.putExtra(KomikReaderActivity.EXTRA_KOMIK_ID, targetId)
                i.putExtra(KomikReaderActivity.EXTRA_EPISODE, no)
                startActivity(i)
            } else {
                val i = Intent(this, ReaderActivity::class.java)
                i.putExtra(ReaderActivity.EXTRA_CERPEN_ID, targetId)
                i.putExtra(ReaderActivity.EXTRA_SEASON, season)
                i.putExtra(ReaderActivity.EXTRA_EPISODE, no)
                startActivity(i)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

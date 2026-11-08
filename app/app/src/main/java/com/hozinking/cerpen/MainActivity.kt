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

class MainActivity : AppCompatActivity() {

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
    private lateinit var loading: ProgressBar
    private lateinit var refreshButton: Button

    private var cerpenData: List<Cerpen> = emptyList()
    private var newsData: List<NewsArticle> = emptyList()
    private var query: String = ""
    private var selectedGenre: String = "Semua"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        loading = findViewById(R.id.loading)
        refreshButton = findViewById(R.id.refresh_button)

        cerpenList.layoutManager = LinearLayoutManager(this)
        historyList.layoutManager = LinearLayoutManager(this)
        bookmarkList.layoutManager = LinearLayoutManager(this)
        newsList.layoutManager = LinearLayoutManager(this)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim() ?: ""
                applyCerpenFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshButton.setOnClickListener { syncNow() }

        loadContent()
        // Sync HANYA manual (tombol Perbarui) — hemat baterai, tanpa proses background.
    }

    override fun onResume() {
        super.onResume()
        showContinueReading()
        showHistoryAndBookmarks()
        if (cerpenData.isNotEmpty()) {
            applyCerpenFilter()
        }
    }

    private fun loadContent() {
        loading.visibility = View.VISIBLE
        Thread {
            val cerpen = DataManager.loadCerpen(this)
            val news = DataManager.loadNews(this)
            runOnUiThread {
                cerpenData = cerpen
                newsData = news
                loading.visibility = View.GONE
                buildGenreChips()
                applyCerpenFilter()
                newsList.adapter = NewsAdapter(news)
                showContinueReading()
                showHistoryAndBookmarks()
            }
        }.start()
    }

    private fun buildGenreChips() {
        genreRow.removeAllViews()
        val genres = listOf("Semua") + cerpenData.map { it.genre }.distinct()
        for (g in genres) {
            val chip = Button(this).apply {
                text = g
                textSize = 13f
                setTextColor(
                    if (g == selectedGenre) getColor(android.R.color.white)
                    else getColor(R.color.accent)
                )
                setBackgroundColor(
                    if (g == selectedGenre) getColor(R.color.accent)
                    else getColor(R.color.card)
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
        cerpenList.adapter = CerpenAdapter(filtered) { openDetail(it) }
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
            historyList.adapter = HistoryAdapter(history) { c, s, e -> openReader(c.id, s, e.no) }
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
            bookmarkList.adapter = HistoryAdapter(marks) { c, s, e -> openReader(c.id, s, e.no) }
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

    private fun syncNow() {
        refreshButton.isEnabled = false
        refreshButton.text = "Memperbarui..."
        DataManager.syncFromGithub(this) { changed ->
            refreshButton.isEnabled = true
            refreshButton.text = "Perbarui Konten"
            if (changed) {
                loadContent()
                Toast.makeText(this, "Konten diperbarui", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Sudah terbaru / tidak ada internet", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

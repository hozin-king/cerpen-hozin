package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CERPEN_ID = "cerpen_id"
    }

    private lateinit var cover: ImageView
    private lateinit var judul: TextView
    private lateinit var genre: TextView
    private lateinit var meta: TextView
    private lateinit var deskripsi: TextView
    private lateinit var episodeList: RecyclerView
    private lateinit var loading: ProgressBar

    private var cerpen: Cerpen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        cover = findViewById(R.id.detail_cover)
        judul = findViewById(R.id.detail_judul)
        genre = findViewById(R.id.detail_genre)
        meta = findViewById(R.id.detail_meta)
        deskripsi = findViewById(R.id.detail_deskripsi)
        episodeList = findViewById(R.id.episode_list)
        loading = findViewById(R.id.loading)

        episodeList.layoutManager = LinearLayoutManager(this)

        val id = intent.getStringExtra(EXTRA_CERPEN_ID) ?: run { finish(); return }
        loading.visibility = View.VISIBLE
        Thread {
            val list = DataManager.loadCerpen(this)
            val found = list.firstOrNull { it.id == id }
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

    override fun onResume() {
        super.onResume()
        // Refresh status "sudah dibaca" tiap kembali dari Reader.
        cerpen?.let {
            episodeList.adapter = EpisodeAdapter(it) { season, ep -> openReader(it.id, season, ep.no) }
        }
    }

    private fun bind(c: Cerpen) {
        supportActionBar?.title = c.judul
        judul.text = c.judul
        genre.text = c.genre
        meta.text = "${c.totalEpisodes} episode · ${c.seasons.size} season"
        deskripsi.text = c.deskripsi
        val bmp = if (c.cover.isNotEmpty()) {
            CoverCache.get(this, "covers/${c.cover}", 600)
        } else null
        if (bmp != null) {
            cover.setImageBitmap(bmp)
        } else {
            cover.setImageResource(R.drawable.cover_placeholder)
        }
        episodeList.adapter = EpisodeAdapter(c) { season, ep -> openReader(c.id, season, ep.no) }
    }

    private fun openReader(cerpenId: String, season: Int, episode: Int) {
        val i = Intent(this, ReaderActivity::class.java)
        i.putExtra(ReaderActivity.EXTRA_CERPEN_ID, cerpenId)
        i.putExtra(ReaderActivity.EXTRA_SEASON, season)
        i.putExtra(ReaderActivity.EXTRA_EPISODE, episode)
        startActivity(i)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

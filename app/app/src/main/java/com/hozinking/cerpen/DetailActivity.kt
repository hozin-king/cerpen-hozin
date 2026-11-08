package com.hozinking.cerpen

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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

    private lateinit var root: View
    private lateinit var cover: ImageView
    private lateinit var judul: TextView
    private lateinit var genre: TextView
    private lateinit var meta: TextView
    private lateinit var deskripsi: TextView
    private lateinit var settingsButton: Button
    private lateinit var episodeList: RecyclerView
    private lateinit var loading: ProgressBar

    private var cerpen: Cerpen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        root = findViewById(R.id.detail_root)
        cover = findViewById(R.id.detail_cover)
        judul = findViewById(R.id.detail_judul)
        genre = findViewById(R.id.detail_genre)
        meta = findViewById(R.id.detail_meta)
        deskripsi = findViewById(R.id.detail_deskripsi)
        settingsButton = findViewById(R.id.detail_settings_button)
        episodeList = findViewById(R.id.episode_list)
        loading = findViewById(R.id.loading)

        episodeList.layoutManager = LinearLayoutManager(this)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        applyThemeAndFont()

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
        applyThemeAndFont()
        // Refresh status "sudah dibaca" tiap kembali dari Reader.
        cerpen?.let {
            val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
            val scale = ThemeHelper.fontScale(this)
            episodeList.adapter = EpisodeAdapter(it, { season, ep -> openReader(it.id, season, ep.no) }, palette, scale)
        }
    }

    /** Terapkan tema + ukuran font global ke layar ini. */
    private fun applyThemeAndFont() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val scale = ThemeHelper.fontScale(this)
        root.setBackgroundColor(palette.bg)
        judul.setTextColor(palette.titleInk)
        genre.setTextColor(palette.accent)
        meta.setTextColor(palette.muted)
        deskripsi.setTextColor(palette.inkSoft)
        deskripsi.textSize = 15f * scale
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
        episodeList.adapter = EpisodeAdapter(c, { season, ep -> openReader(c.id, season, ep.no) }, ThemeHelper.palette(ProgressStore.getTheme(this)), ThemeHelper.fontScale(this))
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

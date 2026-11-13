package com.hozinking.cerpen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Papan peringkat waktu baca (v1.1.30) — publik, tanpa login.
 * Hanya username + menit yang tampil (server tidak pernah mengirim
 * data sensitif; akun reading_private tidak masuk daftar).
 */
class LeaderboardActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var weeklyButton: Button
    private lateinit var allButton: Button
    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView

    private var period = "weekly"
    private val entries = ArrayList<CoinApi.LeaderboardEntry>()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Papan Peringkat"

        root = findViewById(R.id.leaderboard_root)
        weeklyButton = findViewById(R.id.leaderboard_weekly)
        allButton = findViewById(R.id.leaderboard_all)
        list = findViewById(R.id.leaderboard_list)
        emptyText = findViewById(R.id.leaderboard_empty)

        adapter = Adapter(entries)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        weeklyButton.setOnClickListener { setPeriod("weekly") }
        allButton.setOnClickListener { setPeriod("all") }

        refresh()
        load()
    }

    private fun setPeriod(p: String) {
        if (period == p) return
        period = p
        refresh()
        load()
    }

    private fun refresh() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        root.setBackgroundColor(palette.bg)
        stylePeriodButton(weeklyButton, period == "weekly", palette)
        stylePeriodButton(allButton, period == "all", palette)
    }

    private fun stylePeriodButton(button: Button, selected: Boolean, palette: ThemePalette) {
        if (selected) {
            button.setBackgroundColor(palette.accent)
            button.setTextColor(palette.onAccent)
        } else {
            button.setBackgroundColor(palette.card)
            button.setTextColor(palette.accent)
        }
    }

    private fun load() {
        Thread {
            val data = CoinApi.fetchLeaderboard(this, period)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                entries.clear()
                entries.addAll(data)
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
                if (data.isEmpty()) {
                    Toast.makeText(this, "Tidak bisa memuat peringkat — periksa internet", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private inner class Adapter(
        private val items: List<CoinApi.LeaderboardEntry>
    ) : RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val rank: TextView = v.findViewById(R.id.lb_rank)
            val username: TextView = v.findViewById(R.id.lb_username)
            val minutes: TextView = v.findViewById(R.id.lb_minutes)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_leaderboard, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            val palette = ThemeHelper.palette(ProgressStore.getTheme(this@LeaderboardActivity))
            h.rank.text = e.rank.toString()
            h.rank.setTextColor(palette.accent)
            h.username.text = e.username
            h.username.setTextColor(palette.ink)
            h.minutes.text = if (e.minutes >= 60) {
                "${e.minutes / 60} jam ${e.minutes % 60} mnt"
            } else {
                "${e.minutes} mnt"
            }
            h.minutes.setTextColor(palette.muted)
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

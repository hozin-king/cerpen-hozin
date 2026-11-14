package com.hozinking.cerpen

import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Baris sosial di bawah/di atas konten reader (v1.1.31): total view,
 * tombol Like (ikon jempol SVG + angka) + Dislike (ikon jempol-bawah
 * SVG, TANPA angka — ala YouTube) + Laporkan.
 *
 * Perilaku: POST view SEKALI saat reader dibuka (bind kedua dari
 * activity yang sama — mis. muat ulang episode komik setelah unduh —
 * tidak menghitung view lagi); vote/report update di tempat dari
 * respons server tanpa reload activity; dislike tanpa login membuka
 * AccountActivity, bukan menampilkan error mentah.
 */
class SocialBar(
    private val activity: AppCompatActivity,
    private val kind: String,
    private val ref: String
) {
    private var viewsText: TextView? = null
    private var likeButton: Button? = null
    private var dislikeButton: Button? = null
    private var reportButton: Button? = null
    private var stats: SocialApi.Stats? = null
    private var viewPosted = false

    /** Kategori lapor (value server + label Indonesia). */
    private val categories = listOf(
        "typo" to "Typo/kesalahan teks",
        "gambar" to "Gambar aneh/rusak",
        "tidak_pantas" to "Konten tidak pantas",
        "lainnya" to "Lainnya"
    )

    /** Pasang listener + POST view sekali. Aman dipanggil ulang (no-op). */
    fun bind() {
        if (viewPosted) return
        viewPosted = true
        viewsText = activity.findViewById(R.id.social_views)
        likeButton = activity.findViewById(R.id.social_like)
        dislikeButton = activity.findViewById(R.id.social_dislike)
        reportButton = activity.findViewById(R.id.social_report)
        likeButton?.setOnClickListener { onVote(if (stats?.myVote == 1) 0 else 1) }
        dislikeButton?.setOnClickListener {
            if (!CoinStore.hasSession(activity)) {
                Toast.makeText(activity, "Dislike butuh login — silakan masuk dulu", Toast.LENGTH_SHORT).show()
                activity.startActivity(Intent(activity, AccountActivity::class.java))
                return@setOnClickListener
            }
            onVote(if (stats?.myVote == -1) 0 else -1)
        }
        reportButton?.setOnClickListener { showReportDialog() }
        Thread {
            val s = SocialApi.recordView(activity, kind, ref)
            activity.runOnUiThread { if (s != null) { stats = s; applyStats() } }
        }.start()
    }

    /** Vote (1/-1/0 = batal); like boleh guest, dislike wajib login. */
    private fun onVote(vote: Int) {
        val like = likeButton ?: return
        like.isEnabled = false
        dislikeButton?.isEnabled = false
        Thread {
            val r = SocialApi.vote(activity, kind, ref, vote)
            activity.runOnUiThread {
                like.isEnabled = true
                dislikeButton?.isEnabled = true
                if (r.status in 200..299) {
                    SocialApi.parseStats(r.json)?.let { stats = it; applyStats() }
                } else if (r.isLoginRequired) {
                    // Sesi habis / belum login: arahkan ke layar login.
                    Toast.makeText(activity, "Dislike butuh login — silakan masuk dulu", Toast.LENGTH_SHORT).show()
                    activity.startActivity(Intent(activity, AccountActivity::class.java))
                } else {
                    Toast.makeText(activity, "Gagal, coba lagi", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Terapkan angka + tandai my_vote di tombol (tebal + penuh; lawan meredup). */
    private fun applyStats() {
        val s = stats ?: return
        viewsText?.text = SocialApi.formatCount(s.views)
        likeButton?.apply {
            text = SocialApi.formatCount(s.likes)
            isSelected = s.myVote == 1
            typeface = if (s.myVote == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            alpha = if (s.myVote == 1 || s.myVote == 0) 1f else 0.55f
        }
        dislikeButton?.apply {
            isSelected = s.myVote == -1
            alpha = if (s.myVote == -1) 1f else 0.55f
        }
    }

    /** Dialog lapor: 4 kategori + catatan opsional. Guest boleh lapor. */
    private fun showReportDialog() {
        val ctx = activity
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var selected = -1
        val group = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            setOnCheckedChangeListener { g, checkedId ->
                selected = (0 until g.childCount).firstOrNull { g.getChildAt(it).id == checkedId } ?: -1
            }
        }
        for ((_, label) in categories) {
            group.addView(RadioButton(ctx).apply { text = label; textSize = 15f })
        }
        val note = EditText(ctx).apply {
            hint = "Catatan (opsional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(group)
            addView(note)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Laporkan konten")
            .setView(root)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Kirim", null)
            .create()
        // Positive disadap: tanpa kategori, dialog tetap terbuka + toast.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected < 0) {
                    Toast.makeText(ctx, "Pilih kategori dulu", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val category = categories[selected].first
                dialog.dismiss()
                sendReport(category, note.text.toString().trim())
            }
        }
        dialog.show()
    }

    private fun sendReport(category: String, noteText: String) {
        Thread {
            val r = SocialApi.sendReport(activity, kind, ref, category, noteText)
            activity.runOnUiThread {
                when {
                    r.status in 200..299 ->
                        Toast.makeText(activity, "Laporan terkirim, terima kasih", Toast.LENGTH_SHORT).show()
                    r.status == 429 || r.error == "already_reported" ->
                        Toast.makeText(activity, "Kamu sudah melaporkan konten ini hari ini", Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(activity, "Gagal mengirim laporan, coba lagi", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

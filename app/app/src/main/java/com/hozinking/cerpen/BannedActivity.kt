package com.hozinking.cerpen

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Layar banned (paket admin) — tampil otomatis bila server menyatakan
 * perangkat/akun ini di-ban, baik saat buka aplikasi maupun selagi
 * membaca (heartbeat). Tidak bisa ditutup dengan tombol kembali;
 * satu-satunya jalan keluar adalah tombol "Tutup Aplikasi" (lalu
 * buka lagi = gate cek ulang ke server). Bila activity ini gagal
 * dibuka, AdminEnforcer mematikan proses sebagai darurat.
 */
class BannedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "banned_reason"
        const val EXTRA_EXPIRES_AT_MS = "banned_expires_at_ms"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banned)

        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        val root = findViewById<View>(R.id.banned_root)
        root.setBackgroundColor(palette.bg)
        val title = findViewById<TextView>(R.id.banned_title)
        title.setTextColor(palette.titleInk)
        val reasonText = findViewById<TextView>(R.id.banned_reason)
        reasonText.setTextColor(palette.ink)
        val expiresText = findViewById<TextView>(R.id.banned_expires)
        expiresText.setTextColor(palette.muted)
        val closeButton = findViewById<Button>(R.id.banned_close)
        closeButton.setBackgroundColor(palette.accent)
        closeButton.setTextColor(palette.onAccent)

        // Alasan: dari intent, jatuh ke ban tersimpan lokal bila kosong.
        var reason = intent.getStringExtra(EXTRA_REASON).orEmpty().trim()
        var expiresAtMs = intent.getLongExtra(EXTRA_EXPIRES_AT_MS, 0)
        if (reason.isEmpty()) {
            AdminStore.savedBan(this)?.let { (r, e) ->
                reason = r
                expiresAtMs = e
            }
        }
        reasonText.text = if (reason.isNotEmpty()) reason
        else "Akun ini dibanned. Hubungi admin untuk info lebih lanjut."
        expiresText.text = if (expiresAtMs == 0L) {
            "Berlaku permanen"
        } else {
            try {
                val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID"))
                "Berlaku sampai " + fmt.format(Date(expiresAtMs))
            } catch (e: Exception) {
                ""
            }
        }

        closeButton.setOnClickListener { finishAffinity() }
        // Tombol kembali: tidak bisa menutup layar banned.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Diam — hanya tombol "Tutup Aplikasi" yang keluar.
            }
        })
    }
}

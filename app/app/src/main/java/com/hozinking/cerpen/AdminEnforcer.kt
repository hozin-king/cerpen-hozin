package com.hozinking.cerpen

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Penegakan ban + perintah admin di sisi aplikasi (paket admin).
 *
 * - showBanned: buka layar banned (CLEAR_TASK — semua activity lain
 *   ikut tertutup, termasuk reader yang sedang dibuka). Gagal membuka
 *   activity -> matikan proses sebagai darurat.
 * - kick: tutup aplikasi (finishAffinity). Buka lagi = gate cek ulang.
 * - crash: crash sungguhan — lempar uncaught exception di main thread.
 *   Hanya dieksekusi bila perintah "crash" datang dari server.
 * - killProcess: darurat terakhir (layar banned tidak muncul).
 *
 * Semua ini perintah server untuk aplikasi MILIK user sendiri — bukan
 * untuk aplikasi pihak ketiga.
 */
object AdminEnforcer {

    /** Tampilkan layar banned; gagal -> matikan proses. */
    fun showBanned(activity: Activity, reason: String, expiresAtMs: Long) {
        try {
            val intent = Intent(activity, BannedActivity::class.java).apply {
                putExtra(BannedActivity.EXTRA_REASON, reason)
                putExtra(BannedActivity.EXTRA_EXPIRES_AT_MS, expiresAtMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            activity.startActivity(intent)
            activity.finishAffinity()
        } catch (e: Exception) {
            killProcess()
        }
    }

    /** Kick paksa: tutup aplikasi. */
    fun kick(activity: Activity?) {
        try {
            activity?.finishAffinity()
        } catch (e: Exception) {
            // Tidak ada activity foreground — gate berikutnya yang menangani.
        }
    }

    /** Crash paksa: uncaught exception di main thread. */
    fun crash() {
        Handler(Looper.getMainLooper()).post {
            throw RuntimeException("Crash paksa dari panel admin")
        }
    }

    /** Darurat: hentikan proses sendiri. */
    fun killProcess() {
        Process.killProcess(Process.myPid())
    }
}

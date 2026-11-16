package com.hozinking.cerpen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Aturan buka episode saat offline (v1.1.39, perintah user 16 Nov 2026):
 * offline HANYA boleh dibuka bila KEDUA syarat terpenuhi:
 * (a) konten sudah terunduh lokal, DAN (b) episode sudah dimiliki
 * (gratis S1E1 atau sudah dibeli) menurut catatan lokal.
 *
 * VIP offline TIDAK cukup — VIP wajib divalidasi ke server. VIP yang
 * sudah pernah dibuka sekali (unlock tercatat di cache lewat
 * /purchase) menjadi Unlocked dan tetap bisa dibuka offline; hanya
 * episode VIP yang belum pernah dibuka yang ditolak saat offline.
 * Saat kembali online, kepemilikan divalidasi ulang ke server
 * (CoinUi.refreshMeAsync / CoinApi.purchase yang sudah ada).
 *
 * "Terunduh" per jenis konten:
 * - cerpen: teks episode ada di katalog lokal (loadCerpen) — selalu
 *   benar bila reader/detail sudah memuat daftar lokal;
 * - komik: semua panel episode ada di perangkat
 *   (DataManager.isKomikEpisodeOffline).
 *
 * [decide] logika murni — bisa dites tanpa Android.
 */
object OfflineOpen {

    enum class DenyReason { NOT_DOWNLOADED, NOT_OWNED, NEEDS_ONLINE_VALIDATION }

    sealed class Decision {
        object Allow : Decision()
        data class Deny(val reason: DenyReason) : Decision()
    }

    fun decide(online: Boolean, lock: CoinGate.LockState, downloadedLocally: Boolean): Decision {
        if (online) return Decision.Allow
        if (!downloadedLocally) return Decision.Deny(DenyReason.NOT_DOWNLOADED)
        return when (lock) {
            is CoinGate.LockState.Free -> Decision.Allow
            is CoinGate.LockState.Unlocked -> Decision.Allow
            is CoinGate.LockState.Vip -> Decision.Deny(DenyReason.NEEDS_ONLINE_VALIDATION)
            is CoinGate.LockState.Locked -> Decision.Deny(DenyReason.NOT_OWNED)
        }
    }

    fun denyMessage(reason: DenyReason): String = when (reason) {
        DenyReason.NOT_DOWNLOADED -> "Episode ini belum terunduh — butuh internet untuk mengunduh"
        DenyReason.NOT_OWNED -> "Episode ini belum dibeli — butuh internet untuk membeli"
        DenyReason.NEEDS_ONLINE_VALIDATION -> "Butuh internet untuk validasi VIP"
    }

    /** Cek konektivitas (pola sama dengan DataManager.isOnline). */
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}

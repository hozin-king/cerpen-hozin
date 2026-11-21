package com.hozinking.cerpen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Alur koin di UI (v1.1.30).
 *
 * Prinsip: aplikasi TIDAK PERNAH memutuskan sendiri — semua yang
 * butuh uang/unlock lewat CoinApi.purchase (server). Cache lokal
 * hanya dipakai untuk menampilkan status & membaca konten yang sudah
 * dimiliki saat offline.
 */
object CoinUi {

    /** Intent ke AccountActivity; [redeemFirst] langsung buka seksi tukar kode. */
    fun accountIntent(activity: Activity, redeemFirst: Boolean = false): Intent =
        Intent(activity, AccountActivity::class.java).apply {
            putExtra(AccountActivity.EXTRA_REDEEM_FIRST, redeemFirst)
        }

    /** Buka "Dukung kreator" dari RemoteConfig (online, bukan hardcode). */
    fun openDonate(activity: Activity) {
        val url = RemoteConfig.loadCached(activity).donateUrl
        if (url.isBlank()) {
            Toast.makeText(activity, "Link donasi belum tersedia", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(activity, "Tidak bisa membuka tautan", Toast.LENGTH_SHORT).show()
        }
    }

    /** Dialog ban akun — alasan + masa berlaku, bukan error generik. */
    fun showBanned(activity: Activity, json: org.json.JSONObject?) {
        val msg = CoinGate.bannedMessage(json)
            ?: "Akun ini dibanned. Hubungi admin untuk info lebih lanjut."
        AlertDialog.Builder(activity)
            .setTitle("Akun dibanned")
            .setMessage(msg)
            .setPositiveButton("Mengerti", null)
            .show()
    }

    /**
     * Gate utama: buka satu episode lewat CoinGate.
     * [locallyRead] = sudah dibaca di perangkat ini sebelum v1.1.30
     * (riwayat lokal) — dihitung sebagai sudah dimiliki.
     * [downloadedLocally] (v1.1.39): konten sudah terunduh lokal —
     * cerpen: teks ada di katalog lokal (default true, benar bila
     * daftar sudah dimuat lokal); komik: semua panel ada di perangkat
     * (DataManager.isKomikEpisodeOffline). Dipakai aturan buka offline.
     * [open] dipanggil HANYA bila episode boleh dibuka.
     */
    fun openEpisode(
        activity: Activity,
        kind: String,
        targetId: String,
        season: Int,
        no: Int,
        locallyRead: Boolean,
        downloadedLocally: Boolean = true,
        open: () -> Unit
    ) {
        ensureUnlocked(activity, kind, targetId, season, no, locallyRead, downloadedLocally, open) { /* batal: diam */ }
    }

    /**
     * Sama seperti openEpisode, tapi untuk pemakaian di dalam reader
     * (defense-in-depth): [onDenied] dipanggil bila pengguna batal /
     * tidak boleh baca — reader menutup dirinya.
     */
    fun ensureUnlocked(
        activity: Activity,
        kind: String,
        targetId: String,
        season: Int,
        no: Int,
        locallyRead: Boolean,
        downloadedLocally: Boolean = true,
        onAllowed: () -> Unit,
        onDenied: () -> Unit
    ) {
        // God-mode admin (21 Nov 2026): grant cache valid -> semua episode
        // terbuka, lokal saja (jalan OFFLINE, tanpa potong koin, tanpa
        // panggilan server). Ditempatkan SEBELUM cek offline.
        if (AdminKeyStore.isAdminActive(activity)) {
            onAllowed()
            return
        }
        val state = CoinStore.snapshot(activity)
        val now = System.currentTimeMillis()
        val lock = CoinGate.lockState(state, kind, targetId, season, no, locallyRead, now)
        // v1.1.39: aturan buka offline (perintah user 16 Nov 2026) —
        // offline HANYA bila terunduh DAN dimiliki (gratis / sudah dibeli).
        // VIP offline ditolak (wajib validasi server); terkunci ditolak
        // (butuh internet untuk membeli). Online = gerbang lama.
        if (!OfflineOpen.isOnline(activity)) {
            when (val d = OfflineOpen.decide(false, lock, downloadedLocally)) {
                is OfflineOpen.Decision.Allow -> { /* lanjut ke gerbang di bawah */ }
                is OfflineOpen.Decision.Deny -> {
                    Toast.makeText(activity, OfflineOpen.denyMessage(d.reason), Toast.LENGTH_LONG).show()
                    onDenied()
                    return
                }
            }
        }
        when (lock) {
            is CoinGate.LockState.Free -> onAllowed()
            is CoinGate.LockState.Unlocked -> onAllowed()
            is CoinGate.LockState.Vip -> {
                // VIP: catat unlock di server (gratis, tanpa potong koin).
                // v1.1.39: cabang ini hanya terjangkau saat ONLINE
                // (offline sudah ditolak di atas — VIP wajib validasi
                // server). Banned = tolak.
                Thread {
                    val r = CoinApi.purchase(activity, kind, targetId, season, no)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (r.status == 403 && CoinGate.bannedMessage(r.json) != null) {
                            showBanned(activity, r.json)
                            onDenied()
                            return@runOnUiThread
                        }
                        if (r.status in 200..299) {
                            r.json?.optInt("balance")?.let { CoinStore.setBalance(activity, it) }
                            CoinStore.addUnlock(activity, CoinGate.unlockKey(kind, targetId, season, no))
                            // v1.1.32: anti beli dobel — server bilang
                            // sudah punya: koin TIDAK dipotong lagi.
                            if (CoinApi.isAlreadyOwned(r.json)) {
                                Toast.makeText(
                                    activity, "Sudah dibeli — koin tidak dipotong",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        onAllowed()
                    }
                }.start()
            }
            is CoinGate.LockState.Locked -> {
                showBuyDialog(activity, kind, targetId, season, no, lock.price, state.balance, onAllowed, onDenied)
            }
        }
    }

    private fun showBuyDialog(
        activity: Activity,
        kind: String,
        targetId: String,
        season: Int,
        no: Int,
        price: Int,
        balance: Int,
        onBought: () -> Unit,
        onDenied: () -> Unit
    ) {
        val short = if (balance >= price) {
            "Episode ini butuh $price koin.\nSaldo kamu: $balance koin."
        } else {
            "Episode ini butuh $price koin.\nSaldo kamu: $balance koin — tidak cukup. " +
                "Tukar kode redeem untuk menambah koin."
        }
        AlertDialog.Builder(activity)
            .setTitle("Episode terkunci")
            .setMessage(short)
            .setPositiveButton("Beli ($price koin)") { _, _ ->
                Thread {
                    val r = CoinApi.purchase(activity, kind, targetId, season, no)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        when {
                            r.isNetworkError -> Toast.makeText(
                                activity,
                                "Butuh internet untuk membeli — coba lagi saat online",
                                Toast.LENGTH_SHORT
                            ).show()
                            r.status == 403 && CoinGate.bannedMessage(r.json) != null -> {
                                showBanned(activity, r.json)
                                onDenied()
                            }
                            r.status == 402 -> {
                                Toast.makeText(
                                    activity,
                                    "Saldo tidak cukup — tukar kode redeem dulu",
                                    Toast.LENGTH_SHORT
                                ).show()
                                activity.startActivity(accountIntent(activity, redeemFirst = true))
                            }
                            r.status in 200..299 -> {
                                r.json?.optInt("balance")?.let { CoinStore.setBalance(activity, it) }
                                CoinStore.addUnlock(activity, CoinGate.unlockKey(kind, targetId, season, no))
                                // v1.1.32: anti beli dobel — server bilang
                                // sudah punya: koin TIDAK dipotong lagi.
                                Toast.makeText(
                                    activity,
                                    if (CoinApi.isAlreadyOwned(r.json)) "Sudah dibeli — koin tidak dipotong"
                                    else "Terbuka!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onBought()
                            }
                            else -> Toast.makeText(
                                activity,
                                CoinApi.errorMessage(r.json, "Gagal membeli, coba lagi"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            }
            .setNeutralButton("Tukar kode") { _, _ ->
                activity.startActivity(accountIntent(activity, redeemFirst = true))
            }
            .setNegativeButton("Batal") { _, _ -> onDenied() }
            .show()
    }

    /** Refresh /me non-blocking (dipanggil onResume Main/Settings). */
    fun refreshMeAsync(activity: Activity, onDone: (() -> Unit)? = null) {
        Thread {
            CoinApi.fetchMe(activity)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onDone?.invoke()
            }
        }.start()
    }
}

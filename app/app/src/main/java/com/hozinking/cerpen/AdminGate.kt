package com.hozinking.cerpen

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Orkestrasi paket admin/keamanan di sisi aplikasi (branch cerpen-admin-batch).
 *
 * Alur:
 * - Buka aplikasi: [checkOnLaunch] — ban tersimpan lokal dicek dulu
 *   (sinkron, tanpa network); lalu cek server (AdminApi.checkStatus —
 *   jalur cadangan /api/coins/me sampai endpoint status khusus ada).
 *   Di-ban -> layar banned SEBELUM konten tampil (Splash memanggil ini
 *   lebih awal; MainActivity memanggil lagi — single-flight 60 detik,
 *   tidak ada panggilan ganda).
 * - Foreground: [startHeartbeat] tiap 120 detik — presence + sesi baca
 *   saat ini + perintah admin. Di-ban selagi membaca -> sesi diputus
 *   (layar banned CLEAR_TASK menutup reader). Belum terkonfigurasi ->
 *   heartbeat memakai checkStatus sebagai poll ban ringan.
 * - Perintah admin (kick/crash) dieksekusi terhadap activity
 *   foreground saat ini ([setForeground]).
 * - Registrasi guest: sekali per instalasi, best-effort (bila endpoint
 *   register belum ada, guest tetap dikenal server lewat device_id di
 *   tiap panggilan CoinApi).
 *
 * Fail-open: offline / server tidak menjawab = aplikasi jalan seperti
 * biasa. Ban HANYA dari jawaban server yang jelas atau ban tersimpan
 * lokal yang belum kedaluwarsa.
 */
object AdminGate {

    /** Sesi baca yang sedang berjalan (untuk heartbeat). */
    data class ReadingSession(
        val kind: String,
        val targetId: String,
        val season: Int,
        val no: Int,
        val startedAtMs: Long
    )

    /** Jeda antar heartbeat saat aplikasi di foreground. */
    const val HEARTBEAT_INTERVAL_MS = 120_000L

    /** Hasil cek buka-aplikasi tidak diulang dalam 60 detik (single-flight). */
    private const val LAUNCH_CHECK_CACHE_MS = 60_000L

    private var foregroundRef: WeakReference<Activity>? = null

    @Volatile
    private var currentSession: ReadingSession? = null

    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var heartbeatRunning = false

    @Volatile
    private var lastLaunchCheckMs = 0L

    @Volatile
    private var lastLaunchResult: AdminApi.Result? = null

    @Volatile
    private var launchCheckRunning = false

    private val pendingLaunchCallbacks = ArrayList<(String, Long) -> Unit>()

    /** Activity yang sedang di foreground (untuk showBanned/kick). */
    fun setForeground(activity: Activity) {
        foregroundRef = WeakReference(activity)
    }

    /** Hapus referensi foreground (hanya bila activity yang sama). */
    fun clearForeground(activity: Activity) {
        if (foregroundRef?.get() === activity) foregroundRef = null
    }

    private fun foregroundActivity(): Activity? = foregroundRef?.get()

    /** Sesi baca saat ini (di-set Reader/KomikReader, null bila tidak membaca). */
    fun noteReadingSession(session: ReadingSession?) {
        currentSession = session
    }

    fun clearReadingSession() {
        currentSession = null
    }

    /**
     * Cek ban saat buka aplikasi. Dipanggil SplashActivity (lebih awal)
     * dan MainActivity (deep link). Hasil di-cache 60 detik; cek yang
     * sedang berjalan dipakai bersama (single-flight) — callback yang
     * datang belakangan ikut antre dan dipanggil saat hasil tiba.
     * [onBanned] dipanggil di UI thread bila di-ban; pemanggil WAJIB
     * memeriksa isFinishing/isDestroyed sebelum menampilkan layar ban
     * (activity pemanggil bisa sudah tidak hidup saat hasil tiba).
     */
    fun checkOnLaunch(activity: Activity, onBanned: (reason: String, expiresAtMs: Long) -> Unit) {
        val ctx = activity.applicationContext
        // 1. Ban tersimpan lokal (sinkron — tanpa network).
        AdminStore.savedBan(ctx)?.let { (reason, exp) ->
            onBanned(reason, exp)
            return
        }
        // 2. Hasil cek < 60 detik lalu — pakai lagi, tanpa network.
        val now = System.currentTimeMillis()
        val cached = lastLaunchResult
        if (cached != null && now - lastLaunchCheckMs < LAUNCH_CHECK_CACHE_MS) {
            if (cached is AdminApi.Result.Banned) onBanned(cached.reason, cached.expiresAtMs)
            return
        }
        // 3. Single-flight: cek yang sedang berjalan dipakai bersama.
        synchronized(this) {
            if (launchCheckRunning) {
                pendingLaunchCallbacks.add(onBanned)
                return
            }
            launchCheckRunning = true
            pendingLaunchCallbacks.add(onBanned)
        }
        Thread {
            val result = AdminApi.checkStatus(ctx)
            lastLaunchCheckMs = System.currentTimeMillis()
            lastLaunchResult = result
            handleResult(ctx, result, null)
            val callbacks: List<(String, Long) -> Unit>
            synchronized(this) {
                launchCheckRunning = false
                callbacks = pendingLaunchCallbacks.toList()
                pendingLaunchCallbacks.clear()
            }
            if (result is AdminApi.Result.Banned) {
                activity.runOnUiThread {
                    for (cb in callbacks) cb(result.reason, result.expiresAtMs)
                }
            }
        }.start()
    }

    /** Terapkan satu hasil AdminApi: simpan ban/kunci, eksekusi perintah. */
    fun handleResult(ctx: Context, result: AdminApi.Result, activity: Activity?) {
        when (result) {
            is AdminApi.Result.Banned -> {
                AdminStore.saveBan(ctx, result.reason, result.expiresAtMs)
                AdminKeyStore.clearApiKey(ctx)
            }
            is AdminApi.Result.Ok -> {
                AdminStore.clearBan(ctx)
                result.status.apiKey?.let { AdminKeyStore.saveApiKey(ctx, it) }
                if (result.status.commands.isNotEmpty()) {
                    executeCommands(result.status.commands, activity)
                }
            }
            else -> {
                // NotConfigured / NetworkError — tidak ada yang diubah.
            }
        }
    }

    /** Eksekusi perintah admin terhadap activity foreground. */
    fun executeCommands(commands: List<AdminApi.AdminCommand>, activity: Activity?) {
        val target = activity ?: foregroundActivity() ?: return
        val main = Handler(Looper.getMainLooper())
        for (cmd in commands) {
            when (cmd.action) {
                AdminApi.CommandAction.KICK -> main.post { AdminEnforcer.kick(target) }
                AdminApi.CommandAction.CRASH -> AdminEnforcer.crash()
                AdminApi.CommandAction.IGNORE -> { /* tipe tak dikenal — abaikan */ }
            }
        }
    }

    /** Daftarkan guest (sekali, best-effort) + minta token integritas. */
    fun registerIfNeeded(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (AdminStore.isRegistered(appCtx)) return
        Thread {
            // Token integritas diminta paralel (async, tidak diblokir).
            DeviceSignals.requestIntegrityToken(appCtx, CoinStore.deviceId(appCtx)) { }
            val result = AdminApi.register(appCtx)
            if (result is AdminApi.Result.Ok || result is AdminApi.Result.Banned) {
                AdminStore.setRegistered(appCtx, true)
            }
            handleResult(appCtx, result, null)
        }.start()
    }

    /** Mulai heartbeat berkala (idempotent; dipanggil onResume). */
    fun startHeartbeat(ctx: Context) {
        if (heartbeatRunning) return
        heartbeatRunning = true
        val appCtx = ctx.applicationContext
        val handler = Handler(Looper.getMainLooper())
        heartbeatHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                if (!heartbeatRunning) return
                Thread {
                    val result = AdminApi.heartbeat(appCtx, currentSession)
                    val effective = if (result is AdminApi.Result.NotConfigured) {
                        // Kontrak heartbeat belum ada — poll ban ringan.
                        AdminApi.checkStatus(appCtx)
                    } else {
                        result
                    }
                    AdminStore.setLastHeartbeatMs(appCtx, System.currentTimeMillis())
                    handleResult(appCtx, effective, null)
                    if (effective is AdminApi.Result.Banned) {
                        val target = foregroundActivity()
                        Handler(Looper.getMainLooper()).post {
                            val act = target
                            if (act != null && !act.isFinishing && !act.isDestroyed) {
                                AdminEnforcer.showBanned(act, effective.reason, effective.expiresAtMs)
                            }
                        }
                    }
                }.start()
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = runnable
        handler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
    }

    /** Hentikan heartbeat (dipanggil onPause). */
    fun stopHeartbeat() {
        heartbeatRunning = false
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
        heartbeatHandler = null
    }
}

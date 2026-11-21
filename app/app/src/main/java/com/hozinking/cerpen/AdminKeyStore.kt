package com.hozinking.cerpen

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Penyimpanan kunci API anti-bypass (paket admin).
 *
 * Kunci API adalah kredensial yang bisa dicabut server — WAJIB
 * tersimpan terenkripsi (EncryptedSharedPreferences / keystore),
 * JANGAN di SharedPreferences biasa, JANGAN pernah ditulis ke log.
 *
 * Bila keystore perangkat bermasalah (EncryptedSharedPreferences gagal
 * dibuat — perangkat lama / keystore rusak), kunci HANYA disimpan di
 * memori proses (hilang saat proses mati; aplikasi daftar ulang dan
 * server menerbitkan kunci baru). JANGAN fallback ke prefs biasa.
 */
object AdminKeyStore {
    private const val TAG = "AdminKeyStore"
    private const val PREFS = "cerpen_admin_key"
    private const val K_API_KEY = "api_key"

    @Volatile
    private var memoryKey: String? = null

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptedFailed = false

    private fun prefs(ctx: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (encryptedFailed) return null
        synchronized(this) {
            encryptedPrefs?.let { return it }
            if (encryptedFailed) return null
            return try {
                val masterKey = MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx.applicationContext,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { encryptedPrefs = it }
            } catch (e: Exception) {
                // Keystore bermasalah — jangan simpan di prefs biasa.
                encryptedFailed = true
                Log.w(TAG, "EncryptedSharedPreferences gagal: ${e.message}")
                null
            }
        }
    }

    /** Kunci API saat ini (null = belum terdaftar / dicabut). */
    fun apiKey(ctx: Context): String? {
        memoryKey?.let { return it }
        val k = try {
            prefs(ctx)?.getString(K_API_KEY, null)
        } catch (e: Exception) {
            null
        }
        if (!k.isNullOrBlank()) memoryKey = k
        return k?.ifEmpty { null }
    }

    /** Simpan kunci baru (rotasi: timpa yang lama). */
    fun saveApiKey(ctx: Context, key: String) {
        if (key.isBlank()) return
        memoryKey = key
        try {
            prefs(ctx)?.edit()?.putString(K_API_KEY, key)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "gagal menyimpan kunci: ${e.message}")
        }
    }

    /** Hapus kunci (dicabut server / keluar) — memori + disk. */
    fun clearApiKey(ctx: Context) {
        memoryKey = null
        try {
            prefs(ctx)?.edit()?.remove(K_API_KEY)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "gagal menghapus kunci: ${e.message}")
        }
    }

    // ---------- God-mode admin (21 Nov 2026, TANPA panel) ----------
    // Grant admin HANYA dari jawaban server (register/heartbeat/status)
    // — tidak pernah di-set lokal tanpa penerbitan server. Disimpan di
    // prefs terenkripsi yang sama dengan kunci API (bukan prefs biasa).
    private const val K_ADMIN_ACTIVE = "admin_active"
    private const val K_ADMIN_EXPIRES_SEC = "admin_expires_at_sec"
    private const val K_ADMIN_FINGERPRINT = "admin_fingerprint"

    /** Validasi murni grant (unit-testable): flag + belum kedaluwarsa + sidik cocok. */
    fun isGrantValid(
        active: Boolean,
        expiresAtSec: Long,
        storedFingerprint: String?,
        currentFingerprint: String,
        nowSec: Long
    ): Boolean {
        if (!active) return false
        if (expiresAtSec <= 0L || expiresAtSec <= nowSec) return false
        if (storedFingerprint.isNullOrBlank()) return false
        return storedFingerprint == currentFingerprint
    }

    /** Simpan grant admin dari server (register/heartbeat/status). */
    fun saveAdminGrant(ctx: Context, expiresAtSec: Long, fingerprint: String) {
        if (expiresAtSec <= 0L || fingerprint.isBlank()) return
        try {
            prefs(ctx)?.edit()
                ?.putBoolean(K_ADMIN_ACTIVE, true)
                ?.putLong(K_ADMIN_EXPIRES_SEC, expiresAtSec)
                ?.putString(K_ADMIN_FINGERPRINT, fingerprint)
                ?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "gagal menyimpan grant admin: ${e.message}")
        }
    }

    /** Hapus grant admin (kedaluwarsa / bukan admin lagi / ban). */
    fun clearAdminGrant(ctx: Context) {
        try {
            prefs(ctx)?.edit()
                ?.remove(K_ADMIN_ACTIVE)
                ?.remove(K_ADMIN_EXPIRES_SEC)
                ?.remove(K_ADMIN_FINGERPRINT)
                ?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "gagal menghapus grant admin: ${e.message}")
        }
    }

    /**
     * true bila grant admin cache VALID di perangkat ini: flag true +
     * belum kedaluwarsa + fingerprint tersimpan cocok dengan sidik
     * perangkat ini. Cek lokal saja — jalan OFFLINE. Kedaluwarsa TIDAK
     * pernah diperpanjang lokal; refresh hanya dari server saat online.
     */
    fun isAdminActive(ctx: Context): Boolean {
        val p = try {
            prefs(ctx)
        } catch (e: Exception) {
            null
        } ?: return false
        val active = try {
            p.getBoolean(K_ADMIN_ACTIVE, false)
        } catch (e: Exception) {
            false
        }
        val exp = try {
            p.getLong(K_ADMIN_EXPIRES_SEC, 0L)
        } catch (e: Exception) {
            0L
        }
        val fp = try {
            p.getString(K_ADMIN_FINGERPRINT, null)
        } catch (e: Exception) {
            null
        }
        val nowSec = System.currentTimeMillis() / 1000
        val current = try {
            DeviceSignals.fingerprint(ctx)
        } catch (e: Exception) {
            ""
        }
        return isGrantValid(active, exp, fp, current, nowSec)
    }
}

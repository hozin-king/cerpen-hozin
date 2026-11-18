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
}

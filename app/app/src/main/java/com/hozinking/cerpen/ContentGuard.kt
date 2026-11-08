package com.hozinking.cerpen

import android.util.Log

/**
 * Jembatan JNI ke lib native `contentguard` (C++, lihat
 * app/src/main/cpp/contentguard.cpp dan CONTENT_PROTECTION.md).
 *
 * Kunci konten 32 byte di-bake ke .so saat build (bukan dari repo, bukan
 * string polos — di-XOR dengan mask tetap). Bila lib tidak ada / gagal
 * dimuat, getKey() mengembalikan null dan DataManager memakai jalur
 * fallback (plaintext masa transisi, dicatat di log).
 */
object ContentGuard {
    private const val TAG = "ContentGuard"

    private var loaded = false
    private var loadFailed = false

    /** Kunci AES-256 (32 byte) atau null bila tidak tersedia. */
    fun getKey(): ByteArray? {
        if (!loaded && !loadFailed) {
            try {
                System.loadLibrary("contentguard")
                loaded = true
            } catch (e: Throwable) {
                loadFailed = true
                Log.w(TAG, "lib contentguard gagal dimuat: ${e.message}")
            }
        }
        if (!loaded) return null
        return try {
            val k = fetchKey()
            if (k != null && k.size == 32) k else null
        } catch (e: Throwable) {
            Log.w(TAG, "fetchKey gagal: ${e.message}")
            null
        }
    }

    @JvmStatic
    private external fun fetchKey(): ByteArray?
}

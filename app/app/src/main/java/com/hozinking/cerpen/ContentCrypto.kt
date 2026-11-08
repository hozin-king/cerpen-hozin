package com.hozinking.cerpen

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM + SHA-256 untuk konten terenkripsi (lihat CONTENT_PROTECTION.md).
 *
 * Format file .enc: IV acak 12 byte + ciphertext + tag GCM 16 byte
 * (javax.crypto, "AES/GCM/NoPadding"). Kunci berasal dari ContentGuard (JNI).
 */
object ContentCrypto {
    private const val TAG = "ContentCrypto"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /**
     * Log aman untuk unit test JVM: android.util.Log melempar "Stub!" di
     * JVM (bukan perangkat), jadi dibungkus agar fungsi kripto tetap
     * bisa diuji. Di perangkat, Log tetap tertulis normal.
     */
    private fun logW(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: RuntimeException) {
            // JVM unit test — android stub, abaikan.
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Dekripsi blob .enc. Null bila kunci salah / data korup / format salah. */
    fun decrypt(enc: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != 32 || enc.size <= IV_LEN + 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, enc, 0, IV_LEN)
            )
            cipher.doFinal(enc, IV_LEN, enc.size - IV_LEN)
        } catch (e: Exception) {
            logW("dekripsi gagal: ${e.message}")
            null
        }
    }

    /**
     * Dekripsi + verifikasi SHA-256(plaintext) terhadap manifest.
     * Mengembalikan byte plaintext bila cocok, null bila dekripsi gagal
     * atau SHA tidak cocok (konten rusak / dimodifikasi). Dipakai untuk
     * JSON (lalu jadi String) maupun gambar (lalu jadi Bitmap).
     */
    fun decryptVerifiedBytes(enc: ByteArray, key: ByteArray, expectedSha256: String): ByteArray? {
        val plain = decrypt(enc, key) ?: return null
        if (!sha256Hex(plain).equals(expectedSha256, ignoreCase = true)) {
            logW("SHA tidak cocok (manifest vs hasil dekripsi)")
            return null
        }
        return plain
    }

    /**
     * Dekripsi + verifikasi SHA-256(plaintext) terhadap manifest.
     * Mengembalikan teks UTF-8 bila cocok, null bila dekripsi gagal atau
     * SHA tidak cocok (konten rusak / dimodifikasi).
     */
    fun decryptVerified(enc: ByteArray, key: ByteArray, expectedSha256: String): String? {
        val plain = decryptVerifiedBytes(enc, key, expectedSha256) ?: return null
        return try {
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            logW("plaintext bukan UTF-8 valid")
            null
        }
    }
}

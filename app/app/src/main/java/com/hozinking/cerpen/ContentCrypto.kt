package com.hozinking.cerpen

import android.util.Log
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
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

    /**
     * Enkripsi plaintext jadi blob .enc (format yang SAMA dengan file
     * repo: IV acak 12 byte + ciphertext + tag GCM 16 byte). Dipakai
     * unduh per konten on-demand: gambar dari URL asli dienkripsi di
     * perangkat sebelum disimpan, supaya di disk hanya ada .enc.
     * Gambar TIDAK di-gzip (JPEG hampir tidak mengecil — v1.1.4).
     * Null bila kunci bukan 32 byte / enkripsi gagal.
     */
    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != 32) return null
        return try {
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            iv + cipher.doFinal(plain)
        } catch (e: Exception) {
            logW("enkripsi gagal: ${e.message}")
            null
        }
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
     * gambar (lalu jadi Bitmap) — byte dikembalikan APA ADANYA, tanpa
     * gunzip (gambar tidak pernah di-gzip). JSON memakai [decryptVerified]
     * yang meng-gunzip bila ada magic gzip.
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
     *
     * v1.1.4 — lapisan gzip (lihat CONTENT_PROTECTION.md): pipeline
     * meng-gzip JSON (cerpen/news/komik) SEBELUM enkripsi, jadi SHA di
     * manifest adalah SHA-256 dari byte TER-GZIP. Setelah dekripsi +
     * verifikasi SHA, bila 2 byte awal = magic gzip (0x1F 0x8B) hasilnya
     * di-gunzip dulu baru jadi String. File .enc LAMA (tanpa gzip)
     * tetap dibaca apa adanya (backward-compatible). Magic ada tapi
     * gunzip gagal -> null (fail-closed).
     *
     * Jalur ini HANYA untuk teks/JSON. Gambar memakai
     * [decryptVerifiedBytes] yang TIDAK meng-gunzip.
     */
    fun decryptVerified(enc: ByteArray, key: ByteArray, expectedSha256: String): String? {
        val plain = decryptVerifiedBytes(enc, key, expectedSha256) ?: return null
        val textBytes = if (isGzip(plain)) gunzip(plain) ?: return null else plain
        return try {
            String(textBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            logW("plaintext bukan UTF-8 valid")
            null
        }
    }

    /** Magic gzip (RFC 1952): 2 byte awal 0x1F 0x8B. */
    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    /** Gunzip; null bila data rusak / bukan gzip valid (fail-closed). */
    private fun gunzip(bytes: ByteArray): ByteArray? {
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (e: Exception) {
            logW("gunzip gagal: ${e.message}")
            null
        }
    }
}

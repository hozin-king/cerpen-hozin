package com.hozinking.cerpen

import java.net.URL
import java.security.MessageDigest

/**
 * Logika MURNI verifikasi update APK (tanpa API Android — bisa dites di
 * JVM, tanpa instal APK sungguhan dan tanpa jaringan).
 *
 * Model ancaman yang ditangani: server/perantara yang menyajikan APK
 * palsu lewat URL update. Nama file, tag versi, URL GitHub, dan checksum
 * dari sumber yang sama TIDAK PERNAH dianggap bukti — yang mengikat
 * adalah signer APK (dibandingkan dengan aplikasi terpasang). Digest
 * SHA-256 dari API GitHub hanya pemeriksaan integritas TAMBAHAN.
 *
 * Bukan anti-reverse-engineering: APK + kunci konten di dalamnya tetap
 * bisa diekstrak pihak yang bertekad (lihat CONTENT_PROTECTION.md).
 */
object ApkUpdateVerification {

    /** Satu-satunya host yang diizinkan untuk URL unduh APK update. */
    val TRUSTED_UPDATE_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    /** Batas redirect yang diikuti saat mengunduh APK. */
    const val MAX_REDIRECTS = 5

    /** Hasil verifikasi satu APK terunduh. */
    sealed class Result {
        object Ok : Result()
        data class Failed(val reason: Reason) : Result()
    }

    enum class Reason {
        FileUnreadable,
        SizeMismatch,
        DigestMismatch,
        PackageMismatch,
        SignerMismatch
    }

    /**
     * Terima hanya URL HTTPS dengan host persis di allowlist. Tolak
     * protokol lain, host lain (termasuk subdomain/tiruan seperti
     * github.com.evil.com), dan URL yang tidak bisa di-parse.
     */
    fun isTrustedUpdateUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val url = try {
            URL(rawUrl.trim())
        } catch (e: Exception) {
            return false
        }
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host?.lowercase() ?: return false
        return host in TRUSTED_UPDATE_HOSTS
    }

    /**
     * Resolve header Location (bisa relatif) terhadap URL saat ini.
     * Kembalikan URL absolut hanya bila hasil akhirnya terpercaya;
     * null bila Location kosong, tidak bisa di-resolve, atau mengarah
     * ke host/protokol yang tidak diizinkan.
     */
    fun resolveRedirect(currentUrl: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val next = try {
            URL(URL(currentUrl), location.trim())
        } catch (e: Exception) {
            return null
        }
        val s = next.toString()
        return if (isTrustedUpdateUrl(s)) s else null
    }

    /** Parse digest GitHub "sha256:<64 hex>" jadi byte; null bila hilang/salah format. */
    fun parseSha256Digest(digest: String?): ByteArray? {
        if (digest == null) return null
        val d = digest.trim()
        if (!d.startsWith("sha256:")) return null
        val hex = d.removePrefix("sha256:")
        if (hex.length != 64) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return hexStringToBytes(hex)
    }

    fun hexStringToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * Digest hilang (null) = lewati pemeriksaan ini — digest BUKAN
     * satu-satunya validasi, signer tetap diperiksa. Digest ada tapi
     * salah format atau tidak cocok = gagal.
     */
    fun verifyDigest(expectedRawDigest: String?, actualSha256: ByteArray): Boolean {
        if (expectedRawDigest == null) return true
        val expected = parseSha256Digest(expectedRawDigest) ?: return false
        return MessageDigest.isEqual(expected, actualSha256)
    }

    /** Ukuran tidak diketahui (<= 0) = lewati; bila diketahui harus sama persis. */
    fun sizeMatches(actualSize: Long, expectedSize: Long): Boolean =
        expectedSize <= 0 || actualSize == expectedSize

    /**
     * Signer APK harus ada di lineage aplikasi terpasang: signer saat
     * ini ATAU riwayat sertifikatnya (certificate rotation Android —
     * kunci baru yang ditandatangani kunci lama tetap diterima, dan
     * sebaliknya). Digest sertifikat dibandingkan sebagai hex lowercase.
     */
    fun signerLineageMatches(
        apkSignerDigests: Set<String>,
        apkSignerHistoryDigests: Set<String>,
        installedSignerDigests: Set<String>,
        installedSignerHistoryDigests: Set<String>
    ): Boolean {
        if (apkSignerDigests.isEmpty() && apkSignerHistoryDigests.isEmpty()) return false
        val apkAll = (apkSignerDigests + apkSignerHistoryDigests).map { it.lowercase() }.toSet()
        val installedAll =
            (installedSignerDigests + installedSignerHistoryDigests).map { it.lowercase() }.toSet()
        return apkAll.intersect(installedAll).isNotEmpty()
    }

    /** Package harus sama persis DAN signer harus ada di lineage terpasang. */
    fun packageAndSignersMatch(
        apkPackage: String?,
        apkSignerDigests: Set<String>,
        apkSignerHistoryDigests: Set<String>,
        installedPackage: String,
        installedSignerDigests: Set<String>,
        installedSignerHistoryDigests: Set<String>
    ): Boolean {
        if (apkPackage == null || apkPackage != installedPackage) return false
        return signerLineageMatches(
            apkSignerDigests, apkSignerHistoryDigests,
            installedSignerDigests, installedSignerHistoryDigests
        )
    }

    /** Gerbang instalasi: hanya Result.Ok yang boleh dipasang. */
    fun installationAllowed(result: Result): Boolean = result is Result.Ok
}

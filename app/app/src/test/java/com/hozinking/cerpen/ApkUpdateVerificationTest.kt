package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Uji logika murni verifikasi update APK (tanpa Android / tanpa jaringan).
 * Tidak ada secret/kunci nyata di sini — digest contoh adalah hash dari
 * string biasa ("hello", "") yang bisa dihitung siapa pun.
 */
class ApkUpdateVerificationTest {

    // ---------- isTrustedUpdateUrl ----------

    @Test
    fun `hanya https github dan objects githubusercontent yang dipercaya`() {
        assertTrue(
            ApkUpdateVerification.isTrustedUpdateUrl(
                "https://github.com/hozin-king/cerpen-hozin/releases/download/v1.1.8/CerpenHozin-v1.1.8.apk"
            )
        )
        assertTrue(
            ApkUpdateVerification.isTrustedUpdateUrl(
                "https://objects.githubusercontent.com/abc123/CerpenHozin-v1.1.8.apk"
            )
        )
        // Skema/host tidak case-sensitive.
        assertTrue(ApkUpdateVerification.isTrustedUpdateUrl("HTTPS://GITHUB.COM/x.apk"))
    }

    @Test
    fun `tolak http host lain dan tiruan host`() {
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl("http://github.com/x.apk"))
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl("https://evil.com/x.apk"))
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl("https://github.com.evil.com/x.apk"))
        assertFalse(
            ApkUpdateVerification.isTrustedUpdateUrl(
                "https://objects.githubusercontent.com.evil.com/x.apk"
            )
        )
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl("ftp://github.com/x.apk"))
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl("bukan url"))
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl(null))
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl(""))
        // Trik userinfo: host sebenarnya evil.com.
        assertFalse(ApkUpdateVerification.isTrustedUpdateUrl("https://github.com@evil.com/x.apk"))
    }

    // ---------- resolveRedirect ----------

    @Test
    fun `redirect github ke host aset resmi diizinkan, ke host lain ditolak`() {
        val start =
            "https://github.com/hozin-king/cerpen-hozin/releases/download/v1.1.8/CerpenHozin-v1.1.8.apk"
        assertEquals(
            "https://objects.githubusercontent.com/abc/x.apk",
            ApkUpdateVerification.resolveRedirect(start, "https://objects.githubusercontent.com/abc/x.apk")
        )
        // Location relatif di-resolve terhadap URL saat ini.
        assertEquals(
            "https://github.com/baru.apk",
            ApkUpdateVerification.resolveRedirect(start, "/baru.apk")
        )
        assertNull(ApkUpdateVerification.resolveRedirect(start, "https://evil.com/x.apk"))
        assertNull(ApkUpdateVerification.resolveRedirect(start, "http://github.com/x.apk"))
        assertNull(ApkUpdateVerification.resolveRedirect(start, null))
        assertNull(ApkUpdateVerification.resolveRedirect(start, ""))
    }

    // ---------- parseSha256Digest ----------

    // SHA-256 dari byte kosong (nilai publik yang sudah dikenal).
    private val emptySha256Hex =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `parse digest sha256 yang valid`() {
        val bytes = ApkUpdateVerification.parseSha256Digest("sha256:$emptySha256Hex")
        assertEquals(32, bytes?.size)
        assertEquals(emptySha256Hex, ApkUpdateVerification.sha256Hex(ByteArray(0)))
    }

    @Test
    fun `tolak digest salah format`() {
        assertNull(ApkUpdateVerification.parseSha256Digest(null))
        assertNull(ApkUpdateVerification.parseSha256Digest(""))
        assertNull(ApkUpdateVerification.parseSha256Digest("md5:$emptySha256Hex"))
        // 63 hex (kurang satu).
        assertNull(ApkUpdateVerification.parseSha256Digest("sha256:${emptySha256Hex.dropLast(1)}"))
        // 65 hex (lebih satu).
        assertNull(ApkUpdateVerification.parseSha256Digest("sha256:${emptySha256Hex}0"))
        // Karakter bukan hex.
        assertNull(
            ApkUpdateVerification.parseSha256Digest("sha256:${emptySha256Hex.drop(1)}z")
        )
        // Tanpa prefix.
        assertNull(ApkUpdateVerification.parseSha256Digest(emptySha256Hex))
    }

    // ---------- verifyDigest ----------

    // SHA-256("hello") — nilai publik.
    private val helloSha256Hex =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `digest hilang dilewati, cocok diterima, salah format atau beda ditolak`() {
        val actual = sha256("hello".toByteArray(Charsets.UTF_8))
        // Hilang = lewati (signer tetap diperiksa oleh pemanggil).
        assertTrue(ApkUpdateVerification.verifyDigest(null, actual))
        assertTrue(ApkUpdateVerification.verifyDigest("sha256:$helloSha256Hex", actual))
        // Digest benar tapi untuk isi lain.
        assertFalse(ApkUpdateVerification.verifyDigest("sha256:$emptySha256Hex", actual))
        // Salah format tidak boleh lolos diam-diam.
        assertFalse(ApkUpdateVerification.verifyDigest("md5:abc", actual))
        assertFalse(ApkUpdateVerification.verifyDigest("sha256:xyz", actual))
        assertFalse(ApkUpdateVerification.verifyDigest("", actual))
    }

    // ---------- sizeMatches ----------

    @Test
    fun `ukuran tidak diketahui dilewati, bila diketahui harus sama persis`() {
        assertTrue(ApkUpdateVerification.sizeMatches(12345, 0))
        assertTrue(ApkUpdateVerification.sizeMatches(12345, -1))
        assertTrue(ApkUpdateVerification.sizeMatches(12345, 12345))
        assertFalse(ApkUpdateVerification.sizeMatches(12344, 12345))
        assertFalse(ApkUpdateVerification.sizeMatches(0, 12345))
    }

    // ---------- packageAndSignersMatch / signerLineageMatches ----------

    private val signerA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val signerB =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val signerC =
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

    @Test
    fun `package sama dan signer sama diterima`() {
        assertTrue(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", setOf(signerA), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), emptySet()
            )
        )
    }

    @Test
    fun `package beda ditolak walau signer sama`() {
        assertFalse(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozin.palsu", setOf(signerA), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), emptySet()
            )
        )
        assertFalse(
            ApkUpdateVerification.packageAndSignersMatch(
                null, setOf(signerA), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), emptySet()
            )
        )
    }

    @Test
    fun `signer beda total ditolak`() {
        assertFalse(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", setOf(signerB), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), emptySet()
            )
        )
    }

    @Test
    fun `rotasi sertifikat diterima dua arah`() {
        // APK ditandatangani kunci baru (B); aplikasi terpasang punya
        // riwayat B (kunci baru ditandatangani kunci lama A).
        assertTrue(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", setOf(signerB), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), setOf(signerA, signerB)
            )
        )
        // Sebaliknya: APK membawa riwayat (A lama + B baru), aplikasi
        // terpasang masih memakai A.
        assertTrue(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", setOf(signerB), setOf(signerA, signerB),
                "com.hozinking.cerpen", setOf(signerA), setOf(signerA)
            )
        )
        // Riwayat tidak bersinggungan sama sekali tetap ditolak.
        assertFalse(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", setOf(signerC), setOf(signerC),
                "com.hozinking.cerpen", setOf(signerA), setOf(signerA, signerB)
            )
        )
    }

    @Test
    fun `apk tanpa signer ditolak, hex tidak case-sensitive`() {
        assertFalse(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", emptySet(), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), emptySet()
            )
        )
        assertTrue(
            ApkUpdateVerification.packageAndSignersMatch(
                "com.hozinking.cerpen", setOf(signerA.uppercase()), emptySet(),
                "com.hozinking.cerpen", setOf(signerA), emptySet()
            )
        )
    }

    // ---------- installationAllowed (gerbang instalasi) ----------

    @Test
    fun `hanya hasil Ok yang boleh dipasang, semua alasan gagal ditolak`() {
        assertTrue(
            ApkUpdateVerification.installationAllowed(ApkUpdateVerification.Result.Ok)
        )
        for (reason in ApkUpdateVerification.Reason.values()) {
            assertFalse(
                ApkUpdateVerification.installationAllowed(
                    ApkUpdateVerification.Result.Failed(reason)
                )
            )
        }
    }
}

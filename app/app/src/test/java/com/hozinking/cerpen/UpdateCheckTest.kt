package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji logika murni auto-update (tanpa Android / tanpa jaringan).
 *  v1.1.29: cek versi via CMS — format /api/cerpen-version. */
class UpdateCheckTest {

    // ---------- compareVersions ----------

    @Test
    fun `versi sama dianggap sama`() {
        assertEquals(0, UpdateCheck.compareVersions("1.1.5", "1.1.5"))
        assertEquals(0, UpdateCheck.compareVersions("v1.1.5", "1.1.5"))
    }

    @Test
    fun `versi lebih baru dan lebih lama`() {
        assertTrue(UpdateCheck.compareVersions("1.1.6", "1.1.5") > 0)
        assertTrue(UpdateCheck.compareVersions("1.1.5", "1.1.6") < 0)
        assertTrue(UpdateCheck.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(UpdateCheck.compareVersions("1.10.0", "1.9.0") > 0)
        assertEquals(0, UpdateCheck.compareVersions("1.1", "1.1.0"))
    }

    @Test
    fun `tag invalid dianggap sama (fail-open, bukan update)`() {
        assertEquals(0, UpdateCheck.compareVersions("bukan-versi", "1.1.5"))
        assertEquals(0, UpdateCheck.compareVersions("1.1.5", ""))
        assertNull(UpdateCheck.parseVersion("1.x.3"))
        assertNull(UpdateCheck.parseVersion(""))
    }

    // ---------- countNewerReleases ----------

    @Test
    fun `hitung rilis yang lebih baru dari versi terpasang`() {
        val tags = listOf("v1.1.4", "v1.1.5", "v1.1.6", "v1.1.7", "v1.1.8")
        assertEquals(3, UpdateCheck.countNewerReleases("1.1.5", tags))
        assertEquals(0, UpdateCheck.countNewerReleases("1.1.8", tags))
        assertEquals(0, UpdateCheck.countNewerReleases("1.1.5", emptyList()))
        // Tag invalid diabaikan.
        assertEquals(1, UpdateCheck.countNewerReleases("1.1.5", listOf("ngawur", "v1.1.6")))
    }

    // ---------- decide (soft vs force vs diam) ----------

    private fun release(tag: String, apkUrl: String? = "https://example.com/app.apk") =
        UpdateCheck.ReleaseInfo(tag, tag.removePrefix("v"), apkUrl)

    @Test
    fun `versi sama dengan terbaru diam`() {
        val d = UpdateCheck.decide("1.1.5", release("v1.1.5"), listOf("v1.1.5"))
        assertTrue(d is UpdateCheck.Decision.None)
    }

    @Test
    fun `versi terpasang lebih baru dari rilis diam`() {
        val d = UpdateCheck.decide("1.1.6", release("v1.1.5"), listOf("v1.1.5"))
        assertTrue(d is UpdateCheck.Decision.None)
    }

    @Test
    fun `api gagal atau rilis null diam`() {
        assertTrue(UpdateCheck.decide("1.1.5", null, emptyList()) is UpdateCheck.Decision.None)
    }

    @Test
    fun `tertinggal 1 rilis dialog biasa`() {
        val d = UpdateCheck.decide("1.1.5", release("v1.1.6"), listOf("v1.1.6"))
        assertTrue(d is UpdateCheck.Decision.Soft)
        assertEquals("v1.1.6", (d as UpdateCheck.Decision.Soft).release.tag)
    }

    @Test
    fun `tertinggal 2 rilis masih dialog biasa`() {
        val d = UpdateCheck.decide(
            "1.1.5", release("v1.1.7"), listOf("v1.1.7", "v1.1.6", "v1.1.5")
        )
        assertTrue(d is UpdateCheck.Decision.Soft)
    }

    @Test
    fun `tertinggal 3 rilis dialog paksa`() {
        val d = UpdateCheck.decide(
            "1.1.5", release("v1.1.8"), listOf("v1.1.8", "v1.1.7", "v1.1.6", "v1.1.5")
        )
        assertTrue(d is UpdateCheck.Decision.Force)
        assertEquals("v1.1.8", (d as UpdateCheck.Decision.Force).release.tag)
    }

    @Test
    fun `daftar tag kosong tidak langsung paksa (fail-open)`() {
        val d = UpdateCheck.decide("1.1.5", release("v1.1.9"), emptyList())
        assertTrue(d is UpdateCheck.Decision.Soft)
    }

    // ---------- parseLatestRelease (v1.1.29: format CMS /api/cerpen-version) ----------
    // Catatan: tes bagian ini ditulis ulang di v1.1.30 — versi lama memakai
    // format GitHub (tag_name + assets + pickApkAsset + parseReleaseTags)
    // yang sudah tidak ada di UpdateCheck.kt sejak v1.1.29.

    @Test
    fun `parse rilis terbaru format CMS`() {
        val json = """{"tag":"v1.1.6","version":"1.1.6","apk_url":"https://example.com/CerpenHozin-v1.1.6.apk","published_at":"2026-11-13T00:00:00Z"}"""
        val r = UpdateCheck.parseLatestRelease(json)
        assertEquals("v1.1.6", r?.tag)
        assertEquals("1.1.6", r?.version)
        assertEquals("https://example.com/CerpenHozin-v1.1.6.apk", r?.apkUrl)
    }

    @Test
    fun `apk_url kosong atau hilang menjadi null`() {
        assertNull(UpdateCheck.parseLatestRelease("""{"tag":"v1.1.6","version":"1.1.6"}""")?.apkUrl)
        assertNull(UpdateCheck.parseLatestRelease("""{"tag":"v1.1.6","version":"1.1.6","apk_url":""}""")?.apkUrl)
    }

    @Test
    fun `version diturunkan dari tag bila kosong`() {
        val r = UpdateCheck.parseLatestRelease("""{"tag":"v1.1.6","apk_url":"https://example.com/a.apk"}""")
        assertEquals("v1.1.6", r?.tag)
        assertEquals("1.1.6", r?.version)
    }

    @Test
    fun `tag invalid atau json rusak menghasilkan null`() {
        assertNull(UpdateCheck.parseLatestRelease("""{"tag":"ngawur"}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{}"""))
        assertNull(UpdateCheck.parseLatestRelease("bukan json"))
    }

    // ---------- verifikasi SHA signer (v1.1.23, anti-bypass) ----------

    @Test
    fun `fingerprint resmi dikenali apa pun formatnya`() {
        val fp = UpdateCheck.OFFICIAL_SIGNER_SHA256
        assertTrue(UpdateCheck.signerMatches(fp))
        assertTrue(UpdateCheck.signerMatches(fp.uppercase()))
        assertTrue(UpdateCheck.signerMatches(fp.chunked(2).joinToString(":")))
        assertTrue(UpdateCheck.signerMatches("  $fp  "))
    }

    @Test
    fun `fingerprint lain atau kosong ditolak`() {
        assertFalse(UpdateCheck.signerMatches("00".repeat(32)))
        assertFalse(UpdateCheck.signerMatches(""))
        assertFalse(UpdateCheck.signerMatches("75ba028f"))
    }

    @Test
    fun `normalisasi buang pemisah dan huruf kecil`() {
        assertEquals("aabbcc", UpdateCheck.normalizeFingerprint("AA:BB:CC"))
        assertEquals("", UpdateCheck.normalizeFingerprint("::  "))
    }

    // ---------- hapus APK terinstal (v1.1.23) ----------

    @Test
    fun `apk dihapus bila versi terpasang sudah sama atau lebih baru`() {
        assertTrue(UpdateCheck.shouldDeleteDownloadedApk("1.1.23", "v1.1.23"))
        assertTrue(UpdateCheck.shouldDeleteDownloadedApk("1.1.23", "v1.1.22"))
        assertTrue(UpdateCheck.shouldDeleteDownloadedApk("1.2.0", "v1.1.22"))
    }

    @Test
    fun `apk dipertahankan bila instal belum sukses atau tag invalid`() {
        assertFalse(UpdateCheck.shouldDeleteDownloadedApk("1.1.21", "v1.1.22"))
        assertFalse(UpdateCheck.shouldDeleteDownloadedApk("1.1.23", "mismatch"))
        assertFalse(UpdateCheck.shouldDeleteDownloadedApk("1.1.23", ""))
    }
}

package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Uji tab Komik (v1.1.3): parser komik.json, entri manifest komik,
 * dan resolusi tiap panel/cover ke file .enc di repo data/.
 *
 * Fixture di `src/test/resources/` dibuat oleh Python (cryptography/AESGCM)
 * dengan KUNCI TEST sekali-pakai yang sama dengan ContentCryptoTest
 * (`test-only-key.b64`, random, BUKAN kunci konten asli):
 * komik_fixture.json.enc = komik.json ASLI (10 komik) terenkripsi,
 * komik_manifest_fixture.json.enc = manifest berisi entri komik.json +
 * satu cover + satu panel (SHA dari file aslinya).
 */
class KomikTest {

    private fun loadResource(name: String): ByteArray =
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes()
            ?: error("fixture $name tidak ditemukan")

    /** Kunci TEST sekali-pakai — bukan kunci konten Cerpen. */
    private fun testKey(): ByteArray =
        Base64.getDecoder().decode(
            loadResource("test-only-key.b64").toString(Charsets.UTF_8).trim()
        )

    /** Dekripsi komik.json fixture (kunci test) + verifikasi SHA lawan manifest fixture. */
    private fun loadKomikFromFixture(): List<Komik> {
        val key = testKey()
        val manifest = JSONObject(
            String(ContentCrypto.decrypt(loadResource("komik_manifest_fixture.json.enc"), key)!!, Charsets.UTF_8)
        )
        val entry = manifest.getJSONObject("komik.json")
        val text = ContentCrypto.decryptVerified(
            loadResource("komik_fixture.json.enc"), key, entry.getString("sha256")
        )
        assertNotNull("komik.json fixture gagal didekripsi / SHA tidak cocok", text)
        return ContentLoader.parseKomik(text!!)
    }

    @Test
    fun `dekripsi komik json valid 10 komik 20 episode 100 panel`() {
        val komik = loadKomikFromFixture()
        assertEquals(10, komik.size)
        assertEquals(20, komik.sumOf { it.totalEpisodes })
        assertEquals(100, komik.sumOf { it.totalPanels })
        val first = komik[0]
        assertEquals("komik-01", first.id)
        assertEquals("Rumah Makan Sederhana", first.judul)
        assertEquals("komik-01-cover.jpg", first.cover)
        assertEquals(2, first.episodes.size)
        assertEquals(1, first.episodes[0].no)
        assertEquals("Warisan yang Terbagi", first.episodes[0].judul)
        assertEquals(5, first.episodes[0].panels.size)
        assertTrue(
            "tiap panel harus punya nama gambar + teks dialog",
            komik.all { k -> k.episodes.all { e -> e.panels.all { it.image.isNotEmpty() && it.teks.isNotEmpty() } } }
        )
        assertEquals("komik-10", komik.last().id)
    }

    @Test
    fun `manifest memuat entri komik`() {
        val key = testKey()
        val manifest = JSONObject(
            String(ContentCrypto.decrypt(loadResource("komik_manifest_fixture.json.enc"), key)!!, Charsets.UTF_8)
        )
        val jsonEntry = manifest.optJSONObject("komik.json")
        assertNotNull("entri komik.json hilang dari manifest", jsonEntry)
        assertEquals("komik.json.enc", jsonEntry!!.optString("file"))
        assertEquals(64, jsonEntry.optString("sha256").length)
        val coverEntry = manifest.optJSONObject("komik/covers/komik-01-cover.jpg")
        assertNotNull("entri cover komik hilang dari manifest", coverEntry)
        assertEquals("komik/covers/komik-01-cover.jpg.enc", coverEntry!!.optString("file"))
        val panelEntry = manifest.optJSONObject("komik/panels/komik-01-e1-p1.jpg")
        assertNotNull("entri panel komik hilang dari manifest", panelEntry)
        assertEquals("komik/panels/komik-01-e1-p1.jpg.enc", panelEntry!!.optString("file"))
    }

    @Test
    fun `tiap panel dan cover ter-resolve ke file enc di repo data`() {
        val komik = loadKomikFromFixture()
        val dataDir = findRepoDataDir()
        var checked = 0
        for (k in komik) {
            val cover = File(dataDir, "komik/covers/${k.cover}.enc")
            assertTrue("cover .enc hilang: ${cover.path}", cover.exists() && cover.length() > 0)
            checked++
            for (e in k.episodes) {
                for (p in e.panels) {
                    val f = File(dataDir, "komik/panels/${p.image}.enc")
                    assertTrue("panel .enc hilang: ${f.path}", f.exists() && f.length() > 0)
                    checked++
                }
            }
        }
        assertEquals("10 cover + 100 panel", 110, checked)
    }

    @Test
    fun `parser memakai field no dan tahan JSON rusak`() {
        val parsed = ContentLoader.parseKomik(
            """{"komik":[{"id":"k1","judul":"T","genre":"G","deskripsi":"D","cover":"c.jpg","episodes":[{"no":3,"judul":"E3","panels":[{"image":"p.jpg","teks":"halo"}]}]}]}"""
        )
        assertEquals(1, parsed.size)
        assertEquals(3, parsed[0].episodes[0].no)
        assertEquals("E3", parsed[0].episodes[0].judul)
        assertEquals("p.jpg", parsed[0].episodes[0].panels[0].image)
        assertEquals("halo", parsed[0].episodes[0].panels[0].teks)
        assertTrue(ContentLoader.parseKomik("bukan json").isEmpty())
        assertTrue(ContentLoader.parseKomik("""{"komik":[]}""").isEmpty())
        assertTrue(ContentLoader.parseKomik("").isEmpty())
    }

    /**
     * Cari folder data/ repo (berisi komik.json.enc hasil sync paralel).
     * Gradle menjalankan unit test dengan working dir = modul (app/app),
     * jadi data/ ada di ../../data; fallback naik dari user.dir sampai ketemu.
     */
    private fun findRepoDataDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            val data = File(dir, "data")
            if (File(data, "komik.json.enc").exists()) return data
            val here = File(dir, "komik.json.enc")
            if (here.exists()) return dir
            dir = dir.parentFile
        }
        error(
            "repo data/ dengan komik.json.enc tidak ditemukan dari " +
                "${System.getProperty("user.dir")} — sync komik (pekerja paralel) belum selesai?"
        )
    }
}

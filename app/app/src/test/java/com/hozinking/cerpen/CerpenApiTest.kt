package com.hozinking.cerpen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Uji CerpenApi (hash path muter) + D1ContentBuilder (rakit ulang JSON
 * dari dump D1) — v1.1.19.
 *
 * Nilai hash ekspektasi dihitung independen di Python (hmac/hashlib)
 * dari kunci test yang sama — bukan dari implementasi Kotlin sendiri.
 * Kunci test = 32 byte 0x11 (BUKAN kunci konten asli).
 */
class CerpenApiTest {

    private fun testKey(): ByteArray = ByteArray(32) { 0x11 }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun decryptWithTestKey(b64cipher: String): String? {
        return try {
            val raw = Base64.getDecoder().decode(b64cipher)
            ContentCrypto.decrypt(raw, testKey())?.let { String(it, Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    private fun cipher(text: String): String {
        val enc = ContentCrypto.encrypt(text.toByteArray(Charsets.UTF_8), testKey())
            ?: error("enkripsi test gagal")
        return b64(enc)
    }

    // ---- CerpenApi: kunci-jalur + hash jam ----

    @Test
    fun derivePathKey_cocokPython() {
        val pk = CerpenApi.derivePathKey(testKey())
        assertEquals(
            "cb99eccac37caa627f1b337ba4d6fb0ac67533ab0e3e2730fd7f954c893d42a6",
            hex(pk)
        )
    }

    @Test
    fun wibHourString_jamBenar() {
        // 2026-11-12 12:00 WIB = 1794459600 epoch.
        assertEquals("2026-11-12-12", CerpenApi.wibHourString(1794459600L * 1000))
        assertEquals("2026-11-12-11", CerpenApi.wibHourString(1794459600L * 1000, -1))
        assertEquals("2026-11-12-13", CerpenApi.wibHourString(1794459600L * 1000, 1))
    }

    @Test
    fun hourHash_cocokPython() {
        val pk = CerpenApi.derivePathKey(testKey())
        assertEquals("03d402f289b2", CerpenApi.hourHash(pk, "2026-11-12-12"))
        assertEquals("a0a9d1f3cbb6", CerpenApi.hourHash(pk, "2026-11-12-11"))
    }

    // ---- D1ContentBuilder ----

    private fun fakeDump(): JSONObject {
        val series = JSONArray().apply {
            // Sengaja terbalik (02 dulu) — builder harus mengurutkan per id.
            put(JSONObject().apply {
                put("id", "cerpen-02")
                put("judul", "Judul Dua")
                put("genre", "Drama")
                put("deskripsi", "")
                put("cover_url", "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/covers/cover_02.jpg.enc")
                put("status", "aktif")
            })
            put(JSONObject().apply {
                put("id", "cerpen-01")
                put("judul", "Judul Satu")
                put("genre", "Misteri")
                put("deskripsi", "Deskripsi satu")
                put("cover_url", "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/covers/cover_01.jpg.enc")
                put("status", "aktif")
            })
        }
        val episodes = JSONArray().apply {
            put(JSONObject().apply {
                put("series_id", "cerpen-01"); put("season", 1); put("no", 2)
                put("judul", "Episode Dua")
                put("isi_cipher", cipher("Isi episode dua."))
                put("word_count", 3)
            })
            put(JSONObject().apply {
                put("series_id", "cerpen-01"); put("season", 1); put("no", 1)
                put("judul", "Episode Satu")
                put("isi_cipher", cipher("Isi episode satu."))
                put("word_count", 3)
            })
            put(JSONObject().apply {
                put("series_id", "cerpen-01"); put("season", 2); put("no", 1)
                put("judul", "Musim Dua")
                put("isi_cipher", cipher("Isi musim dua."))
                put("word_count", 3)
            })
        }
        val komik = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "komik-01")
                put("judul", "Komik Satu")
                put("genre", "")
                put("deskripsi", "")
                put("cover_url", "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/komik/covers/komik-01-cover.jpg.enc")
            })
        }
        val panels = JSONArray().apply {
            put(JSONObject().apply {
                put("image_url", "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/komik/panels/p01.jpg.enc")
                put("teks", "Halo")
            })
            put(JSONObject().apply {
                put("image_url", "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/komik/panels/p02.jpg.enc")
                put("teks", "Dunia")
            })
        }
        val komikEpisodes = JSONArray().apply {
            put(JSONObject().apply {
                put("komik_id", "komik-01"); put("no", 1); put("judul", "Ep 1")
                put("panels_cipher", cipher(panels.toString()))
                put("panels_count", 2)
            })
        }
        val news = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "news-abc")
                put("title", "Judul Berita")
                put("summary", "Ringkasan")
                put("source_name", "Sumber")
                put("source_url", "https://example.com/a")
                put("image_url", "https://example.com/i1.jpg")
                put("images_json", "[\"https://example.com/i1.jpg\", \"https://example.com/i2.jpg\"]")
                put("details_json", "{\"sumber_data\": \"tes\"}")
                put("topic", "phone")
                put("published_at", "2026-11-12")
                put("added_at", "2026-11-12T11:20:18+07:00")
            })
        }
        return JSONObject().apply {
            put("series", series)
            put("episodes", episodes)
            put("komik", komik)
            put("komik_episodes", komikEpisodes)
            put("news", news)
        }
    }

    @Test
    fun buildAll_roundTrip_terbacaContentLoader() {
        val built = D1ContentBuilder.buildAll(fakeDump(), ::decryptWithTestKey)
        assertNotNull(built)
        built!!

        val cerpen = ContentLoader.parseCerpen(built.cerpenJson)
        assertEquals(2, cerpen.size)
        assertEquals("cerpen-01", cerpen[0].id) // diurutkan per id
        assertEquals("Judul Satu", cerpen[0].judul)
        assertEquals("cover_01.jpg", cerpen[0].cover)
        assertEquals(2, cerpen[0].seasons.size)
        assertEquals("Isi episode satu.", cerpen[0].episode(1, 1)?.isi)
        assertEquals("Isi episode dua.", cerpen[0].episode(1, 2)?.isi)
        assertEquals("Isi musim dua.", cerpen[0].episode(2, 1)?.isi)
        assertEquals("Judul Dua", cerpen[1].judul)

        val komik = ContentLoader.parseKomik(built.komikJson)
        assertEquals(1, komik.size)
        assertEquals("komik-01-cover.jpg", komik[0].cover)
        val ep = komik[0].episode(1)
        assertNotNull(ep)
        assertEquals(2, ep!!.panels.size)
        assertEquals("p01.jpg", ep.panels[0].image)
        assertEquals("Halo", ep.panels[0].teks)
        assertEquals("Dunia", ep.panels[1].teks)

        val news = ContentLoader.parseNews(built.newsJson)
        assertEquals(1, news.size)
        assertEquals("Judul Berita", news[0].title)
        assertEquals(2, news[0].images.size)
        assertEquals("https://example.com/i1.jpg", news[0].imageUrls[0])
        assertTrue(news[0].details.contains("tes"))
        assertEquals("2026-11-12T11:20:18+07:00", news[0].addedAt)

        assertEquals(
            "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/covers/cover_01.jpg.enc",
            built.imageUrls["covers/cover_01.jpg"]
        )
        assertEquals(
            "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/komik/panels/p01.jpg.enc",
            built.imageUrls["komik/panels/p01.jpg"]
        )
    }

    @Test
    fun buildAll_cipherRusak_gagalTotal() {
        val dump = fakeDump()
        // Rusak cipher episode pertama (bukan base64 valid).
        dump.getJSONArray("episodes").getJSONObject(0).put("isi_cipher", "!!!bukan-base64!!!")
        assertNull(D1ContentBuilder.buildAll(dump, ::decryptWithTestKey))
    }

    @Test
    fun buildAll_panelsRusak_gagalTotal() {
        val dump = fakeDump()
        dump.getJSONArray("komik_episodes").getJSONObject(0)
            .put("panels_cipher", cipher("bukan-json-array"))
        assertNull(D1ContentBuilder.buildAll(dump, ::decryptWithTestKey))
    }

    @Test
    fun fileNameFromEncUrl() {
        assertEquals(
            "cover_01.jpg",
            D1ContentBuilder.fileNameFromEncUrl(
                "https://raw.githubusercontent.com/hozin-king/cerpen-hozin/main/data/covers/cover_01.jpg.enc"
            )
        )
        assertEquals("", D1ContentBuilder.fileNameFromEncUrl(""))
        assertEquals("p01.jpg", D1ContentBuilder.fileNameFromEncUrl("p01.jpg.enc"))
    }
}

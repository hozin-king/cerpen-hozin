package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji helper murni v1.1.5: enkripsi on-demand, tanggal/NEW, penyebaran gambar. */
class OfflineDownloadTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `encrypt lalu decrypt kembali ke plaintext yang sama`() {
        val plain = "gambar-jpeg-palsu".toByteArray(Charsets.UTF_8)
        val enc = ContentCrypto.encrypt(plain, key) ?: error("encrypt null")
        // Format .enc: IV 12 byte + ciphertext + tag 16 byte.
        assertTrue(enc.size > plain.size + 12 + 16 - 1)
        assertEquals(12, enc.size - (plain.size + 16))
        val back = ContentCrypto.decrypt(enc, key) ?: error("decrypt null")
        assertEquals(String(plain, Charsets.UTF_8), String(back, Charsets.UTF_8))
    }

    @Test
    fun `encrypt menolak kunci bukan 32 byte`() {
        assertEquals(null, ContentCrypto.encrypt("x".toByteArray(), ByteArray(16)))
    }

    @Test
    fun `decryptVerifiedBytes cocok dengan sha dari encrypt`() {
        val plain = "abc123".toByteArray(Charsets.UTF_8)
        val enc = ContentCrypto.encrypt(plain, key) ?: error("encrypt null")
        val sha = ContentCrypto.sha256Hex(plain)
        val back = ContentCrypto.decryptVerifiedBytes(enc, key, sha)
        assertEquals("abc123", back?.let { String(it, Charsets.UTF_8) })
        // SHA salah -> ditolak.
        assertEquals(null, ContentCrypto.decryptVerifiedBytes(enc, key, "00".repeat(32)))
    }

    @Test
    fun `format tanggal Indonesia`() {
        assertEquals("9 Nov 2026", ItemDates.format("2026-11-09"))
        assertEquals("1 Jan 2026", ItemDates.format("2026-01-01"))
        assertEquals("31 Des 2025", ItemDates.format("2025-12-31"))
        assertEquals("bukan-tanggal", ItemDates.format("bukan-tanggal"))
        assertEquals("", ItemDates.format(""))
    }

    @Test
    fun `isNew benar dalam 24 jam dari addedAt`() {
        val now = java.time.OffsetDateTime.parse("2026-11-09T20:00:00+07:00")
            .toInstant().toEpochMilli()
        // 6 jam lalu -> NEW.
        assertTrue(ItemDates.isNew("2026-11-09T14:00:00+07:00", "", now))
        // 30 jam lalu -> bukan NEW.
        assertFalse(ItemDates.isNew("2026-11-08T14:00:00+07:00", "", now))
        // Masa depan -> bukan NEW.
        assertFalse(ItemDates.isNew("2026-11-10T14:00:00+07:00", "", now))
        // addedAt rusak -> bukan NEW.
        assertFalse(ItemDates.isNew("rusak", "", now))
    }

    @Test
    fun `newestId mengambil angka akhir terbesar`() {
        assertEquals("cerpen-30", ItemDates.newestId(listOf("cerpen-9", "cerpen-30", "cerpen-2")))
        assertEquals("komik-10", ItemDates.newestId(listOf("komik-2", "komik-10")))
        assertEquals(null, ItemDates.newestId(emptyList()))
    }

    @Test
    fun `trailingNumber untuk sorting terbaru dulu`() {
        assertEquals(30, ItemDates.trailingNumber("cerpen-30"))
        assertEquals(0, ItemDates.trailingNumber("tanpa-angka"))
    }

    @Test
    fun `penyebaran gambar merata antar paragraf`() {
        // 6 blok, 2 gambar -> setelah blok ke-2 dan ke-4 (indeks 1 dan 3).
        assertEquals(listOf(1, 3), NewsBodyLayout.insertAfter(6, 2))
        // 1 blok, 1 gambar -> setelah blok satu-satunya.
        assertEquals(listOf(0), NewsBodyLayout.insertAfter(1, 1))
        // 3 blok, 3 gambar -> setelah tiap blok.
        assertEquals(listOf(0, 1, 2), NewsBodyLayout.insertAfter(3, 3))
        // Tidak ada blok / tidak ada gambar -> kosong.
        assertEquals(emptyList<Int>(), NewsBodyLayout.insertAfter(0, 2))
        assertEquals(emptyList<Int>(), NewsBodyLayout.insertAfter(5, 0))
    }

    @Test
    fun `parseNews membaca image_urls dan added_at`() {
        val json = JSONObject(
            """{"articles": [{"title": "T", "summary": "S", "source": "X",
            |"date": "2026-11-09", "image": "img/a.jpg",
            |"images": ["img/a.jpg", "img/b.jpg"],
            |"image_urls": ["https://cdn.example/a.jpg"],
            |"added_at": "2026-11-09T14:23:00+07:00",
            |"details": "isi"}]}""".trimMargin()
        )
        // parseNews butuh teks JSON utuh.
        val list = ContentLoader.parseNews(json.toString())
        assertEquals(1, list.size)
        assertEquals(listOf("https://cdn.example/a.jpg"), list[0].imageUrls)
        assertEquals("2026-11-09T14:23:00+07:00", list[0].addedAt)
        assertEquals(listOf("img/a.jpg", "img/b.jpg"), list[0].images)
    }

    @Test
    fun `parseCerpen dan parseKomik tahan tanpa field date`() {
        val cerpen = ContentLoader.parseCerpen(
            """{"cerpen": [{"id": "cerpen-1", "judul": "J", "genre": "G",
            |"deskripsi": "D", "cover": "cover_01.jpg", "seasons": []}]}""".trimMargin()
        )
        assertEquals("", cerpen[0].date)
        val komik = ContentLoader.parseKomik(
            """{"komik": [{"id": "komik-1", "judul": "J", "genre": "G",
            |"deskripsi": "D", "cover": "k.jpg", "episodes": []}]}""".trimMargin()
        )
        assertEquals("", komik[0].date)
    }
}

package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Uji ContentCrypto + format .enc lintas bahasa (v1.2, enkripsi semua aset).
 *
 * Fixture di `src/test/resources/` dibuat oleh Python (cryptography/AESGCM)
 * dengan KUNCI TEST sekali-pakai (`test-only-key.b64`, random, BUKAN kunci
 * konten asli): manifest_fixture.json.enc + image_fixture.jpg.enc.
 * Test ini membuktikan blob yang dienkripsi pipeline Python bisa
 * didekripsi + diverifikasi SHA oleh Kotlin — dan sebaliknya (round-trip
 * enkripsi Kotlin -> dekripsi Kotlin).
 *
 * v1.1.4: ditambah uji lapisan gzip pada .enc JSON (gunzip setelah
 * verifikasi SHA di `decryptVerified`; gambar via `decryptVerifiedBytes`
 * tidak ikut ter-gunzip).
 */
class ContentCryptoTest {

    private fun loadResource(name: String): ByteArray =
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes()
            ?: error("fixture $name tidak ditemukan")

    /** Kunci TEST sekali-pakai — bukan kunci konten Cerpen. */
    private fun testKey(): ByteArray =
        Base64.getDecoder().decode(
            loadResource("test-only-key.b64").toString(Charsets.UTF_8).trim()
        )

    @Test
    fun `manifest fixture hasil enkripsi Python bisa didekripsi Kotlin`() {
        val key = testKey()
        val enc = loadResource("manifest_fixture.json.enc")
        val plain = ContentCrypto.decrypt(enc, key)
        assertNotNull("manifest .enc gagal didekripsi", plain)
        val manifest = JSONObject(String(plain!!, Charsets.UTF_8))
        val cerpen = manifest.optJSONObject("cerpen.json")
        assertNotNull("entri cerpen.json hilang", cerpen)
        assertEquals("cerpen.json.enc", cerpen!!.optString("file"))
        val cover = manifest.optJSONObject("covers/cover_01.jpg")
        assertNotNull("entri cover hilang", cover)
        assertEquals("covers/cover_01.jpg.enc", cover!!.optString("file"))
    }

    @Test
    fun `gambar fixture terverifikasi SHA lawan manifest`() {
        val key = testKey()
        val manifest = JSONObject(
            String(ContentCrypto.decrypt(loadResource("manifest_fixture.json.enc"), key)!!, Charsets.UTF_8)
        )
        val entry = manifest.getJSONObject("covers/cover_01.jpg")
        val sha = entry.getString("sha256")
        val enc = loadResource("image_fixture.jpg.enc")
        val bytes = ContentCrypto.decryptVerifiedBytes(enc, key, sha)
        assertNotNull("gambar .enc gagal verifikasi", bytes)
        // Header JPEG SOI + APP0 — payload fixture memang diawali itu.
        assertEquals(0xFF.toByte(), bytes!![0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xE0.toByte(), bytes[3])
        assertEquals(sha, ContentCrypto.sha256Hex(bytes))
    }

    @Test
    fun `ciphertext yang dirusak ditolak (tag GCM gagal)`() {
        val key = testKey()
        val enc = loadResource("image_fixture.jpg.enc").copyOf()
        enc[30] = (enc[30].toInt() xor 0x01).toByte() // rusak 1 byte ciphertext
        assertNull(ContentCrypto.decrypt(enc, key))
    }

    @Test
    fun `SHA salah ditolak walau dekripsi sukses`() {
        val key = testKey()
        val enc = loadResource("image_fixture.jpg.enc")
        val wrongSha = "00".repeat(32)
        assertNull(ContentCrypto.decryptVerifiedBytes(enc, key, wrongSha))
    }

    @Test
    fun `round-trip enkripsi Kotlin ke dekripsi Kotlin`() {
        val key = testKey()
        val plain = "halo cerpen terenkripsi".toByteArray(Charsets.UTF_8)
        val blob = encryptBlob(plain, key)
        val back = ContentCrypto.decrypt(blob, key)
        assertNotNull(back)
        assertTrue(plain.contentEquals(back!!))
        assertEquals(
            ContentCrypto.sha256Hex(plain),
            ContentCrypto.sha256Hex(back)
        )
    }

    // ---- v1.1.4: lapisan gzip pada .enc JSON ----

    /** Enkripsi satu blob format .enc (IV 12B + ciphertext+tag) dari plaintext. */
    private fun encryptBlob(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(12) { (it * 7 + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        return iv + cipher.doFinal(plain)
    }

    /** gzip (level default; magic 0x1F 0x8B sama seperti gzip.compress Python). */
    private fun gzipBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun `gzip lalu enkripsi - decryptVerified mengembalikan JSON asli`() {
        val key = testKey()
        val json = """{"cerpen":[{"id":"c1","judul":"Tes Gzip","genre":"Misteri"}]}"""
        val gzipped = gzipBytes(json.toByteArray(Charsets.UTF_8))
        // Magic gzip di 2 byte awal, seperti output gzip.compress Python.
        assertEquals(0x1F.toByte(), gzipped[0])
        assertEquals(0x8B.toByte(), gzipped[1])
        val enc = encryptBlob(gzipped, key)
        // Manifest v1.1.4 menyimpan SHA-256 dari byte TER-GZIP.
        val text = ContentCrypto.decryptVerified(enc, key, ContentCrypto.sha256Hex(gzipped))
        assertNotNull("JSON ter-gzip gagal didekripsi / di-gunzip", text)
        assertEquals(json, text)
        assertEquals("c1", JSONObject(text!!).getJSONArray("cerpen").getJSONObject(0).getString("id"))
    }

    @Test
    fun `enc lama tanpa gzip tetap ter-parse`() {
        val key = testKey()
        val json = """{"news":[{"id":"n1","judul":"Berita Lama"}]}"""
        val plain = json.toByteArray(Charsets.UTF_8)
        val enc = encryptBlob(plain, key)
        val text = ContentCrypto.decryptVerified(enc, key, ContentCrypto.sha256Hex(plain))
        assertNotNull(".enc lama (tanpa gzip) gagal dibaca", text)
        assertEquals(json, text)
    }

    @Test
    fun `SHA dihitung dari byte ter-gzip bukan plaintext JSON`() {
        val key = testKey()
        val json = """{"cerpen":[]}"""
        val gzipped = gzipBytes(json.toByteArray(Charsets.UTF_8))
        val enc = encryptBlob(gzipped, key)
        // SHA dari JSON polos (bukan dari byte ter-gzip) HARUS ditolak.
        assertNull(
            ContentCrypto.decryptVerified(enc, key, ContentCrypto.sha256Hex(json.toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun `magic gzip ada tapi isi rusak ditolak`() {
        val key = testKey()
        val corrupt = byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08, 0x00, 0x11, 0x22, 0x33, 0x44)
        val enc = encryptBlob(corrupt, key)
        assertNull(ContentCrypto.decryptVerified(enc, key, ContentCrypto.sha256Hex(corrupt)))
    }

    @Test
    fun `gambar tidak ikut ter-gunzip walau diawali magic gzip`() {
        val key = testKey()
        // Byte gambar hipotetis yang kebetulan diawali 0x1F 0x8B.
        val image = byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08, 0x00) + "bukan-gzip".toByteArray(Charsets.UTF_8)
        val enc = encryptBlob(image, key)
        val bytes = ContentCrypto.decryptVerifiedBytes(enc, key, ContentCrypto.sha256Hex(image))
        assertNotNull("gambar gagal verifikasi", bytes)
        assertTrue("decryptVerifiedBytes harus mengembalikan byte apa adanya", image.contentEquals(bytes!!))
    }
}

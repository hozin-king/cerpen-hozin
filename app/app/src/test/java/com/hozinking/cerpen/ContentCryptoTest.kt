package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
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
        val iv = ByteArray(12) { (it * 7 + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val ct = cipher.doFinal(plain)
        val blob = iv + ct // format .enc: IV 12B + ciphertext+tag
        val back = ContentCrypto.decrypt(blob, key)
        assertNotNull(back)
        assertTrue(plain.contentEquals(back!!))
        assertEquals(
            ContentCrypto.sha256Hex(plain),
            ContentCrypto.sha256Hex(back)
        )
    }
}

package com.hozinking.cerpen

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Uji decodeD1BlobBytes (v1.1.37): Worker /api/cerpen-img/
 * mengembalikan BLOB D1 sebagai teks array desimal ("255,35,253,...")
 * — parser harus mengembalikannya jadi byte .enc asli, dan
 * mengembalikan body APA ADANYA bila bukan format itu (byte mentah
 * dari Worker yang sudah diperbaiki, JSON error, dsb).
 */
class D1BlobDecodeTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `teks array desimal di-parse jadi byte`() {
        assertArrayEquals(
            byteArrayOf(255.toByte(), 35, 253.toByte()),
            decodeD1BlobBytes(bytes("255,35,253"))
        )
    }

    @Test
    fun `satu angka saja tetap di-parse`() {
        assertArrayEquals(byteArrayOf(0), decodeD1BlobBytes(bytes("0")))
    }

    @Test
    fun `whitespace pinggir ditoleransi`() {
        assertArrayEquals(
            byteArrayOf(255.toByte(), 35),
            decodeD1BlobBytes(bytes("  255,35\n"))
        )
    }

    @Test
    fun `byte mentah dikembalikan apa adanya`() {
        // IV acak + ciphertext: ada byte di luar digit/koma.
        val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x2C, 0x31, 0x00, 0xFF.toByte())
        assertArrayEquals(raw, decodeD1BlobBytes(raw))
    }

    @Test
    fun `nilai di atas 255 bukan format array`() {
        val input = bytes("255,35,256")
        assertArrayEquals(input, decodeD1BlobBytes(input))
    }

    @Test
    fun `token kosong bukan format array`() {
        val input = bytes("255,,35")
        assertArrayEquals(input, decodeD1BlobBytes(input))
    }

    @Test
    fun `koma akhir bukan format array`() {
        val input = bytes("12,34,")
        assertArrayEquals(input, decodeD1BlobBytes(input))
    }

    @Test
    fun `body kosong dikembalikan apa adanya`() {
        val input = ByteArray(0)
        assertArrayEquals(input, decodeD1BlobBytes(input))
    }

    @Test
    fun `teks biasa bukan format array`() {
        val input = bytes("{\"ok\":false}")
        assertArrayEquals(input, decodeD1BlobBytes(input))
    }
}

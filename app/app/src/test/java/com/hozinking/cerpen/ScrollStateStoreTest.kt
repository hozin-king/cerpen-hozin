package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Uji ScrollStateStore (v1.1.38) — fungsi murni (encode/decode/clamp).
 * capture/apply/save/load memakai LinearLayoutManager / SharedPreferences
 * (Android) jadi tidak diuji di JVM; perilaku restore diuji di POCO
 * (gulir ke tengah daftar Berita → pindah tab → kembali → posisi sama).
 */
class ScrollStateStoreTest {

    @Test
    fun `encode decode round-trip`() {
        val pos = ScrollPosition(firstVisible = 12, offsetPx = 37)
        assertEquals(pos, ScrollStateStore.decode(ScrollStateStore.encode(pos)))
    }

    @Test
    fun `encode format pos offset`() {
        assertEquals("7:120", ScrollStateStore.encode(ScrollPosition(7, 120)))
    }

    @Test
    fun `decode sampah menghasilkan null`() {
        assertNull(ScrollStateStore.decode(null))
        assertNull(ScrollStateStore.decode(""))
        assertNull(ScrollStateStore.decode("abc"))
        assertNull(ScrollStateStore.decode("12"))
        assertNull(ScrollStateStore.decode("12:"))
        assertNull(ScrollStateStore.decode(":5"))
        assertNull(ScrollStateStore.decode("1:2:3"))
        assertNull(ScrollStateStore.decode("x:5"))
        assertNull(ScrollStateStore.decode("5:y"))
    }

    @Test
    fun `decode posisi nol valid`() {
        assertEquals(
            ScrollPosition(0, 0),
            ScrollStateStore.decode("0:0")
        )
    }

    @Test
    fun `clamp posisi normal tidak berubah`() {
        assertEquals(
            ScrollPosition(5, 10),
            ScrollStateStore.clamp(ScrollPosition(5, 10), itemCount = 20)
        )
    }

    @Test
    fun `clamp posisi di luar jumlah item ke item terakhir offset tetap`() {
        assertEquals(
            ScrollPosition(9, 30),
            ScrollStateStore.clamp(ScrollPosition(50, 30), itemCount = 10)
        )
    }

    @Test
    fun `clamp daftar kosong menghasilkan null`() {
        assertNull(
            ScrollStateStore.clamp(ScrollPosition(0, 0), itemCount = 0)
        )
    }

    @Test
    fun `clamp posisi negatif ke nol offset tetap`() {
        assertEquals(
            ScrollPosition(0, -10),
            ScrollStateStore.clamp(ScrollPosition(-3, -10), itemCount = 10)
        )
    }
}

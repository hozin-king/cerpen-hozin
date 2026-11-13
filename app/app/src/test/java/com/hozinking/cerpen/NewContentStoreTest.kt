package com.hozinking.cerpen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logika jendela BARU 7 hari (v1.1.26): murni perbandingan waktu,
 * tanpa Android — first-seen 0 = belum pernah terlihat = bukan baru.
 */
class NewContentStoreTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_793_000_000_000L

    @Test
    fun baru_dalam7Hari() {
        assertTrue(NewContentStore.isNew(now - 1_000, now))
        assertTrue(NewContentStore.isNew(now - 6 * day, now))
    }

    @Test
    fun batas7HariPas_masihBaru() {
        assertTrue(NewContentStore.isNew(now - 7 * day, now))
    }

    @Test
    fun lebihDari7Hari_bukanBaru() {
        assertFalse(NewContentStore.isNew(now - 7 * day - 1, now))
        assertFalse(NewContentStore.isNew(now - 30 * day, now))
    }

    @Test
    fun tanpaFirstSeen_bukanBaru() {
        assertFalse(NewContentStore.isNew(0L, now))
    }

    @Test
    fun firstSeenDiMasaDepan_bukanBaru() {
        assertFalse(NewContentStore.isNew(now + day, now))
    }
}

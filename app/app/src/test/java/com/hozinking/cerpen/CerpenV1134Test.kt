package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test v1.1.34 (tanpa Android): sinkron bookmark ikut akun (fungsi murni). */
class CerpenV1134Test {

    @Test
    fun antrean_encodeParse_roundTrip() {
        val enc = BookmarkSync.encodePending(true, "cerpen", "cerpen-66")
        assertEquals(BookmarkSync.PendingOp(true, "cerpen", "cerpen-66"), BookmarkSync.parsePending(enc))
        val enc2 = BookmarkSync.encodePending(false, "berita", "Judul | Dengan Pipa")
        assertEquals(BookmarkSync.PendingOp(false, "berita", "Judul | Dengan Pipa"), BookmarkSync.parsePending(enc2))
    }

    @Test
    fun antrean_parseRusak_ditolak() {
        assertNull(BookmarkSync.parsePending("tanpa-pemisah"))
        assertNull(BookmarkSync.parsePending("X|cerpen|cerpen-66"))
        assertNull(BookmarkSync.parsePending("A||cerpen-66"))
        assertNull(BookmarkSync.parsePending("A|cerpen|"))
        assertNull(BookmarkSync.parsePending(""))
    }

    @Test
    fun antrean_tambahLaluHapus_batal() {
        val afterAdd = BookmarkSync.togglePending(emptySet(), true, "cerpen", "cerpen-66")
        assertEquals(setOf("A|cerpen|cerpen-66"), afterAdd)
        val afterDel = BookmarkSync.togglePending(afterAdd, false, "cerpen", "cerpen-66")
        assertEquals(setOf("D|cerpen|cerpen-66"), afterDel)
    }

    @Test
    fun antrean_operasiLawanDiganti_bukanDobel() {
        val start = setOf("A|komik|komik-10", "A|cerpen|cerpen-66")
        val out = BookmarkSync.togglePending(start, false, "komik", "komik-10")
        assertEquals(setOf("A|cerpen|cerpen-66", "D|komik|komik-10"), out)
    }

    @Test
    fun parseRemoteList_valid() {
        val json = JSONObject(
            """{"ok":true,"bookmarks":[
                {"kind":"cerpen","content_id":"cerpen-66","created_at":1794670000000},
                {"kind":"komik","content_id":"komik-10","created_at":1794670001000},
                {"kind":"","content_id":"rusak"},
                {"kind":"berita","content_id":""},
                {"kind":"berita","content_id":"Judul Berita","created_at":1794670002000}
            ]}"""
        )
        val list = BookmarkSync.parseRemoteList(json)
        assertEquals(
            listOf(
                ProgressStore.SeriesBookmark("cerpen", "cerpen-66"),
                ProgressStore.SeriesBookmark("komik", "komik-10"),
                ProgressStore.SeriesBookmark("berita", "Judul Berita")
            ),
            list
        )
    }

    @Test
    fun parseRemoteList_takValid_null() {
        assertNull(BookmarkSync.parseRemoteList(null))
        assertNull(BookmarkSync.parseRemoteList(JSONObject("""{"ok":false}""")))
        assertEquals(emptyList<ProgressStore.SeriesBookmark>(), BookmarkSync.parseRemoteList(JSONObject("""{"ok":true}""")))
    }

    @Test
    fun union_lokalTidakPernahDihapus() {
        val local = listOf(
            ProgressStore.SeriesBookmark("cerpen", "cerpen-66"),
            ProgressStore.SeriesBookmark("komik", "komik-10")
        )
        val remote = listOf(
            ProgressStore.SeriesBookmark("komik", "komik-10"),
            ProgressStore.SeriesBookmark("berita", "news-1")
        )
        val merged = BookmarkSync.union(local, remote)
        assertEquals(
            listOf(
                ProgressStore.SeriesBookmark("cerpen", "cerpen-66"),
                ProgressStore.SeriesBookmark("komik", "komik-10"),
                ProgressStore.SeriesBookmark("berita", "news-1")
            ),
            merged
        )
        // Lokal yang tidak ada di server tetap ada (union, bukan replace).
        assertTrue(merged.contains(ProgressStore.SeriesBookmark("cerpen", "cerpen-66")))
    }

    @Test
    fun union_kosong() {
        assertEquals(emptyList<ProgressStore.SeriesBookmark>(), BookmarkSync.union(emptyList(), emptyList()))
    }
}

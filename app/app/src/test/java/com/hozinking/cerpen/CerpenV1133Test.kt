package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit test v1.1.33 (tanpa Android): kunci + parse bookmark konten (series). */
class CerpenV1133Test {

    @Test
    fun kunciGabungKindDanId() {
        assertEquals("cerpen|cerpen-66", ProgressStore.seriesBookmarkKey("cerpen", "cerpen-66"))
        assertEquals("komik|komik-10", ProgressStore.seriesBookmarkKey("komik", "komik-10"))
        assertEquals("berita|news-ff3a02", ProgressStore.seriesBookmarkKey("berita", "news-ff3a02"))
    }

    @Test
    fun parseKunciValid() {
        assertEquals(
            ProgressStore.SeriesBookmark("cerpen", "cerpen-66"),
            ProgressStore.parseSeriesBookmark("cerpen|cerpen-66")
        )
        assertEquals(
            ProgressStore.SeriesBookmark("berita", "Judul | Dengan Pipa"),
            ProgressStore.parseSeriesBookmark("berita|Judul | Dengan Pipa")
        )
    }

    @Test
    fun parseKunciRusak_ditolak() {
        assertNull(ProgressStore.parseSeriesBookmark("tanpa-pemisah"))
        assertNull(ProgressStore.parseSeriesBookmark("cerpen|"))
        assertNull(ProgressStore.parseSeriesBookmark("|cerpen-66"))
        assertNull(ProgressStore.parseSeriesBookmark(""))
    }

    @Test
    fun roundTrip_kunci_parse() {
        val key = ProgressStore.seriesBookmarkKey("komik", "komik-10")
        assertEquals(
            ProgressStore.SeriesBookmark("komik", "komik-10"),
            ProgressStore.parseSeriesBookmark(key)
        )
    }
}

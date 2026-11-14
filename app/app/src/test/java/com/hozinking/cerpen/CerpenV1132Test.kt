package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test v1.1.32 (tanpa Android): waktu relatif, format ala YouTube, anti beli dobel. */
class CerpenV1132Test {

    private fun epoch(iso: String): Long =
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun relativeTimeAddedAt() {
        val now = epoch("2026-11-14T11:00:00+07:00")
        assertEquals("baru saja", ItemDates.relativeTime("2026-11-14T10:59:30+07:00", "", now))
        assertEquals("5 menit lalu", ItemDates.relativeTime("2026-11-14T10:55:00+07:00", "", now))
        assertEquals("1 jam lalu", ItemDates.relativeTime("2026-11-14T10:00:00+07:00", "", now))
        assertEquals("10 jam lalu", ItemDates.relativeTime("2026-11-14T01:00:00+07:00", "", now))
        assertEquals("3 hari lalu", ItemDates.relativeTime("2026-11-11T11:00:00+07:00", "", now))
        assertEquals("2 minggu lalu", ItemDates.relativeTime("2026-10-31T11:00:00+07:00", "", now))
        assertNull(ItemDates.relativeTime("2026-11-14T12:00:00+07:00", "", now))
        assertNull(ItemDates.relativeTime("", "", now))
        assertNull(ItemDates.relativeTime("bukan-tanggal", "juga-bukan", now))
    }

    @Test
    fun relativeTimeDateOnly() {
        val today = java.time.LocalDate.now().toString()
        val threeDaysAgo = java.time.LocalDate.now().minusDays(3).toString()
        assertEquals("hari ini", ItemDates.relativeTime("", today))
        assertEquals("3 hari lalu", ItemDates.relativeTime("", threeDaysAgo))
        assertNull(ItemDates.relativeTime("", ""))
    }

    @Test
    fun readerLine() {
        assertEquals(
            "206 suka · 12 rb x dibaca · 1 jam lalu",
            SocialApi.readerLine(206, 12_000, "1 jam lalu")
        )
        assertEquals("5 suka · 999 x dibaca", SocialApi.readerLine(5, 999, null))
        assertEquals(
            "1 suka · 2,5 jt x dibaca",
            SocialApi.readerLine(1, 2_500_000, "")
        )
    }

    @Test
    fun feedLine() {
        assertEquals(
            "Levelup iD ▷ 16 rb dibaca · 10 jam lalu",
            SocialApi.feedLine("Levelup iD", 16_000, "10 jam lalu")
        )
        assertEquals("16 rb dibaca · 10 jam lalu", SocialApi.feedLine(null, 16_000, "10 jam lalu"))
        assertEquals("500 dibaca", SocialApi.feedLine("", 500, null))
        assertEquals("X ▷ 500 dibaca", SocialApi.feedLine("X", 500, null))
    }

    @Test
    fun isAlreadyOwnedAcceptsBothFields() {
        assertTrue(CoinApi.isAlreadyOwned(JSONObject("""{"ok":true,"already":true}""")))
        assertTrue(CoinApi.isAlreadyOwned(JSONObject("""{"ok":true,"already_owned":true}""")))
        assertEquals(false, CoinApi.isAlreadyOwned(JSONObject("""{"ok":true}""")))
        assertEquals(false, CoinApi.isAlreadyOwned(JSONObject("""{"already":false}""")))
        assertEquals(false, CoinApi.isAlreadyOwned(null))
    }
}

package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji ShareLink (tautan berbagi + deep link) — v1.1.20.
 * Hanya fungsi murni (parse/build/ekstrak token); pemanggilan
 * jaringan TIDAK diuji di sini (butuh Worker live).
 */
class ShareLinkTest {

    // ---- extractTokenFromPath ----

    @Test
    fun extractToken_jalurBenar() {
        assertEquals("abc123", ShareLink.extractTokenFromPath("/s/abc123"))
    }

    @Test
    fun extractToken_bukanJalurShare() {
        assertNull(ShareLink.extractTokenFromPath("/cerpen/cerpen-1"))
        assertNull(ShareLink.extractTokenFromPath("/s"))
        assertNull(ShareLink.extractTokenFromPath("/s/"))
        assertNull(ShareLink.extractTokenFromPath("/x/abc123"))
        assertNull(ShareLink.extractTokenFromPath("/s/a/b"))
        assertNull(ShareLink.extractTokenFromPath(null))
        assertNull(ShareLink.extractTokenFromPath(""))
    }

    // ---- parseResolve ----

    @Test
    fun parseResolve_lengkap() {
        val r = ShareLink.parseResolve(
            """{"content_type":"cerpen","content_id":"cerpen-55","season":2,"episode_no":7}"""
        )
        assertEquals("cerpen", r?.contentType)
        assertEquals("cerpen-55", r?.contentId)
        assertEquals(2, r?.season)
        assertEquals(7, r?.episodeNo)
    }

    @Test
    fun parseResolve_tanpaSeasonEpisode() {
        val r = ShareLink.parseResolve(
            """{"content_type":"komik","content_id":"komik-3"}"""
        )
        assertEquals("komik", r?.contentType)
        assertEquals("komik-3", r?.contentId)
        assertNull(r?.season)
        assertNull(r?.episodeNo)
    }

    @Test
    fun parseResolve_tidakValid() {
        assertNull(ShareLink.parseResolve("""{"content_type":"cerpen"}"""))
        assertNull(ShareLink.parseResolve("""{"content_id":"cerpen-1"}"""))
        assertNull(ShareLink.parseResolve("bukan json"))
        assertNull(ShareLink.parseResolve(""))
    }

    // ---- parseCreateResponse ----

    @Test
    fun parseCreateResponse_urlBenar() {
        assertEquals(
            "https://hozinking.my.id/s/xyz",
            ShareLink.parseCreateResponse("""{"url":"https://hozinking.my.id/s/xyz"}""")
        )
    }

    @Test
    fun parseCreateResponse_tidakValid() {
        assertNull(ShareLink.parseCreateResponse("""{"error":"gagal"}"""))
        assertNull(ShareLink.parseCreateResponse("bukan json"))
    }

    // ---- buildCreateBody ----

    @Test
    fun buildCreateBody_fieldLengkap() {
        val o = JSONObject(
            ShareLink.buildCreateBody("cerpen", "cerpen-55", 2, 7, "POCO X6 Pro")
        )
        assertEquals("cerpen", o.getString("content_type"))
        assertEquals("cerpen-55", o.getString("content_id"))
        assertEquals(2, o.getInt("season"))
        assertEquals(7, o.getInt("episode_no"))
        assertEquals("POCO X6 Pro", o.getString("device"))
    }

    @Test
    fun buildCreateBody_tanpaSeasonEpisode() {
        val o = JSONObject(
            ShareLink.buildCreateBody("berita", "Judul Berita", null, null, "Android")
        )
        assertEquals("berita", o.getString("content_type"))
        assertEquals("Judul Berita", o.getString("content_id"))
        assertTrue(!o.has("season"))
        assertTrue(!o.has("episode_no"))
    }
}

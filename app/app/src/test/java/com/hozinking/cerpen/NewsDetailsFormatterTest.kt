package com.hozinking.cerpen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji NewsDetailsFormatter + parseNews dengan details ASLI artikel id 280. */
class NewsDetailsFormatterTest {

    private fun article280Details(): JSONObject {
        val text = javaClass.classLoader
            ?.getResourceAsStream("article280_details.json")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("fixture article280_details.json tidak ditemukan")
        return JSONObject(text)
    }

    @Test
    fun `details objek artikel 280 jadi teks rapi bukan JSON mentah`() {
        val out = NewsDetailsFormatter.format(article280Details())

        // Tidak ada sisa JSON mentah / escape org.json.
        assertFalse(out.contains("{\"video_sumber\""))
        assertFalse(out.contains("\\\""))
        assertFalse(out.contains("\\/"))
        assertFalse(out.contains("{\"bank\""))

        // Judul seksi yang diharapkan ada.
        assertTrue(out.contains("Prancis"))
        assertTrue(out.contains("Sumber Verifikasi"))
        assertTrue(out.contains("Tips Aman"))
        assertTrue(out.contains("Sumber Video"))
        assertTrue(out.contains("Imbauan BSSN"))
        assertTrue(out.contains("Malware Hirudo"))
        assertTrue(out.contains("Klarifikasi: Bank Indonesia"))
        assertTrue(out.contains("Status Verifikasi"))

        // Dict bersarang jadi "label: isi".
        assertTrue(out.contains("Bank: Crédit Mutuel Arkéa"))
        assertTrue(out.contains("Kerugian: Sekitar €100 juta"))

        // List sumber_verifikasi jadi bullet.
        assertTrue(out.contains("• Video: https://www.tiktok.com/@sinauaset/video/7705647758837075208"))
    }

    @Test
    fun `parseNews memakai formatter untuk details objek`() {
        val details = article280Details().toString()
        val json = JSONObject().apply {
            put("articles", JSONArray().apply {
                put(JSONObject().apply {
                    put("title", "T")
                    put("summary", "S")
                    put("source", "X")
                    put("date", "2026-11-08")
                    put("details", JSONObject(details))
                })
            })
        }
        val articles = ContentLoader.parseNews(json.toString())
        assertEquals(1, articles.size)
        val out = articles[0].details
        assertFalse(out.contains("{\"video_sumber\""))
        assertTrue(out.contains("Prancis"))
        assertTrue(out.contains("• Video: https://www.tiktok.com/@sinauaset/video/7705647758837075208"))
    }

    @Test
    fun `details string dipakai apa adanya`() {
        assertEquals("Isi biasa.", NewsDetailsFormatter.format("Isi biasa."))
    }

    @Test
    fun `details null atau kosong jadi string kosong`() {
        assertEquals("", NewsDetailsFormatter.format(null))
        assertEquals("", NewsDetailsFormatter.format(JSONObject.NULL))
        assertEquals("", NewsDetailsFormatter.format(JSONObject().apply { put("a", JSONObject.NULL) }))
    }

    @Test
    fun `details array jadi daftar bullet`() {
        val out = NewsDetailsFormatter.format(JSONArray("[\"satu\", \"dua\"]"))
        assertEquals("• satu\n• dua", out)
    }

    @Test
    fun `key tak dikenal di-humanize`() {
        assertEquals("Bukan Serangan Siber", NewsDetailsFormatter.sectionTitle("bukan_serangan_siber"))
        val out = NewsDetailsFormatter.format(JSONObject("{\"hal_baru\": \"isi\"}"))
        assertTrue(out.contains("Hal Baru\nisi"))
    }
}

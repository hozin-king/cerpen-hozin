package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test RemoteConfig (v1.1.14): parse fail-closed, tanpa Android. */
class RemoteConfigTest {

    @Test
    fun parseDefaultJsonEqualsDefault() {
        val json = """{"version":1,"tabs":[
            {"id":"cerbung","label":"Cerita","visible":true},
            {"id":"berita","label":"Berita","visible":true},
            {"id":"komik","label":"Komik","visible":true},
            {"id":"koleksi","label":"Koleksi","visible":true}],
            "banner":"","accent":""}"""
        assertEquals(RemoteConfig.DEFAULT, RemoteConfig.parse(json))
    }

    @Test
    fun parseBrokenJsonReturnsNull() {
        assertNull(RemoteConfig.parse("{bukan json"))
        assertNull(RemoteConfig.parse(""))
        assertNull(RemoteConfig.parse(null))
        assertNull(RemoteConfig.parse("[1,2,3]"))
    }

    @Test
    fun unknownTabIdIgnoredAndMissingTabKeptWithDefaultLabel() {
        val json = """{"tabs":[
            {"id":"ngawur","label":"X","visible":true},
            {"id":"komik","label":"Komikku","visible":false}]}"""
        val c = RemoteConfig.parse(json)!!
        assertEquals(listOf("komik", "cerbung", "berita", "koleksi"), c.tabs.map { it.id })
        assertEquals("Komikku", c.tabs[0].label)
        assertEquals(false, c.tabs[0].visible)
        assertEquals("Cerita", c.tabs[1].label) // hilang dari config -> bawaan
        assertTrue(c.tabs.drop(1).all { it.visible })
    }

    @Test
    fun emptyLabelFallsBackToDefaultAndEmptyTabsMeansDefault() {
        val json = """{"tabs":[{"id":"berita","label":"","visible":true}],"banner":"x","accent":""}"""
        val c = RemoteConfig.parse(json)!!
        assertEquals("Berita", c.tabs[0].label)
        assertEquals(RemoteConfig.DEFAULT.tabs, RemoteConfig.parse("""{"tabs":[]}""")!!.tabs)
        assertEquals(RemoteConfig.DEFAULT.tabs, RemoteConfig.parse("""{}""")!!.tabs)
    }

    @Test
    fun invalidAccentBecomesEmptyValidAccentParses() {
        assertEquals("", RemoteConfig.parse("""{"accent":"red"}""")!!.accent)
        assertEquals("", RemoteConfig.parse("""{"accent":"#12345"}""")!!.accent)
        assertEquals("", RemoteConfig.parse("""{"accent":"#GGGGGG"}""")!!.accent)
        val c = RemoteConfig.parse("""{"accent":"#1A2B3C"}""")!!
        assertEquals("#1A2B3C", c.accent)
        assertEquals(0xFF1A2B3C.toInt(), c.accentColor())
        assertNull(RemoteConfig.DEFAULT.accentColor())
    }

    @Test
    fun duplicateTabIdKeepsFirst() {
        val json = """{"tabs":[
            {"id":"berita","label":"Pertama","visible":true},
            {"id":"berita","label":"Kedua","visible":false}]}"""
        val c = RemoteConfig.parse(json)!!
        assertEquals(1, c.tabs.count { it.id == "berita" })
        assertEquals("Pertama", c.tabs.first { it.id == "berita" }.label)
    }

    // v1.1.30: donate_url — hanya https tanpa spasi; selain itu "".
    @Test
    fun donateUrlValidHttpsParses() {
        val c = RemoteConfig.parse("""{"donate_url":"https://saweria.co/hozinking"}""")!!
        assertEquals("https://saweria.co/hozinking", c.donateUrl)
    }

    @Test
    fun donateUrlHttpOrSpacedOrEmptyBecomesEmpty() {
        assertEquals("", RemoteConfig.parse("""{"donate_url":"http://saweria.co/x"}""")!!.donateUrl)
        assertEquals("", RemoteConfig.parse("""{"donate_url":"https://saweria.co/a b"}""")!!.donateUrl)
        assertEquals("", RemoteConfig.parse("""{"donate_url":""}""")!!.donateUrl)
        assertEquals("", RemoteConfig.parse("""{}""")!!.donateUrl)
        assertEquals("", RemoteConfig.DEFAULT.donateUrl)
    }
}

package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test parse CoinApi (v1.1.30): JSON server -> model, tanpa Android. */
class CoinApiParseTest {

    @Test
    fun parseMeFull() {
        val json = JSONObject(
            """{"owner_key":"user:u-abc","username":"hozin","balance":120,
               "vip":{"active":true,"expires_at":1800000000000},
               "reading_private":false,
               "unlocks":[{"kind":"cerpen","target_id":"s1","season":1,"no":5},
                          {"kind":"komik","target_id":"k2","season":1,"no":2}],
               "pricing":{"default_price":10,"free_episodes_default":1,"overrides":{}}}"""
        )
        val me = CoinApi.parseMe(json)!!
        assertEquals(120, me.balance)
        assertEquals("hozin", me.username)
        assertEquals("user:u-abc", me.ownerKey)
        assertTrue(me.vipActive)
        assertEquals(1800000000000L, me.vipExpiresAtMs)
        assertEquals(setOf("cerpen:s1:1:5", "komik:k2:1:2"), me.unlocks)
        assertEquals(10, CoinGate.parsePricing(JSONObject(me.pricingJson!!)).defaultPrice)
    }

    @Test
    fun parseVerifiedDefensive() {
        // Server v87.2 kirim verified 0/1; field bisa hilang (deploy paralel).
        val withInt = JSONObject("""{"verified":1,"balance":0}""")
        assertTrue(CoinApi.parseMe(withInt)!!.verified)
        val withBool = JSONObject("""{"verified":true,"balance":0}""")
        assertTrue(CoinApi.parseMe(withBool)!!.verified)
        val missing = JSONObject("""{"balance":0}""")
        assertEquals(false, CoinApi.parseMe(missing)!!.verified)
        val lb = CoinApi.parseLeaderboard(JSONObject(
            """{"leaderboard":[{"rank":1,"username":"ani","seconds":60,"minutes":1,"verified":1},
                               {"rank":2,"username":"budi","seconds":30,"minutes":0}]}"""
        ))
        assertTrue(lb[0].verified)
        assertEquals(false, lb[1].verified)
    }

    @Test
    fun parseMeGuest() {
        val json = JSONObject(
            """{"owner_key":"device:550e8400-e29b-41d4-a716-446655440000",
               "username":null,"balance":0,
               "vip":{"active":false,"expires_at":null},
               "reading_private":false,"unlocks":[],
               "pricing":{"default_price":10,"free_episodes_default":1}}"""
        )
        val me = CoinApi.parseMe(json)!!
        assertEquals(0, me.balance)
        assertNull(me.username)
        assertEquals(false, me.vipActive)
        assertEquals(false, me.verified)
        assertTrue(me.unlocks.isEmpty())
    }

    @Test
    fun parseMeNullReturnsNull() {
        assertNull(CoinApi.parseMe(null))
    }

    @Test
    fun parseSession() {
        val json = JSONObject(
            """{"session":"abc123","username":"hozin","owner_key":"user:u-abc"}"""
        )
        val s = CoinApi.parseSession(json)!!
        assertEquals("abc123", s.session)
        assertEquals("hozin", s.username)
        assertEquals("user:u-abc", s.ownerKey)
        assertNull(CoinApi.parseSession(JSONObject("""{"ok":true}""")))
    }

    @Test
    fun parseLeaderboard() {
        val json = JSONObject(
            """{"leaderboard":[
               {"rank":1,"username":"ani","seconds":7200,"minutes":120},
               {"rank":2,"username":"budi","seconds":3600,"minutes":60}]}"""
        )
        val list = CoinApi.parseLeaderboard(json)
        assertEquals(2, list.size)
        assertEquals(1, list[0].rank)
        assertEquals("ani", list[0].username)
        assertEquals(7200L, list[0].seconds)
        assertEquals(60L, list[1].minutes)
        assertEquals(false, list[0].verified)
        assertEquals(false, list[1].verified)
        assertTrue(CoinApi.parseLeaderboard(null).isEmpty())
        assertTrue(CoinApi.parseLeaderboard(JSONObject("{}")).isEmpty())
    }

    @Test
    fun parseLeaderboardEmptySuccess() {
        // Respons live saat board kosong (200 {"ok":true,"leaderboard":[]}):
        // sukses dengan daftar kosong — bukan gagal. fetchLeaderboard hanya
        // mengembalikan null saat status non-2xx (pola yang sama dengan fetchHistory).
        val json = JSONObject("""{"ok":true,"period":"weekly","leaderboard":[]}""")
        assertTrue(CoinApi.parseLeaderboard(json).isEmpty())
    }

    @Test
    fun parseHistory() {
        val json = JSONObject(
            """{"unlocks":[{"kind":"cerpen","target_id":"s1","season":1,"no":5,"created_at":1790000000}],
               "reading":[{"kind":"cerpen","target_id":"s1","season":1,"no":5,"seconds":900,"last_day":"2026-11-13"}]}"""
        )
        val h = CoinApi.parseHistory(json)!!
        assertEquals(1, h.unlocks.size)
        assertEquals("s1", h.unlocks[0].targetId)
        assertEquals(5, h.unlocks[0].no)
        assertEquals(1790000000L, h.unlocks[0].createdAtRaw)
        assertEquals(1, h.reading.size)
        assertEquals(900L, h.reading[0].seconds)
        assertEquals("2026-11-13", h.reading[0].lastDay)
        assertNull(CoinApi.parseHistory(null))
    }

    @Test
    fun errorMessageFallsBack() {
        assertEquals("Kode salah", CoinApi.errorMessage(JSONObject("""{"error":"Kode salah"}"""), "Gagal"))
        assertEquals("Gagal", CoinApi.errorMessage(JSONObject("""{"error":"banned"}"""), "Gagal"))
        assertEquals("Gagal", CoinApi.errorMessage(null, "Gagal"))
    }
}

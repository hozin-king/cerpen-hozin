package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test CoinGate (v1.1.30): logika gerbang murni, tanpa Android. */
class CoinGateTest {

    private val now = 1_800_000_000_000L // ~2027, ms
    private val pricing = CoinGate.Pricing(
        defaultPrice = 10,
        freeEpisodesDefault = 1,
        overrides = mapOf(
            "cerpen:s1" to CoinGate.PriceOverride(price = 25, freeEpisodes = 2)
        )
    )

    private fun state(
        balance: Int = 5,
        unlocks: Set<String> = emptySet(),
        vipExpiresAtMs: Long = 0L
    ) = CoinGate.CoinState(
        balance = balance,
        unlocks = unlocks,
        vipExpiresAtMs = vipExpiresAtMs,
        pricing = pricing
    )

    // ---- priceFor: cerminan persis server ----

    @Test
    fun priceForDefaultFreeEpisodeOne() {
        val p = CoinGate.priceFor(pricing, "cerpen", "lain", 1)
        assertEquals(10, p.price)
        assertTrue(p.free)
    }

    @Test
    fun priceForDefaultLockedEpisodeTwo() {
        val p = CoinGate.priceFor(pricing, "cerpen", "lain", 2)
        assertEquals(10, p.price)
        assertEquals(false, p.free)
    }

    @Test
    fun priceForOverrideSeries() {
        // Override: harga 25, gratis s/d episode 2.
        val p1 = CoinGate.priceFor(pricing, "cerpen", "s1", 2)
        assertEquals(25, p1.price)
        assertTrue(p1.free)
        val p2 = CoinGate.priceFor(pricing, "cerpen", "s1", 3)
        assertEquals(25, p2.price)
        assertEquals(false, p2.free)
    }

    @Test
    fun priceForFreeDependsOnlyOnEpisodeNumberNotSeason() {
        // Episode 1 di season 5 tetap gratis (aturan server).
        val p = CoinGate.priceFor(pricing, "cerpen", "lain", 1)
        assertTrue(p.free)
    }

    @Test
    fun parsePricingNullGivesDefault() {
        assertEquals(CoinGate.Pricing.DEFAULT, CoinGate.parsePricing(null))
        assertEquals(CoinGate.Pricing.DEFAULT, CoinGate.parsePricing(JSONObject("{}")))
    }

    @Test
    fun parsePricingReadsOverrides() {
        val obj = JSONObject(
            """{"default_price":10,"free_episodes_default":1,
               "overrides":{"cerpen:x":{"price":50,"free_episodes":0}}}"""
        )
        val p = CoinGate.parsePricing(obj)
        assertEquals(50, p.overrides["cerpen:x"]!!.price)
        assertEquals(0, p.overrides["cerpen:x"]!!.freeEpisodes)
    }

    // ---- lockState ----

    @Test
    fun lockStateFreeEpisode() {
        assertEquals(
            CoinGate.LockState.Free,
            CoinGate.lockState(state(), "cerpen", "lain", 1, 1, false, now)
        )
    }

    @Test
    fun lockStateUnlockedFromCache() {
        val s = state(unlocks = setOf(CoinGate.unlockKey("cerpen", "lain", 1, 5)))
        assertEquals(
            CoinGate.LockState.Unlocked,
            CoinGate.lockState(s, "cerpen", "lain", 1, 5, false, now)
        )
    }

    @Test
    fun lockStateLocallyReadCountsAsUnlocked() {
        // Grandfathering: dibaca sebelum v1.1.30 = sudah dimiliki.
        assertEquals(
            CoinGate.LockState.Unlocked,
            CoinGate.lockState(state(), "cerpen", "lain", 2, 7, true, now)
        )
    }

    @Test
    fun lockStateVipActive() {
        val s = state(vipExpiresAtMs = now + 10 * 24 * 60 * 60 * 1000L)
        assertEquals(
            CoinGate.LockState.Vip,
            CoinGate.lockState(s, "cerpen", "lain", 1, 9, false, now)
        )
    }

    @Test
    fun lockStateVipExpiredIsLocked() {
        val s = state(vipExpiresAtMs = now - 1000L)
        assertEquals(
            CoinGate.LockState.Locked(10),
            CoinGate.lockState(s, "cerpen", "lain", 1, 9, false, now)
        )
    }

    @Test
    fun lockStateLockedPriceFromOverride() {
        assertEquals(
            CoinGate.LockState.Locked(25),
            CoinGate.lockState(state(), "cerpen", "s1", 1, 3, false, now)
        )
    }

    @Test
    fun unlockKeyFormat() {
        assertEquals("cerpen:abc:2:7", CoinGate.unlockKey("cerpen", "abc", 2, 7))
        assertEquals("komik:xyz:1:1", CoinGate.unlockKey("komik", "xyz", 1, 1))
    }

    // ---- VIP / waktu ----

    @Test
    fun vipRemainingText() {
        assertEquals("12 hari lagi", CoinGate.vipRemainingText(now + 12 * 24 * 60 * 60 * 1000L, now))
        assertEquals("3 jam lagi", CoinGate.vipRemainingText(now + 3 * 60 * 60 * 1000L, now))
        assertEquals("45 menit lagi", CoinGate.vipRemainingText(now + 45 * 60 * 1000L, now))
        assertNull(CoinGate.vipRemainingText(now - 1, now))
        assertNull(CoinGate.vipRemainingText(0, now))
    }

    @Test
    fun updatedAtText() {
        assertEquals("baru saja", CoinGate.updatedAtText(now - 30_000, now))
        assertEquals("5 menit lalu", CoinGate.updatedAtText(now - 5 * 60_000, now))
        assertEquals("2 jam lalu", CoinGate.updatedAtText(now - 2 * 60 * 60_000, now))
        assertEquals("belum pernah", CoinGate.updatedAtText(0, now))
    }

    @Test
    fun shortId() {
        assertEquals("u-abc123", CoinGate.shortId("u-abc123"))
        assertEquals("550e8400…", CoinGate.shortId("550e8400-e29b-41d4-a716-446655440000"))
    }

    // ---- pesan ban ----

    @Test
    fun bannedMessageWithReasonAndExpiry() {
        // expires_at server = DETIK.
        val json = JSONObject(
            """{"error":"banned","reason":"Spam voucher","expires_at":1790000000}"""
        )
        val msg = CoinGate.bannedMessage(json)!!
        assertTrue(msg.contains("Spam voucher"))
        assertTrue(msg.contains("sampai"))
    }

    @Test
    fun bannedMessagePermanent() {
        val json = JSONObject("""{"error":"banned","reason":"Curang","expires_at":0}""")
        val msg = CoinGate.bannedMessage(json)!!
        assertTrue(msg.contains("Curang"))
        assertTrue(msg.contains("permanen"))
    }

    @Test
    fun bannedMessageNullForOtherErrors() {
        assertNull(CoinGate.bannedMessage(JSONObject("""{"error":"saldo tidak cukup"}""")))
        assertNull(CoinGate.bannedMessage(null))
    }
}

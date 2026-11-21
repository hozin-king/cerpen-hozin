package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test god-mode admin (21 Nov 2026, TANPA panel): grant + gating, tanpa Android. */
class AdminGrantTest {

    @Test
    fun parseStatusAdminFields() {
        val st = AdminApi.parseStatus(
            JSONObject("""{"ok":true,"banned":false,"admin":true,"admin_expires_at":1795000000,"commands":[]}""")
        )!!
        assertTrue(st.admin)
        assertEquals(1795000000L, st.adminExpiresAtSec)
    }

    @Test
    fun parseStatusNonAdminDefaults() {
        val st = AdminApi.parseStatus(JSONObject("""{"ok":true,"banned":false,"commands":[]}"""))!!
        assertFalse(st.admin)
        assertEquals(0L, st.adminExpiresAtSec)
    }

    @Test
    fun parseStatusAdminExpiresMsNormalized() {
        // Defensif seperti expires_at: milidetik -> detik.
        val st = AdminApi.parseStatus(
            JSONObject("""{"ok":true,"banned":false,"admin":true,"admin_expires_at":1795000000000,"commands":[]}""")
        )!!
        assertTrue(st.admin)
        assertEquals(1795000000L, st.adminExpiresAtSec)
    }

    @Test
    fun grantValid() {
        assertTrue(AdminKeyStore.isGrantValid(true, 1795000000L, "fp-abc", "fp-abc", 1794000000L))
    }

    @Test
    fun grantExpired() {
        assertFalse(AdminKeyStore.isGrantValid(true, 1794000000L, "fp-abc", "fp-abc", 1795000000L))
    }

    @Test
    fun grantExpiredExactlyAtNow() {
        assertFalse(AdminKeyStore.isGrantValid(true, 1794000000L, "fp-abc", "fp-abc", 1794000000L))
    }

    @Test
    fun grantWrongFingerprint() {
        assertFalse(AdminKeyStore.isGrantValid(true, 1795000000L, "fp-abc", "fp-lain", 1794000000L))
    }

    @Test
    fun grantInactiveFlag() {
        assertFalse(AdminKeyStore.isGrantValid(false, 1795000000L, "fp-abc", "fp-abc", 1794000000L))
    }

    @Test
    fun grantNoFingerprint() {
        assertFalse(AdminKeyStore.isGrantValid(true, 1795000000L, "", "fp-abc", 1794000000L))
        assertFalse(AdminKeyStore.isGrantValid(true, 1795000000L, null, "fp-abc", 1794000000L))
    }

    @Test
    fun lockStateAdminUnlocksEverything() {
        val state = CoinGate.CoinState(balance = 0, unlocks = emptySet())
        val now = System.currentTimeMillis()
        val lock = CoinGate.lockState(state, "cerpen", "cerpen-99", 3, 7, false, now, isAdmin = true)
        assertTrue(lock is CoinGate.LockState.Unlocked)
    }

    @Test
    fun lockStateOrdinaryUserStillLocked() {
        val state = CoinGate.CoinState(balance = 0, unlocks = emptySet())
        val now = System.currentTimeMillis()
        val lock = CoinGate.lockState(state, "cerpen", "cerpen-99", 3, 7, false, now, isAdmin = false)
        assertTrue(lock is CoinGate.LockState.Locked)
        assertEquals(10, (lock as CoinGate.LockState.Locked).price)
    }

    @Test
    fun lockStateAdminDefaultIsFalse() {
        // Tanpa parameter isAdmin, perilaku lama dipertahankan.
        val state = CoinGate.CoinState(balance = 0, unlocks = emptySet())
        val now = System.currentTimeMillis()
        val lock = CoinGate.lockState(state, "cerpen", "cerpen-99", 3, 7, false, now)
        assertTrue(lock is CoinGate.LockState.Locked)
    }
}

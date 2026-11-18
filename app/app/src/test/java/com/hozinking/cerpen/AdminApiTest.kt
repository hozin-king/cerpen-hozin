package com.hozinking.cerpen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test parse AdminApi (paket admin): JSON server -> model, tanpa Android. */
class AdminApiTest {

    @Test
    fun parseStatusNotBanned() {
        val st = AdminApi.parseStatus(
            JSONObject("""{"ok":true,"banned":false,"commands":[]}""")
        )!!
        assertFalse(st.banned)
        assertEquals("", st.reason)
        assertEquals(0L, st.expiresAtMs)
        assertNull(st.apiKey)
        assertTrue(st.commands.isEmpty())
    }

    @Test
    fun parseStatusBannedWithCommandsAndKey() {
        val st = AdminApi.parseStatus(
            JSONObject(
                """{"ok":true,"banned":true,"reason":"spam","expires_at":1795000000,
                   "api_key":"kunci-baru","commands":[{"type":"kick"},{"type":"crash"}]}"""
            )
        )!!
        assertTrue(st.banned)
        assertEquals("spam", st.reason)
        // expires_at server detik -> ms.
        assertEquals(1795000000000L, st.expiresAtMs)
        assertEquals("kunci-baru", st.apiKey)
        assertEquals(listOf("kick", "crash"), st.commands.map { it.type })
    }

    @Test
    fun parseStatusPermanentBan() {
        // expires_at 0 / hilang = permanen (0 ms).
        val st = AdminApi.parseStatus(
            JSONObject("""{"ok":true,"banned":true,"reason":"curang"}""")
        )!!
        assertTrue(st.banned)
        assertEquals(0L, st.expiresAtMs)
        assertTrue(AdminApi.isBanActive(st.expiresAtMs, System.currentTimeMillis()))
    }

    @Test
    fun parseStatusNull() {
        assertNull(AdminApi.parseStatus(null))
    }

    @Test
    fun parseCommandsUnknownIgnored() {
        val cmds = AdminApi.parseCommands(
            JSONObject("""{"commands":[{"type":"kick"},{"type":"aneh"},{"type":"crash"},{}]}""")
        )
        assertEquals(listOf("kick", "aneh", "crash"), cmds.map { it.type })
        // Aksi: kick -> KICK, crash -> CRASH, tak dikenal -> IGNORE.
        assertEquals(AdminApi.CommandAction.KICK, cmds[0].action)
        assertEquals(AdminApi.CommandAction.IGNORE, cmds[1].action)
        assertEquals(AdminApi.CommandAction.CRASH, cmds[2].action)
    }

    @Test
    fun parseCommandsMissing() {
        assertTrue(AdminApi.parseCommands(JSONObject("""{"ok":true}""")).isEmpty())
        assertTrue(AdminApi.parseCommands(null).isEmpty())
    }

    @Test
    fun isBanActiveExpiry() {
        val now = 1_000_000L
        // Permanen (0) selalu aktif.
        assertTrue(AdminApi.isBanActive(0L, now))
        // Belum kedaluwarsa aktif; sudah lewat tidak aktif.
        assertTrue(AdminApi.isBanActive(2_000_000L, now))
        assertFalse(AdminApi.isBanActive(999_999L, now))
        // Tepat di batas = tidak aktif.
        assertFalse(AdminApi.isBanActive(now, now))
    }
}

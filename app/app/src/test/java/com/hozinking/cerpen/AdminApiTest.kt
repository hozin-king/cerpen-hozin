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
                   "api_key":"kunci-baru","commands":[{"id":"c1","kind":"kick"},{"id":"c2","kind":"crash"}]}"""
            )
        )!!
        assertTrue(st.banned)
        assertEquals("spam", st.reason)
        // expires_at server detik -> ms.
        assertEquals(1795000000000L, st.expiresAtMs)
        assertEquals("kunci-baru", st.apiKey)
        assertEquals(listOf("kick", "crash"), st.commands.map { it.type })
        assertEquals(listOf("c1", "c2"), st.commands.map { it.id })
    }

    @Test
    fun parseRegisterNewKey() {
        // 201 register baru: plaintext kunci + user_id + type + key_id.
        val st = AdminApi.parseStatus(
            JSONObject(
                """{"ok":true,"user_id":"user:u-abc","type":"guest","api_key":"ck-0123456789abcdef0123456789abcdef","key_id":"k1","banned":false,"commands":[]}"""
            )
        )!!
        assertFalse(st.banned)
        assertEquals("user:u-abc", st.userId)
        assertEquals("guest", st.accountType)
        assertEquals("k1", st.keyId)
        assertEquals("ck-0123456789abcdef0123456789abcdef", st.apiKey)
        assertFalse(st.keyExists)
    }

    @Test
    fun parseRegisterKeyExists() {
        // 200 key_exists: plaintext TIDAK dikirim — pemanggil harus rotate.
        val st = AdminApi.parseStatus(
            JSONObject("""{"ok":true,"user_id":"device:uuid-1","type":"guest","key_exists":true,"key_id":"k1","banned":false,"commands":[]}""")
        )!!
        assertTrue(st.keyExists)
        assertNull(st.apiKey)
        assertEquals("k1", st.keyId)
    }

    @Test
    fun normalizeExpiresSecMsVsSeconds() {
        // Modul baru: detik dibiarkan.
        assertEquals(1795000000L, AdminApi.normalizeExpiresSec(1795000000L))
        // Rute lama (bug D4): ms dibagi 1000.
        assertEquals(1795000000L, AdminApi.normalizeExpiresSec(1795000000000L))
        assertEquals(0L, AdminApi.normalizeExpiresSec(0L))
    }

    @Test
    fun parseStatusExpiresMsDefensive() {
        // expires_at dalam ms (rute lama) tetap diformat sebagai detik yang benar.
        val st = AdminApi.parseStatus(
            JSONObject("""{"ok":true,"banned":true,"reason":"spam","expires_at":1795000000000}""")
        )!!
        assertEquals(1795000000000L, st.expiresAtMs)
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
            JSONObject("""{"commands":[{"id":"a","kind":"kick"},{"id":"b","kind":"aneh"},{"id":"c","kind":"crash"},{}]}""")
        )
        assertEquals(listOf("kick", "aneh", "crash"), cmds.map { it.type })
        // Aksi: kick -> KICK, crash -> CRASH, tak dikenal -> IGNORE.
        assertEquals(AdminApi.CommandAction.KICK, cmds[0].action)
        assertEquals(AdminApi.CommandAction.IGNORE, cmds[1].action)
        assertEquals(AdminApi.CommandAction.CRASH, cmds[2].action)
    }

    @Test
    fun parseCommandsLegacyTypeField() {
        // Field "type" lama tetap diterima sebagai fallback kind.
        val cmds = AdminApi.parseCommands(
            JSONObject("""{"commands":[{"id":"x","type":"kick"}]}""")
        )
        assertEquals(listOf("kick"), cmds.map { it.type })
        assertEquals("x", cmds[0].id)
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

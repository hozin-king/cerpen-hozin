package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test deteksi murni DeviceSignals (paket admin), tanpa Android. */
class DeviceSignalsTest {

    @Test
    fun rootMethodsSuAndTestKeys() {
        val methods = DeviceSignals.rootMethodsFor(
            existingPaths = setOf("/system/xbin/su", "/system/app/Superuser.apk"),
            tags = "release-keys",
            installedPackages = emptySet()
        )
        // foundSu mengikuti urutan SU_PATHS — Superuser.apk lebih dulu.
        assertEquals(listOf("su:/system/app/Superuser.apk"), methods)
    }

    @Test
    fun rootMethodsTestKeysOnly() {
        val methods = DeviceSignals.rootMethodsFor(
            existingPaths = emptySet(),
            tags = "test-keys",
            installedPackages = emptySet()
        )
        assertEquals(listOf("test-keys"), methods)
    }

    @Test
    fun rootMethodsPackage() {
        val methods = DeviceSignals.rootMethodsFor(
            existingPaths = emptySet(),
            tags = "release-keys",
            installedPackages = setOf("me.weishu.kernelsu")
        )
        assertEquals(listOf("pkg:me.weishu.kernelsu"), methods)
    }

    @Test
    fun rootMethodsCleanDevice() {
        val methods = DeviceSignals.rootMethodsFor(
            existingPaths = emptySet(),
            tags = "release-keys",
            installedPackages = emptySet()
        )
        assertTrue(methods.isEmpty())
    }

    @Test
    fun emulatorPixelNotEmulator() {
        // POCO X6 Pro user — fingerprint asli, bukan emulator.
        val hints = DeviceSignals.emulatorHintsFor(
            fingerprint = "POCO/duchamp/duchamp:14/UKQ1.240116.001/V816.0.8.0.UNLMIXM:user/release-keys",
            model = "2311DRK48G",
            manufacturer = "Xiaomi",
            hardware = "mt6897",
            product = "duchamp"
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun emulatorGeneric() {
        val hints = DeviceSignals.emulatorHintsFor(
            fingerprint = "generic/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11223344:userdebug/test-keys",
            model = "sdk_gphone64_arm64",
            manufacturer = "Google",
            hardware = "ranchu",
            product = "sdk_gphone64_arm64"
        )
        assertFalse(hints.isEmpty())
        assertTrue(hints.any { it.startsWith("fingerprint:") })
        assertTrue(hints.any { it.startsWith("hardware:") })
    }

    @Test
    fun emulatorGoldfishOnly() {
        val hints = DeviceSignals.emulatorHintsFor(
            fingerprint = "unknown",
            model = "Pixel 8",
            manufacturer = "Google",
            hardware = "goldfish",
            product = "raven"
        )
        assertEquals(listOf("hardware:goldfish"), hints)
    }

    @Test
    fun parseBootLocked() {
        assertEquals(true, DeviceSignals.parseBootLocked("locked"))
        assertEquals(false, DeviceSignals.parseBootLocked("unlocked"))
        assertNull(DeviceSignals.parseBootLocked("orange"))
        assertNull(DeviceSignals.parseBootLocked(null))
        assertNull(DeviceSignals.parseBootLocked(""))
    }

    @Test
    fun toJsonShape() {
        val s = DeviceSignals.Signals(
            rooted = true,
            rootMethods = listOf("su:/system/xbin/su"),
            emulator = false,
            emulatorHints = emptyList(),
            bootloaderLocked = false,
            verifiedBoot = "orange",
            customRom = false
        )
        val j = DeviceSignals.toJson(s)
        assertTrue(j.getBoolean("is_rooted"))
        assertFalse(j.getBoolean("is_emulator"))
        assertEquals(false, j.getBoolean("bootloader_locked"))
        assertEquals("orange", j.getString("verified_boot"))
        assertEquals("su:/system/xbin/su", j.getJSONArray("root_methods").getString(0))
        assertFalse(j.getBoolean("custom_rom"))
    }

    @Test
    fun toJsonUnknownBootloaderIsNull() {
        val s = DeviceSignals.Signals(
            rooted = false,
            rootMethods = emptyList(),
            emulator = false,
            emulatorHints = emptyList(),
            bootloaderLocked = null,
            verifiedBoot = null,
            customRom = false
        )
        val j = DeviceSignals.toJson(s)
        assertTrue(j.isNull("bootloader_locked"))
        assertTrue(j.isNull("verified_boot"))
    }

    @Test
    fun buildFingerprintStableAndHex() {
        val a = DeviceSignals.buildFingerprint("android-1", "xiaomi", "2311DRK48G", "Xiaomi", "duchamp", "mt6897", "board1")
        val b = DeviceSignals.buildFingerprint("android-1", "xiaomi", "2311DRK48G", "Xiaomi", "duchamp", "mt6897", "board1")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        // Perangkat berbeda -> sidik berbeda.
        val c = DeviceSignals.buildFingerprint("android-2", "xiaomi", "2311DRK48G", "Xiaomi", "duchamp", "mt6897", "board1")
        assertFalse(a == c)
    }

    @Test
    fun isCustomRomBuild() {
        assertTrue(DeviceSignals.isCustomRomBuild("lineage_duchamp-userdebug 14", "unknown"))
        assertTrue(DeviceSignals.isCustomRomBuild("TP1A.220624.014", "evolution_duchamp-user 14"))
        assertFalse(DeviceSignals.isCustomRomBuild("V816.0.8.0.UNLMIXM", "POCO/duchamp/duchamp:14/UKQ1.240116.001/V816.0.8.0.UNLMIXM:user/release-keys"))
        assertFalse(DeviceSignals.isCustomRomBuild("", ""))
    }
}

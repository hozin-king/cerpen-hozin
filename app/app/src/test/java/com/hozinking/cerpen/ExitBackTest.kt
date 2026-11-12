package com.hozinking.cerpen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji logika murni dialog keluar v1.1.24 (tanpa Android). */
class ExitBackTest {

    @Test
    fun `back di beranda tanpa dialog tampilkan konfirmasi keluar`() {
        assertTrue(ExitBack.shouldShowExitDialog(updateDialogShowing = false, exitDialogShowing = false))
    }

    @Test
    fun `dialog update tampil jangan tumpuk dialog keluar`() {
        assertFalse(ExitBack.shouldShowExitDialog(updateDialogShowing = true, exitDialogShowing = false))
    }

    @Test
    fun `dialog keluar sudah tampil jangan tampilkan lagi`() {
        assertFalse(ExitBack.shouldShowExitDialog(updateDialogShowing = false, exitDialogShowing = true))
    }

    @Test
    fun `kedua dialog tampil jangan tampilkan lagi`() {
        assertFalse(ExitBack.shouldShowExitDialog(updateDialogShowing = true, exitDialogShowing = true))
    }
}

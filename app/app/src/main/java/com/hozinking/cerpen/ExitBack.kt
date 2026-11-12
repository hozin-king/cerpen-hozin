package com.hozinking.cerpen

/**
 * Logika murni kapan tombol back menampilkan dialog konfirmasi keluar
 * (v1.1.24). MainActivity adalah root tanpa tumpukan navigasi internal —
 * 4 tab dalam satu layar; sub-layar (detail, baca, pengaturan) adalah
 * activity terpisah yang kembali sendiri. Jadi back di MainActivity
 * selalu berarti keluar, kecuali ada dialog lain yang sedang tampil
 * (dialog itu sendiri yang menelan back, jadi dialog tidak boleh
 * menumpuk).
 */
object ExitBack {
    fun shouldShowExitDialog(updateDialogShowing: Boolean, exitDialogShowing: Boolean): Boolean =
        !updateDialogShowing && !exitDialogShowing
}

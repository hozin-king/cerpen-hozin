package com.hozinking.cerpen

/**
 * Kunci zoom viewer komik (v1.1.39, perintah user 16 Nov 2026):
 * panel komik HANYA tampil pas layar (fit) — pinch-to-zoom dan
 * double-tap zoom dimatikan sebagai proteksi konten. Geser antar
 * panel + scroll vertikal tetap jalan seperti biasa.
 *
 * Catatan jujur: viewer komik memakai ImageView biasa (fitCenter,
 * item_komik_panel.xml) — TIDAK ADA implementasi zoom di aplikasi
 * (tanpa PhotoView / ScaleGestureDetector / Matrix). Yang ditambahkan
 * di sini adalah penjaga eksplisit: sentuhan pinch ditelan di
 * KomikReaderActivity.TapToToggleChrome sebelum sampai ke daftar,
 * dan double-tap ditelan tanpa aksi. Logika murni di bawah ini bisa
 * dites tanpa Android.
 */
object ZoomLock {

    /** Skala tampilan panel: selalu fit (1x) — zoom tidak pernah diizinkan. */
    const val LOCKED_SCALE = 1f

    /** Skala apa pun yang diminta runtuh ke fit (parameter sengaja
     *  diabaikan — itulah arti "terkunci"). */
    @Suppress("UNUSED_PARAMETER")
    fun applyScale(requestedScale: Float): Float = LOCKED_SCALE

    /**
     * True bila sentuhan harus ditelan (pinch: 2 jari atau lebih).
     * Sentuhan 1 jari (tap/scroll) diteruskan normal.
     */
    fun shouldConsumeTouch(pointerCount: Int): Boolean = pointerCount >= 2
}

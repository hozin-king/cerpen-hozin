package com.hozinking.cerpen

/**
 * Pelacak waktu baca aplikasi (v1.1.30) — state machine murni, cerminan
 * algoritma pelacak web v81 (reading.js) supaya batas server konsisten:
 * - hitung detik hanya saat halaman terlihat DAN ada aktivitas < 15 detik
 *   DAN pengguna sudah scroll ke bawah sekali
 * - flush tiap 30 detik; maks 120 detik per flush; maks 600 detik pending
 * - batas server (4 jam/hari, 6 flush/menit) ditegakkan server, bukan di sini
 *
 * Dipakai ReaderActivity + KomikReaderActivity HANYA bila sudah login
 * (guest tidak pernah ditulis ke tabel bersama — lihat coins.js).
 * Waktu di-inject lewat [nowMs] supaya bisa di-unit-test.
 */
class ReadingTracker(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        /** Flush tiap 30 detik (sama seperti heartbeat web). */
        const val FLUSH_INTERVAL_SECONDS = 30

        /** Maks detik yang ditahan sebelum wajib flush. */
        const val MAX_PENDING_SECONDS = 600

        /** Maks detik per satu flush ke server. */
        const val MAX_FLUSH_SECONDS = 120

        /** Jendela aktivitas: detik dihitung bila aktivitas < 15 dtk lalu. */
        const val ACTIVITY_WINDOW_MS = 15_000L
    }

    var pendingSeconds: Int = 0
        private set
    var scrolledOnce: Boolean = false
        private set
    var maxScroll: Int = 0
        private set

    private var visible: Boolean = false
    private var lastActiveMs: Long = 0L
    private var lastFlushMs: Long = 0L

    /** Halaman terlihat / tidak (onResume / onPause). */
    fun noteVisible(v: Boolean) {
        visible = v
        if (v) lastActiveMs = nowMs()
    }

    /** Aktivitas pengguna: scroll / sentuh / tombol. */
    fun noteActive() {
        lastActiveMs = nowMs()
    }

    /** Pengguna sudah scroll ke bawah sekali (syarat mulai menghitung). */
    fun noteScrolled(scrollY: Int = 1) {
        scrolledOnce = true
        if (scrollY > maxScroll) maxScroll = scrollY
    }

    /**
     * Dipanggil 1x per detik saat halaman aktif. Mengembalikan true bila
     * detik ini dihitung (pending bertambah 1, maks 600).
     */
    fun tick(): Boolean {
        if (!visible || !scrolledOnce) return false
        val now = nowMs()
        if (now - lastActiveMs > ACTIVITY_WINDOW_MS) return false
        if (pendingSeconds < MAX_PENDING_SECONDS) pendingSeconds++
        return true
    }

    /** true bila sudah waktunya flush ke server (pending >= 30 dtk). */
    fun flushDue(): Boolean {
        val now = nowMs()
        return pendingSeconds >= FLUSH_INTERVAL_SECONDS &&
            now - lastFlushMs >= FLUSH_INTERVAL_SECONDS * 1000L
    }

    /**
     * Ambil detik untuk satu flush (maks 120). Panggil setelah flush
     * sukses -> [confirmFlushed].
     */
    fun takeFlush(): Int {
        val n = minOf(pendingSeconds, MAX_FLUSH_SECONDS)
        lastFlushMs = nowMs()
        return n
    }

    /** Kurangi pending sebanyak yang berhasil tersimpan di server. */
    fun confirmFlushed(stored: Int) {
        pendingSeconds = (pendingSeconds - stored).coerceAtLeast(0)
    }

    /** Dipanggil saat reader ditutup — sisa pending dibuang (bukan error). */
    fun reset() {
        pendingSeconds = 0
        scrolledOnce = false
        maxScroll = 0
        visible = false
        lastActiveMs = 0L
        lastFlushMs = 0L
    }
}

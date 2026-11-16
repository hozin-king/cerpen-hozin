package com.hozinking.cerpen

import android.content.SharedPreferences
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Posisi scroll satu daftar: item pertama yang terlihat + offset piksel
 * tepi atasnya terhadap padding daftar (v1.1.38 — perintah user 16 Nov
 * 2026: posisi scroll tersimpan persis per daftar, kembali saat pindah
 * tab / keluar lalu kembali, termasuk tab Berita).
 *
 * Disimpan persisten (SharedPreferences, kunci per daftar) — bukan hanya
 * di memori — supaya tetap kembali setelah aplikasi ditutup dan dibuka
 * lagi. Format simpan "pos:offset" (dua Int), BUKAN Parcelable
 * LayoutManager: lebih tahan terhadap pergantian adapter/data (posisi
 * di-clamp ke jumlah item saat restore) dan logikanya bisa di-unit-test
 * di JVM (Parcel tidak bisa).
 *
 * Aturan pakai (MainActivity): simpan saat daftar berhenti di-scroll
 * (idle) + saat onPause; restore SETELAH adapter baru dipasang (data
 * selesai dimuat) dan HANYA bila user belum menggulir sendiri sejak
 * daftar tampil — penanda itu dipegang pemanggil, bukan di sini.
 */
data class ScrollPosition(val firstVisible: Int, val offsetPx: Int)

object ScrollStateStore {

    /** "12:-34" — offset boleh negatif (item pertama sebagian ter-scroll). */
    fun encode(pos: ScrollPosition): String = "${pos.firstVisible}:${pos.offsetPx}"

    /** Null bila format rusak / posisi negatif — jangan restore sampah. */
    fun decode(raw: String?): ScrollPosition? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val pos = parts[0].toIntOrNull() ?: return null
        val off = parts[1].toIntOrNull() ?: return null
        if (pos < 0) return null
        return ScrollPosition(pos, off)
    }

    /**
     * Jepit posisi ke daftar yang ada sekarang (konten bisa menyusut
     * setelah sync/filter): posisi di luar jangkauan -> item terakhir;
     * daftar kosong -> null (tidak ada yang di-restore).
     */
    fun clamp(pos: ScrollPosition, itemCount: Int): ScrollPosition? {
        if (itemCount <= 0) return null
        val p = pos.firstVisible.coerceIn(0, itemCount - 1)
        return ScrollPosition(p, pos.offsetPx)
    }

    /** Tangkap posisi saat ini dari LayoutManager (null bila daftar kosong). */
    fun capture(lm: LinearLayoutManager): ScrollPosition? {
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return null
        val child = lm.findViewByPosition(pos) ?: return ScrollPosition(pos, 0)
        return ScrollPosition(pos, child.top - lm.paddingTop)
    }

    fun save(prefs: SharedPreferences, key: String, pos: ScrollPosition) {
        prefs.edit().putString(key, encode(pos)).apply()
    }

    fun load(prefs: SharedPreferences, key: String): ScrollPosition? =
        decode(prefs.getString(key, null))

    /** Terapkan ke LayoutManager (dipanggil setelah adapter terpasang). */
    fun apply(lm: LinearLayoutManager, pos: ScrollPosition) {
        lm.scrollToPositionWithOffset(pos.firstVisible, pos.offsetPx)
    }

    /** ScrollView utama (satu angka per tab) — pendamping posisi daftar. */
    fun saveScrollY(prefs: SharedPreferences, key: String, y: Int) {
        prefs.edit().putInt(key, y.coerceAtLeast(0)).apply()
    }

    fun loadScrollY(prefs: SharedPreferences, key: String): Int =
        prefs.getInt(key, 0).coerceAtLeast(0)
}

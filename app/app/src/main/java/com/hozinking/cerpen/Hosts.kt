package com.hozinking.cerpen

import java.io.IOException

/**
 * Host CMS (v1.1.22): domain utama + fallback workers.dev.
 *
 * Bila domain utama mati (tidak dibayar / lupa perpanjang), aplikasi
 * tetap bisa sync konten, bikin tautan berbagi, dan ambil remote
 * config lewat `https://hozin-cms.muchammadhozin.workers.dev` — Worker
 * yang sama, path yang sama, ganti host saja. URL share dibangun
 * server dari origin request, jadi tautan dari fallback tetap
 * ber-host fallback (dan tetap membuka aplikasi via intent-filter).
 *
 * Aturan fallback: coba host utama dulu; bila gagal KONEKSI
 * (IOException: timeout / DNS / putus), coba SEKALI ke fallback.
 * Bila server MENJAWAB (HTTP error seperti 404/429) -> TIDAK ada
 * fallback, perilaku lama berlaku (null / fail-open).
 */
object Hosts {
    const val PRIMARY = "https://hozinking.my.id"
    const val FALLBACK = "https://hozin-cms.muchammadhozin.workers.dev"

    /** Ganti host di awal URL (path + query sama persis). */
    fun fallbackUrl(url: String): String =
        if (url.startsWith(PRIMARY)) FALLBACK + url.substring(PRIMARY.length) else url

    /**
     * Jalankan [block] ke URL utama; bila gagal koneksi (IOException),
     * coba SEKALI ke host fallback. Block HARUS melempar IOException
     * bila koneksi gagal, dan mengembalikan null (bukan melempar) bila
     * server menjawab HTTP error.
     */
    fun <T> withFallback(url: String, block: (String) -> T): T {
        try {
            return block(url)
        } catch (e: IOException) {
            return block(fallbackUrl(url))
        }
    }
}

package com.hozinking.cerpen

import android.content.Context

/**
 * Badge "diperbarui" di kartu cerbung/komik (v1.1.7, perintah user 10 Nov 2026:
 * "kalau ada episode atau season baru itu ditambahin kek season diperbarui
 * atau episode diperbarui").
 *
 * Cara kerja: [DataManager.runSync] membandingkan daftar LAMA (sebelum unduh)
 * dengan daftar BARU (sesudah unduh). Item yang kedapatan bertambah dicatat
 * di sini; kartu menampilkan badge sampai pengguna MEMBUKA detail item itu
 * (dibuka = sudah melihat pembaruan, badge dihapus).
 *
 * Item lama yang belum pernah bertambah TIDAK pernah dapat badge — jadi tidak
 * ada badge massal saat update aplikasi, dan sync yang tidak membawa konten
 * baru juga tidak memunculkan apa-apa.
 */
object UpdatedStore {
    private const val PREFS = "cerpen_updated"
    private const val PREFIX_CERPEN = "upd_cerpen_"
    private const val PREFIX_KOMIK = "upd_komik_"

    enum class Kind { NEW, SEASON, EPISODE }

    fun markCerpen(context: Context, id: String, kind: Kind) {
        put(context, PREFIX_CERPEN + id, kind)
    }

    fun markKomik(context: Context, id: String, kind: Kind) {
        put(context, PREFIX_KOMIK + id, kind)
    }

    /** Teks badge untuk kartu cerpen, atau null bila tidak ada pembaruan. */
    fun badgeTextCerpen(context: Context, c: Cerpen): String? =
        when (kindOf(context, PREFIX_CERPEN + c.id)) {
            Kind.NEW -> "Baru"
            Kind.SEASON -> "Season diperbarui"
            Kind.EPISODE -> "Episode diperbarui"
            null -> null
        }

    /** Teks badge untuk kartu komik, atau null bila tidak ada pembaruan. */
    fun badgeTextKomik(context: Context, k: Komik): String? =
        when (kindOf(context, PREFIX_KOMIK + k.id)) {
            Kind.NEW -> "Baru"
            Kind.SEASON -> "Episode diperbarui" // komik tidak punya season
            Kind.EPISODE -> "Episode diperbarui"
            null -> null
        }

    /** Dipanggil saat pengguna membuka detail: pembaruan sudah dilihat. */
    fun clearCerpen(context: Context, id: String) {
        remove(context, PREFIX_CERPEN + id)
    }

    fun clearKomik(context: Context, id: String) {
        remove(context, PREFIX_KOMIK + id)
    }

    private fun put(context: Context, key: String, kind: Kind) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, kind.name)
            .apply()
    }

    private fun remove(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(key)
            .apply()
    }

    private fun kindOf(context: Context, key: String): Kind? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return try {
            Kind.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

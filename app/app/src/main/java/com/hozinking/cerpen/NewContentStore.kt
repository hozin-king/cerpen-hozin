package com.hozinking.cerpen

import android.content.Context

/**
 * Label "BARU" / "SEASON BARU" otomatis (v1.1.26).
 *
 * JSON konten tidak membawa created_at per episode/season (hanya `date`
 * level series), jadi sumber kebenarannya adalah first-seen: saat
 * [DataManager] mendeteksi season/episode yang benar-benar baru sesudah
 * sync, waktu pertama terlihat dicatat di sini (putIfAbsent). Badge tampil
 * bila first-seen masih dalam 7 hari terakhir.
 *
 * Konten lama TIDAK PERNAH kena label: first-seen hanya ditulis untuk item
 * yang muncul sesudah fitur ini aktif (sync pertama = baseline, tidak
 * menandai apa-apa). Badge tidak dihapus saat dibuka — ia hilang sendiri
 * setelah 7 hari.
 */
object NewContentStore {
    private const val PREFS = "cerpen_newbadges"

    /** Jendela badge: first-seen dalam 7 hari terakhir. */
    const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun episodeKey(cerpenId: String, season: Int, no: Int) = "ep:$cerpenId:$season:$no"
    fun seasonKey(cerpenId: String, season: Int) = "se:$cerpenId:$season"
    fun komikEpisodeKey(komikId: String, no: Int) = "kep:$komikId:$no"

    /**
     * true bila first-seen ada dan umurnya masih dalam jendela.
     * Murni waktu — tanpa Android — supaya bisa di-unit-test di JVM.
     */
    fun isNew(firstSeenMillis: Long, nowMillis: Long): Boolean =
        firstSeenMillis > 0L && nowMillis >= firstSeenMillis &&
            nowMillis - firstSeenMillis <= WINDOW_MILLIS

    /** Catat first-seen bila belum ada; tidak menimpa stempel lama. */
    fun markSeen(context: Context, key: String, nowMillis: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(key)) p.edit().putLong(key, nowMillis).apply()
    }

    /** 0 bila belum pernah terlihat (konten lama / belum ada stempel). */
    fun firstSeen(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)

    fun isEpisodeNew(
        context: Context, cerpenId: String, season: Int, no: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isNew(firstSeen(context, episodeKey(cerpenId, season, no)), nowMillis)

    fun isSeasonNew(
        context: Context, cerpenId: String, season: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isNew(firstSeen(context, seasonKey(cerpenId, season)), nowMillis)

    fun isKomikEpisodeNew(
        context: Context, komikId: String, no: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isNew(firstSeen(context, komikEpisodeKey(komikId, no)), nowMillis)

    /** true bila ada satu pun season/episode series ini yang masih BARU. */
    fun hasNewContent(
        context: Context, c: Cerpen,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        for (s in c.seasons) {
            if (isSeasonNew(context, c.id, s.season, nowMillis)) return true
            for (e in s.episodes) {
                if (isEpisodeNew(context, c.id, s.season, e.no, nowMillis)) return true
            }
        }
        return false
    }

    /** true bila ada satu pun episode komik ini yang masih BARU. */
    fun hasNewContent(
        context: Context, k: Komik,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        for (e in k.episodes) {
            if (isKomikEpisodeNew(context, k.id, e.no, nowMillis)) return true
        }
        return false
    }

    /**
     * Stempel first-seen untuk season/episode yang baru muncul dibanding
     * versi lama. `old == null` = series baru: semua season + episode
     * distempel. Dipanggil dari diff sync — hanya untuk konten yang
     * benar-benar bertambah.
     */
    fun markNewCerpen(context: Context, c: Cerpen, old: Cerpen?) {
        val now = System.currentTimeMillis()
        if (old == null) {
            for (s in c.seasons) {
                markSeen(context, seasonKey(c.id, s.season), now)
                for (e in s.episodes) {
                    markSeen(context, episodeKey(c.id, s.season, e.no), now)
                }
            }
            return
        }
        val oldSeasons = old.seasons.associateBy { it.season }
        for (s in c.seasons) {
            val os = oldSeasons[s.season]
            if (os == null) {
                markSeen(context, seasonKey(c.id, s.season), now)
                for (e in s.episodes) {
                    markSeen(context, episodeKey(c.id, s.season, e.no), now)
                }
            } else {
                val oldNos = os.episodes.map { it.no }.toSet()
                for (e in s.episodes) {
                    if (e.no !in oldNos) {
                        markSeen(context, episodeKey(c.id, s.season, e.no), now)
                    }
                }
            }
        }
    }

    /** Seperti [markNewCerpen] untuk episode komik (komik tidak punya season). */
    fun markNewKomik(context: Context, k: Komik, old: Komik?) {
        val now = System.currentTimeMillis()
        if (old == null) {
            for (e in k.episodes) {
                markSeen(context, komikEpisodeKey(k.id, e.no), now)
            }
            return
        }
        val oldNos = old.episodes.map { it.no }.toSet()
        for (e in k.episodes) {
            if (e.no !in oldNos) {
                markSeen(context, komikEpisodeKey(k.id, e.no), now)
            }
        }
    }
}

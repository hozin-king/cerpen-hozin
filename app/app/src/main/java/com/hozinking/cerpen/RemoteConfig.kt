package com.hozinking.cerpen

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote config (v1.1.14): urutan/visibilitas/label tab dock, banner, dan
 * warna aksen — diatur dari /kendali/settings CMS, diambil dari
 * GET https://hozinking.my.id/api/cerpen-config.
 *
 * Fail-closed di semua lapis: JSON rusak total / HTTP bukan 200 /
 * offline / timeout -> fetch mengembalikan null dan pemanggil memakai
 * cache terakhir (atau DEFAULT); field hilang / id tab tak dikenal /
 * accent bukan #RRGGBB -> nilai default untuk bagian itu saja, tidak
 * pernah throw ke pemanggil. Tab yang hilang dari config tetap tampil
 * dengan label bawaan (parse melengkapi dari DEFAULT).
 */
data class RemoteConfig(
    val tabs: List<Tab>,
    val banner: String,
    val accent: String
) {
    data class Tab(val id: String, val label: String, val visible: Boolean)

    /**
     * Warna aksen config (#RRGGBB) sebagai Int ARGB, null bila
     * kosong/tidak valid. Dihitung manual (tanpa Color.parseColor)
     * agar tetap bisa dites di unit test JVM.
     */
    fun accentColor(): Int? {
        if (!ACCENT_REGEX.matches(accent)) return null
        val rgb = accent.substring(1).toIntOrNull(16) ?: return null
        return 0xFF000000.toInt() or rgb
    }

    companion object {
        const val ENDPOINT = "https://hozinking.my.id/api/cerpen-config"
        const val PREFS_NAME = "remote_config"
        const val PREF_KEY_JSON = "remote_config_json"
        const val MAX_BANNER_CHARS = 140
        private const val TAG = "RemoteConfig"
        private const val TIMEOUT_MS = 8000
        private val ACCENT_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        /** Id tab yang dikenal, dalam urutan bawaan (0=Cerita, 1=Berita, 2=Komik, 3=Koleksi). */
        val KNOWN_IDS = listOf("cerbung", "berita", "komik", "koleksi")

        val DEFAULT = RemoteConfig(
            tabs = KNOWN_IDS.map { Tab(it, defaultLabel(it), true) },
            banner = "",
            accent = ""
        )

        fun defaultLabel(id: String): String = when (id) {
            "cerbung" -> "Cerita"
            "berita" -> "Berita"
            "komik" -> "Komik"
            "koleksi" -> "Koleksi"
            else -> id
        }

        /** Indeks tab (0..3) untuk satu id, -1 bila tak dikenal. */
        fun tabIndex(id: String): Int = KNOWN_IDS.indexOf(id)

        /**
         * Parse JSON config. Null HANYA bila JSON rusak total (bukan objek
         * JSON) — pemanggil memakai cache/DEFAULT. Field bermasalah
         * diganti default per-bagian.
         */
        fun parse(json: String?): RemoteConfig? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val tabs = parseTabs(obj)
                val banner = obj.optString("banner", "").trim()
                val accent = obj.optString("accent", "").trim()
                    .takeIf { ACCENT_REGEX.matches(it) } ?: ""
                RemoteConfig(tabs, banner, accent)
            } catch (e: Exception) {
                // Sengaja tanpa Log: parse murni agar bisa dites di JVM
                // (android.util.Log stub melempar di unit test); fetch
                // yang mencatat kegagalan ini.
                null
            }
        }

        /** Tab config dalam urutan config; yang hilang dilengkapi dari DEFAULT. */
        private fun parseTabs(obj: JSONObject): List<Tab> {
            val parsed = ArrayList<Tab>()
            val arr = obj.optJSONArray("tabs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val id = t.optString("id", "").trim()
                    if (id !in KNOWN_IDS) continue // id tak dikenal: abaikan
                    if (parsed.any { it.id == id }) continue // duplikat: pakai pertama
                    val label = t.optString("label", "").trim()
                        .takeIf { it.isNotEmpty() } ?: defaultLabel(id)
                    parsed.add(Tab(id, label, t.optBoolean("visible", true)))
                }
            }
            // Tab yang hilang dari config tetap tampil dengan label bawaan.
            for (id in KNOWN_IDS) {
                if (parsed.none { it.id == id }) parsed.add(Tab(id, defaultLabel(id), true))
            }
            return if (parsed.isEmpty()) DEFAULT.tabs else parsed
        }

        /** Config terakhir yang tersimpan, DEFAULT bila belum pernah fetch sukses. */
        fun loadCached(context: Context): RemoteConfig {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_JSON, null)
            return parse(raw) ?: DEFAULT
        }

        /**
         * GET endpoint config (panggil dari thread background). Sukses +
         * parse OK -> simpan mentah ke SharedPreferences, kembalikan config.
         * Gagal apa pun -> null, cache tidak disentuh, tanpa toast/error UI.
         * v1.1.22: host utama mati (gagal koneksi) -> coba SEKALI ke
         * workers.dev; HTTP error tetap null tanpa fallback.
         */
        fun fetch(context: Context): RemoteConfig? {
            return try {
                Hosts.withFallback(ENDPOINT) { url -> fetchOnce(context, url) }
            } catch (e: Exception) {
                Log.w(TAG, "fetch gagal: ${e.message}")
                null
            }
        }

        /**
         * Satu percobaan GET config ke [url]. Melempar IOException bila
         * koneksi gagal (pemicu fallback); null bila server menjawab
         * HTTP bukan 200 atau body tak bisa diparse.
         */
        private fun fetchOnce(context: Context, url: String): RemoteConfig? {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "CerpenHozin/1.1")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "fetch HTTP ${conn.responseCode}")
                    return null
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val config = parse(body)
                if (config == null) {
                    Log.w(TAG, "fetch: parse gagal total")
                    return null
                }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY_JSON, body).apply()
                return config
            } finally {
                conn?.disconnect()
            }
        }
    }
}

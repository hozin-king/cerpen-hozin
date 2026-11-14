package com.hozinking.cerpen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Sinkron bookmark konten (series) ke akun — v1.1.34 (CMS v87.5).
 *
 * Kontrak server: GET / POST / DELETE /api/cerpen-bookmarks (wajib sesi
 * akun; guest -> 401 login_required). POST idempotent (sudah ada ->
 * already). DELETE lewat query (?kind=&content_id=).
 *
 * Perilaku:
 * - Toggle di halaman detail: lokal dulu (langsung, seperti v1.1.33),
 *   lalu antrean ke server bila login & online; bila offline/belum
 *   login, operasi masuk antrean dan dikirim saat online/login.
 * - Saat login / buka aplikasi: tarik daftar server, gabung dengan lokal
 *   (union — bookmark lokal TIDAK PERNAH dihapus oleh sinkron; yang
 *   sudah tidak ada di katalog dilewati seperti biasa), dan unggah
 *   bookmark lokal yang belum ada di server (ini yang memindahkan
 *   bookmark guest ke akun begitu login).
 * - Guest (belum login): bookmark tetap lokal saja, tampil normal.
 * - Semua jaringan HANYA dari thread latar — syncNow/onToggle membuat
 *   thread sendiri, pemanggil cukup memanggilnya dari mana saja.
 * - Batasan jujur (semantik union): hapus di perangkat A tidak
 *   menghapus salinan lokal di perangkat B — B akan mengunggah lagi
 *   bookmark itu saat sinkron berikutnya. Hapus di perangkat yang sama
 *   tetap hilang dari server (DELETE langsung/antrean).
 */
object BookmarkSync {

    private const val PREFS = "cerpen_bookmark_sync"
    private const val KEY_PENDING = "pending_ops"
    private const val KEY_LAST_FULL_SYNC = "last_full_sync_ms"
    const val FULL_SYNC_MIN_INTERVAL_MS = 10 * 60 * 1000L

    data class PendingOp(val add: Boolean, val kind: String, val id: String)

    // ---------- fungsi murni (tanpa Android) — bisa diuji unit ----------

    /** "A|kind|id" (tambah) atau "D|kind|id" (hapus). Id boleh berisi "|" / spasi. */
    fun encodePending(add: Boolean, kind: String, id: String): String =
        (if (add) "A" else "D") + "|" + kind + "|" + id

    /** Null bila format rusak / kind kosong / id kosong. */
    fun parsePending(raw: String): PendingOp? {
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3) return null
        val (op, kind, id) = parts
        if (op != "A" && op != "D") return null
        if (kind.isBlank() || id.isBlank()) return null
        return PendingOp(op == "A", kind, id)
    }

    /**
     * Antrean baru setelah satu operasi: operasi lawan untuk kunci yang
     * sama dibuang (tambah lalu hapus = batal), lalu operasi baru masuk.
     */
    fun togglePending(existing: Set<String>, add: Boolean, kind: String, id: String): Set<String> {
        val out = existing.toMutableSet()
        out.remove(encodePending(!add, kind, id))
        out.remove(encodePending(add, kind, id))
        out.add(encodePending(add, kind, id))
        return out
    }

    /** Daftar bookmark dari JSON GET /api/cerpen-bookmarks. Null bila JSON tak valid. */
    fun parseRemoteList(json: JSONObject?): List<ProgressStore.SeriesBookmark>? {
        if (json == null || !json.optBoolean("ok", false)) return null
        val arr: JSONArray = json.optJSONArray("bookmarks") ?: return emptyList()
        val out = ArrayList<ProgressStore.SeriesBookmark>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("kind", "")
            val id = o.optString("content_id", "")
            if (kind.isBlank() || id.isBlank()) continue
            out.add(ProgressStore.SeriesBookmark(kind, id))
        }
        return out
    }

    /**
     * Gabung lokal + server (union): urutan lokal dipertahankan, lalu
     * item server yang belum ada di lokal. TIDAK menghapus apa pun.
     */
    fun union(
        local: List<ProgressStore.SeriesBookmark>,
        remote: List<ProgressStore.SeriesBookmark>
    ): List<ProgressStore.SeriesBookmark> {
        val seen = HashSet<String>()
        val out = ArrayList<ProgressStore.SeriesBookmark>()
        for (b in local) {
            val k = ProgressStore.seriesBookmarkKey(b.kind, b.id)
            if (seen.add(k)) out.add(b)
        }
        for (b in remote) {
            val k = ProgressStore.seriesBookmarkKey(b.kind, b.id)
            if (seen.add(k)) out.add(b)
        }
        return out
    }

    // ---------- antrean (SharedPreferences) ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getPending(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_PENDING, emptySet()) ?: emptySet()

    private fun savePending(ctx: Context, ops: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_PENDING, ops).apply()
    }

    fun enqueue(ctx: Context, add: Boolean, kind: String, id: String) {
        savePending(ctx, togglePending(getPending(ctx), add, kind, id))
    }

    // ---------- jaringan (HANYA dari thread latar) ----------

    /** Daftar server; null = offline / sesi mati / respons tak dikenal. */
    fun fetchRemote(ctx: Context): List<ProgressStore.SeriesBookmark>? {
        val r = SocialApi.request(ctx, "GET", "/api/cerpen-bookmarks")
        if (r.status !in 200..299) return null
        return parseRemoteList(r.json)
    }

    /**
     * Kirim tambah ke server. True = selesai (2xx, atau 400/401 — tidak
     * perlu diulang); false = gagal jaringan / 429 / 5xx (antre lagi).
     */
    fun pushAdd(ctx: Context, kind: String, id: String): Boolean {
        val body = JSONObject().apply {
            put("kind", kind)
            put("content_id", id)
        }
        val r = SocialApi.request(ctx, "POST", "/api/cerpen-bookmarks", body)
        return r.status in 200..299 || r.status == 400 || r.status == 401
    }

    /** Kirim hapus ke server. Makna true/false sama seperti [pushAdd]. */
    fun pushRemove(ctx: Context, kind: String, id: String): Boolean {
        val k = URLEncoder.encode(kind, "UTF-8")
        val c = URLEncoder.encode(id, "UTF-8")
        val r = SocialApi.request(ctx, "DELETE", "/api/cerpen-bookmarks?kind=$k&content_id=$c")
        return r.status in 200..299 || r.status == 400 || r.status == 401
    }

    private fun flushQueue(ctx: Context) {
        val pending = getPending(ctx)
        if (pending.isEmpty()) return
        val remaining = pending.toMutableSet()
        for (raw in pending) {
            val op = parsePending(raw)
            if (op == null) {
                remaining.remove(raw)
                continue
            }
            val done = if (op.add) pushAdd(ctx, op.kind, op.id) else pushRemove(ctx, op.kind, op.id)
            if (done) remaining.remove(raw)
        }
        savePending(ctx, remaining)
    }

    // ---------- titik masuk ----------

    /**
     * Dipanggil setelah toggle lokal di halaman detail. Antrekan operasi
     * lalu sinkron di latar — UI tidak menunggu jaringan.
     */
    fun onToggle(ctx: Context, kind: String, id: String, nowSaved: Boolean) {
        enqueue(ctx.applicationContext, nowSaved, kind, id)
        syncNow(ctx)
    }

    /**
     * Sinkron penuh: kirim antrean, lalu (paling cepat 10 menit sekali,
     * kecuali [force]) tarik daftar server + unggah bookmark lokal yang
     * belum ada di server + gabung bookmark server ke lokal. Guest
     * (tanpa sesi) langsung kembali — lokal saja.
     */
    fun syncNow(ctx: Context, force: Boolean = false) {
        val appCtx = ctx.applicationContext
        Thread { syncNowInternal(appCtx, force) }.start()
    }

    private fun syncNowInternal(ctx: Context, force: Boolean) {
        try {
            if (CoinStore.session(ctx).isNullOrBlank()) return
            flushQueue(ctx)
            val now = System.currentTimeMillis()
            val last = prefs(ctx).getLong(KEY_LAST_FULL_SYNC, 0)
            if (!force && now - last < FULL_SYNC_MIN_INTERVAL_MS) return
            val remote = fetchRemote(ctx) ?: return
            val local = ProgressStore.getSeriesBookmarks(ctx)
            val remoteKeys = remote.map { ProgressStore.seriesBookmarkKey(it.kind, it.id) }.toSet()
            for (b in local) {
                if (ProgressStore.seriesBookmarkKey(b.kind, b.id) !in remoteKeys) {
                    if (!pushAdd(ctx, b.kind, b.id)) enqueue(ctx, true, b.kind, b.id)
                }
            }
            val localKeys = local.map { ProgressStore.seriesBookmarkKey(it.kind, it.id) }.toSet()
            for (r in remote) {
                if (ProgressStore.seriesBookmarkKey(r.kind, r.id) !in localKeys) {
                    ProgressStore.ensureSeriesBookmarked(ctx, r.kind, r.id)
                }
            }
            prefs(ctx).edit().putLong(KEY_LAST_FULL_SYNC, now).apply()
        } catch (e: Exception) {
            // Sinkron gagal diam-diam (offline dsb.) — antrean tetap
            // tersimpan untuk percobaan berikutnya; jangan crash aplikasi.
        }
    }
}

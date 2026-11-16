package com.hozinking.cerpen

import android.content.SharedPreferences
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Penjaga posisi scroll untuk SATU daftar (v1.1.38) — dipakai halaman
 * detail episode (DetailActivity, KomikDetailActivity). MainActivity
 * memakai logika sendiri (query filter + banyak daftar + ScrollView).
 *
 * Perilaku sama: simpan saat daftar berhenti di-scroll (idle) + saat
 * onPause; restore SETELAH adapter baru dipasang dan HANYA bila user
 * belum menggulir sendiri sejak daftar tampil.
 */
class ListScrollGuard(
    private val prefs: SharedPreferences,
    private val key: String,
    private val list: RecyclerView
) {
    private var userScrolled = false

    fun attach() {
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolled = true
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    save()
                }
            }
        })
    }

    fun save() {
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        ScrollStateStore.capture(lm)?.let { ScrollStateStore.save(prefs, key, it) }
    }

    fun restore() {
        if (userScrolled) return
        if (list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val count = list.adapter?.itemCount ?: 0
        val saved = ScrollStateStore.load(prefs, key) ?: return
        val pos = ScrollStateStore.clamp(saved, count) ?: return
        ScrollStateStore.apply(lm, pos)
    }

    /** Setelah adapter baru dipasang: izinkan restore + jadwalkan (setelah layout). */
    fun requestRestore() {
        userScrolled = false
        list.post { restore() }
    }
}

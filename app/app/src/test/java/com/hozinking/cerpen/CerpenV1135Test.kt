package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit test v1.1.35 (tanpa Android): placeholder jujur + toast sync dipisah. */
class CerpenV1135Test {

    @Test
    fun toast_offline_tidakAdaInternet() {
        val r = SyncResult(changed = false, downloaded = 0, alreadyHad = 0, failed = 0, offline = true)
        assertEquals("Tidak ada internet", MainActivity.syncUnchangedToast(r))
    }

    @Test
    fun toast_online_sudahTerbaru() {
        val r = SyncResult(changed = false, downloaded = 0, alreadyHad = 0, failed = 0, offline = false)
        assertEquals("Sudah terbaru", MainActivity.syncUnchangedToast(r))
    }

    @Test
    fun toast_default_bukanOffline() {
        val r = SyncResult(changed = false, downloaded = 0, alreadyHad = 0, failed = 0)
        assertEquals("Sudah terbaru", MainActivity.syncUnchangedToast(r))
    }

    @Test
    fun toast_offline_tidakDianggapBerubah() {
        // Flag offline tidak mengubah field lain (copy preserves).
        val r = SyncResult(changed = false, downloaded = 0, alreadyHad = 3, failed = 0).copy(offline = true)
        assertEquals(3, r.alreadyHad)
        assertEquals("Tidak ada internet", MainActivity.syncUnchangedToast(r))
    }

    @Test
    fun panel_imageUrl_kosong_jadi_kosong() {
        // komik-12 e1: image_url "" di D1 -> panel.image "" -> penanda
        // "Gambar panel belum tersedia" (bukan "belum terunduh").
        assertEquals("", D1ContentBuilder.fileNameFromEncUrl(""))
    }

    @Test
    fun panel_imageUrl_penuh_jadi_namaFile() {
        assertEquals(
            "komik-10-e1-p1.jpg",
            D1ContentBuilder.fileNameFromEncUrl(
                "https://hozinking.my.id/api/cerpen-img/komik/panels/komik-10-e1-p1.jpg.enc"
            )
        )
    }
}

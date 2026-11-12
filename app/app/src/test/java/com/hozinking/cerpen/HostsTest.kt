package com.hozinking.cerpen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Unit test Hosts (v1.1.22): fallback workers.dev, tanpa jaringan. */
class HostsTest {

    @Test
    fun fallbackUrl_gantiHostPathSama() {
        assertEquals(
            "https://hozin-cms.muchammadhozin.workers.dev/api/share/create",
            Hosts.fallbackUrl("https://hozinking.my.id/api/share/create")
        )
        assertEquals(
            "https://hozin-cms.muchammadhozin.workers.dev/api/share/resolve?token=abc",
            Hosts.fallbackUrl("https://hozinking.my.id/api/share/resolve?token=abc")
        )
    }

    @Test
    fun fallbackUrl_bukanHostUtamaTidakDiubah() {
        val other = "https://example.com/x"
        assertEquals(other, Hosts.fallbackUrl(other))
    }

    @Test
    fun withFallback_hostUtamaSuksesSekaliPanggil() {
        val seen = mutableListOf<String>()
        val out = Hosts.withFallback("https://hozinking.my.id/api/cerpen-config") { url ->
            seen.add(url)
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(listOf("https://hozinking.my.id/api/cerpen-config"), seen)
    }

    @Test
    fun withFallback_gagalKoneksiCobaFallbackSekali() {
        val seen = mutableListOf<String>()
        val out = Hosts.withFallback("https://hozinking.my.id/api/share/create") { url ->
            seen.add(url)
            if (url.startsWith(Hosts.PRIMARY)) throw IOException("tidak ada koneksi")
            "dari-fallback"
        }
        assertEquals("dari-fallback", out)
        assertEquals(
            listOf(
                "https://hozinking.my.id/api/share/create",
                "https://hozin-cms.muchammadhozin.workers.dev/api/share/create"
            ),
            seen
        )
    }

    @Test
    fun withFallback_hasilNullTidakMemancingFallback() {
        // null = server menjawab HTTP error -> JANGAN coba fallback.
        val seen = mutableListOf<String>()
        val out: String? = Hosts.withFallback("https://hozinking.my.id/api/share/resolve?token=x") { url ->
            seen.add(url)
            null
        }
        assertNull(out)
        assertEquals(1, seen.size)
    }

    @Test
    fun withFallback_keduanyaGagalMelempar() {
        assertThrows(IOException::class.java) {
            Hosts.withFallback("https://hozinking.my.id/api/cerpen-config") { _ ->
                throw IOException("mati total")
            }
        }
    }
}

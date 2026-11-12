package com.hozinking.cerpen

import android.graphics.Color
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.SimpleTagHandler

/**
 * v1.1.18: handler tag HTML <font color="#RRGGBB">teks</font>.
 *
 * HtmlPlugin bawaan Markwon TIDAK punya handler warna (dicek isi AAR
 * html-4.6.2: hanya ada Strike, Underline, Emphasis, Strong, dsb.),
 * jadi dibuat sendiri. Dipakai di konten .md, contoh:
 * <font color="#E53935">teks merah</font>
 * Warna tidak valid diabaikan (teks tetap tampil normal).
 * v1.1.18: span yang dipakai adalah PulseColorSpan supaya kata berwarna
 * bisa berdenyut halus (animator diatur PulseText dari Activity).
 */
class FontColorHandler : SimpleTagHandler() {

    override fun supportedTags(): Collection<String> = listOf("font")

    override fun getSpans(
        configuration: MarkwonConfiguration,
        renderProps: RenderProps,
        tag: HtmlTag
    ): Any? {
        val color = tag.attributes()["color"] ?: return null
        return try {
            PulseColorSpan(Color.parseColor(color))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

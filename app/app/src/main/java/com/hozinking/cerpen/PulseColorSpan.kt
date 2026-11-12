package com.hozinking.cerpen

import android.graphics.Color
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

/**
 * v1.1.18: span warna yang bisa berdenyut halus.
 *
 * Dipakai untuk kata berwarna (<font color="...">). Animator di PulseText
 * mengubah [alphaFactor] dari redup ke penuh dan sebaliknya; TextView
 * di-invalidate tiap perubahan supaya warnanya ikut bergerak.
 * Bila tidak ada animator, alphaFactor tetap 1 (warna penuh, statis).
 */
class PulseColorSpan(private val baseColor: Int) : CharacterStyle(), UpdateAppearance {

    var alphaFactor: Float = 1f

    override fun updateDrawState(tp: TextPaint) {
        val a = (255 * alphaFactor.coerceIn(0f, 1f)).toInt()
        tp.color = Color.argb(a, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }
}

package com.hozinking.cerpen

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Dialog kaca ala iOS (v1.1.23): kartu tembus pandang sudut membulat
 * besar, judul + pesan rata tengah, garis pemisah, tombol berdampingan
 * (negatif abu-abu, positif aksen). NON-CANCELABLE: dialog hanya hilang
 * lewat tombolnya — tap di luar + tombol back diblokir.
 *
 * Blur latar: Window.setBackgroundBlurRadius (API 31+) mem-blur isi di
 * balik jendela dialog. Di API lebih rendah fallback-nya kartu
 * translusen + dim (tetap terbaca). RenderEffect TIDAK dipakai — ia
 * mem-blur isi view sendiri, bukan latar (lihat GlassView.kt).
 */
object GlassDialog {

    /** Satu tombol dialog. [accent] = warna aksen, bukan abu-abu. */
    data class Button(val label: String, val accent: Boolean, val onClick: () -> Unit)

    fun create(
        context: Context,
        title: String,
        message: String,
        negative: Button?,
        positive: Button
    ): AlertDialog {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val cardColor = if (night) Color.argb(0xE8, 0x2C, 0x2C, 0x2E)
            else Color.argb(0xEC, 0xF2, 0xF2, 0xF7)
        val strokeColor = if (night) Color.argb(0x66, 0xFF, 0xFF, 0xFF)
            else Color.argb(0x33, 0x00, 0x00, 0x00)
        val titleColor = if (night) Color.WHITE else Color.parseColor("#1C1B1A")
        val msgColor = if (night) Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
            else Color.parseColor("#3D3A37")
        val dividerColor = if (night) Color.argb(0x55, 0xFF, 0xFF, 0xFF)
            else Color.argb(0x44, 0x00, 0x00, 0x00)
        val negativeColor = if (night) Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
            else Color.parseColor("#6B6560")
        val accentColor = if (night) Color.parseColor("#2DD4BF")
            else ContextCompat.getColor(context, R.color.accent)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(cardColor)
                setStroke(dp(1), strokeColor)
            }
        }
        root.addView(TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), dp(20), dp(24), 0)
        })
        root.addView(TextView(context).apply {
            text = message
            gravity = Gravity.CENTER
            setTextColor(msgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(24), dp(8), dp(24), dp(20))
        })
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(dividerColor)
        })

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()
        // Non-cancelable: tap di luar + tombol back tidak menutup dialog.
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.35f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.setBackgroundBlurRadius(dp(20))
                } catch (e: Exception) {
                    // Blur tidak didukung perangkat — kartu translusen tetap jalan.
                }
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
        }
        fun buttonView(b: Button): TextView = TextView(context).apply {
            text = b.label
            gravity = Gravity.CENTER
            setTextColor(if (b.accent) accentColor else negativeColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            if (b.accent) typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                b.onClick()
                dialog.dismiss()
            }
        }
        if (negative != null) {
            row.addView(buttonView(negative))
            row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(1), LinearLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(dividerColor)
            })
        }
        row.addView(buttonView(positive))
        root.addView(row)
        return dialog
    }
}

package com.hozinking.cerpen

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Layar Pengaturan (v1.1): ukuran teks + tema berlaku di SEMUA layar
 * (Main, Detail, Reader) karena tersimpan di ProgressStore dan diterapkan
 * ulang di onCreate/onResume tiap Activity. Seksi "Lainnya" disiapkan
 * untuk pengaturan tambahan berikutnya.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var title: TextView
    private lateinit var sizeValue: TextView
    private lateinit var smallerButton: Button
    private lateinit var biggerButton: Button
    private lateinit var dayButton: Button
    private lateinit var nightButton: Button
    private lateinit var sepiaButton: Button
    private lateinit var othersTitle: TextView
    private lateinit var othersNote: TextView
    private lateinit var versionText: TextView

    private var textSizeSp: Float = 17f
    private var theme: Int = ProgressStore.THEME_DAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Pengaturan"

        root = findViewById(R.id.settings_root)
        title = findViewById(R.id.settings_title)
        sizeValue = findViewById(R.id.set_size_value)
        smallerButton = findViewById(R.id.set_smaller)
        biggerButton = findViewById(R.id.set_bigger)
        dayButton = findViewById(R.id.theme_day)
        nightButton = findViewById(R.id.theme_night)
        sepiaButton = findViewById(R.id.theme_sepia)
        othersTitle = findViewById(R.id.settings_others_title)
        othersNote = findViewById(R.id.settings_others_note)
        versionText = findViewById(R.id.settings_version)

        textSizeSp = ProgressStore.getTextSizeSp(this)
        theme = ProgressStore.getTheme(this)

        smallerButton.setOnClickListener {
            textSizeSp = (textSizeSp - 1f).coerceAtLeast(13f)
            ProgressStore.setTextSizeSp(this, textSizeSp)
            refresh()
        }
        biggerButton.setOnClickListener {
            textSizeSp = (textSizeSp + 1f).coerceAtMost(24f)
            ProgressStore.setTextSizeSp(this, textSizeSp)
            refresh()
        }
        dayButton.setOnClickListener { setThemeChoice(ProgressStore.THEME_DAY) }
        nightButton.setOnClickListener { setThemeChoice(ProgressStore.THEME_NIGHT) }
        sepiaButton.setOnClickListener { setThemeChoice(ProgressStore.THEME_SEPIA) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        textSizeSp = ProgressStore.getTextSizeSp(this)
        theme = ProgressStore.getTheme(this)
        refresh()
    }

    private fun setThemeChoice(choice: Int) {
        theme = choice
        ProgressStore.setTheme(this, theme)
        refresh()
    }

    private fun refresh() {
        val palette = ThemeHelper.palette(theme)
        root.setBackgroundColor(palette.bg)
        title.setTextColor(palette.titleInk)
        sizeValue.text = "${textSizeSp.toInt()}sp"
        sizeValue.setTextColor(palette.ink)
        othersTitle.setTextColor(palette.ink)
        othersNote.setTextColor(palette.muted)
        // v1.2: versi asli dari BuildConfig (bukan hardcode).
        versionText.text = "Cerpen Hozin v${BuildConfig.VERSION_NAME}"
        versionText.setTextColor(palette.muted)
        styleThemeButton(dayButton, theme == ProgressStore.THEME_DAY, palette)
        styleThemeButton(nightButton, theme == ProgressStore.THEME_NIGHT, palette)
        styleThemeButton(sepiaButton, theme == ProgressStore.THEME_SEPIA, palette)
    }

    private fun styleThemeButton(button: Button, selected: Boolean, palette: ThemePalette) {
        if (selected) {
            button.setBackgroundColor(palette.accent)
            button.setTextColor(palette.onAccent)
        } else {
            button.setBackgroundColor(palette.card)
            button.setTextColor(palette.accent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

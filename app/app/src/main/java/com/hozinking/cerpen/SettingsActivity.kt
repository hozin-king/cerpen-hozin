package com.hozinking.cerpen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Layar Pengaturan (v1.1.30, dirapikan): tiga kelompok — Akun, Konten,
 * Aplikasi. Ukuran teks + tema berlaku di SEMUA layar (Main, Detail,
 * Reader) karena tersimpan di ProgressStore dan diterapkan ulang di
 * onCreate/onResume tiap Activity.
 *
 * Kelompok Akun: ID perangkat (guest) + ID akun (bila login), masing-masing
 * dengan tombol salin; saldo koin + "terakhir diperbarui"; badge VIP;
 * masuk/daftar, tukar kode, keluar; privasi papan peringkat; baris
 * "Dukung kreator" (sukarela, tampil hanya bila donate_url terisi).
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
    private lateinit var checkUpdateButton: Button
    private lateinit var versionText: TextView

    // v1.1.30: kelompok Akun.
    private lateinit var accountCard: LinearLayout
    private lateinit var deviceIdLabel: TextView
    private lateinit var deviceIdValue: TextView
    private lateinit var deviceIdCopy: Button
    private lateinit var accountRow: LinearLayout
    private lateinit var accountIdLabel: TextView
    private lateinit var accountIdValue: TextView
    private lateinit var accountIdCopy: Button
    private lateinit var usernameText: TextView
    private lateinit var balanceText: TextView
    private lateinit var balanceUpdatedText: TextView
    private lateinit var vipText: TextView
    private lateinit var loginButton: Button
    private lateinit var redeemButton: Button
    private lateinit var logoutButton: Button
    private lateinit var privacyRow: LinearLayout
    private lateinit var privacyLabel: TextView
    private lateinit var privacySwitch: Switch
    private lateinit var donateRow: LinearLayout
    private lateinit var donateTitle: TextView
    private lateinit var donateSubtitle: TextView
    // v1.1.30.1: judul kelompok + label Konten (dulu statis @color/ink).
    private lateinit var sectionAccount: TextView
    private lateinit var sectionContent: TextView
    private lateinit var sectionApp: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var themeLabel: TextView

    private var textSizeSp: Float = 17f
    private var theme: Int = ProgressStore.THEME_DAY
    /** Penjaga: jangan kirim privasi saat refresh() mengisi switch. */
    private var settingPrivacyProgrammatically = false

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
        checkUpdateButton = findViewById(R.id.set_check_update)
        versionText = findViewById(R.id.settings_version)

        deviceIdLabel = findViewById(R.id.acc_device_id_label)
        deviceIdValue = findViewById(R.id.acc_device_id_value)
        deviceIdCopy = findViewById(R.id.acc_device_id_copy)
        accountCard = findViewById(R.id.acc_account_card)
        accountRow = findViewById(R.id.acc_account_row)
        accountIdLabel = findViewById(R.id.acc_account_id_label)
        accountIdValue = findViewById(R.id.acc_account_id_value)
        accountIdCopy = findViewById(R.id.acc_account_id_copy)
        usernameText = findViewById(R.id.acc_username)
        balanceText = findViewById(R.id.acc_balance)
        balanceUpdatedText = findViewById(R.id.acc_balance_updated)
        vipText = findViewById(R.id.acc_vip)
        loginButton = findViewById(R.id.acc_login_button)
        redeemButton = findViewById(R.id.acc_redeem_button)
        logoutButton = findViewById(R.id.acc_logout_button)
        privacyRow = findViewById(R.id.acc_privacy_row)
        privacyLabel = findViewById(R.id.acc_privacy_label)
        privacySwitch = findViewById(R.id.acc_privacy_switch)
        donateRow = findViewById(R.id.acc_donate_row)
        donateTitle = findViewById(R.id.acc_donate_title)
        donateSubtitle = findViewById(R.id.acc_donate_subtitle)
        sectionAccount = findViewById(R.id.settings_section_account)
        sectionContent = findViewById(R.id.settings_section_content)
        sectionApp = findViewById(R.id.settings_section_app)
        sizeLabel = findViewById(R.id.set_size_label)
        themeLabel = findViewById(R.id.set_theme_label)

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
        // v1.1.15: minta MainActivity cek update SEKARANG saat kembali
        // (pola flag prefs yang sama dengan force_tag/pending_install_tag).
        checkUpdateButton.setOnClickListener {
            getSharedPreferences("update_check", MODE_PRIVATE)
                .edit().putBoolean("manual_check_requested", true).apply()
            finish()
        }

        deviceIdCopy.setOnClickListener { copyText("ID Perangkat", CoinStore.deviceId(this)) }
        accountIdCopy.setOnClickListener {
            CoinStore.userId(this)?.let { copyText("ID Akun", it) }
        }
        loginButton.setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        redeemButton.setOnClickListener {
            startActivity(CoinUi.accountIntent(this, redeemFirst = true))
        }
        logoutButton.setOnClickListener { doLogout() }
        donateRow.setOnClickListener { CoinUi.openDonate(this) }
        privacySwitch.setOnCheckedChangeListener { _, checked ->
            if (settingPrivacyProgrammatically) return@setOnCheckedChangeListener
            setReadingPrivacy(checked)
        }

        refresh()
        // Sinkron saldo/unlock/VIP dari server (gagal = diam, cache tampil).
        CoinUi.refreshMeAsync(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        textSizeSp = ProgressStore.getTextSizeSp(this)
        theme = ProgressStore.getTheme(this)
        refresh()
        CoinUi.refreshMeAsync(this) { refresh() }
    }

    private fun setThemeChoice(choice: Int) {
        theme = choice
        ProgressStore.setTheme(this, theme)
        refresh()
    }

    private fun copyText(label: String, value: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label disalin", Toast.LENGTH_SHORT).show()
    }

    private fun setReadingPrivacy(private: Boolean) {
        Thread {
            val r = CoinApi.setReadingPrivacy(this, private)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    r.isNetworkError -> {
                        Toast.makeText(this, "Tidak ada internet — pengaturan tidak tersimpan", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    r.status == 403 && CoinGate.bannedMessage(r.json) != null -> {
                        CoinUi.showBanned(this, r.json)
                        refresh()
                    }
                    r.status in 200..299 -> {
                        CoinStore.setReadingPrivate(this, private)
                        Toast.makeText(
                            this,
                            if (private) "Kamu disembunyikan dari papan peringkat"
                            else "Kamu tampil lagi di papan peringkat",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        Toast.makeText(this, CoinApi.errorMessage(r.json, "Gagal menyimpan"), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
        }.start()
    }

    private fun doLogout() {
        Thread {
            // Best-effort: sesi stateless, yang penting sesi lokal dibuang.
            try { CoinApi.logout(this) } catch (e: Exception) { /* abaikan */ }
            runOnUiThread {
                CoinStore.clearSession(this)
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, "Keluar — kembali jadi guest", Toast.LENGTH_SHORT).show()
                CoinUi.refreshMeAsync(this) { refresh() }
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val palette = ThemeHelper.palette(theme)
        root.setBackgroundColor(palette.bg)
        title.setTextColor(palette.titleInk)
        sizeValue.text = "${textSizeSp.toInt()}sp"
        sizeValue.setTextColor(palette.ink)
        styleThemeButton(checkUpdateButton, true, palette)
        // v1.2: versi asli dari BuildConfig (bukan hardcode).
        versionText.text = "Cerpen Hozin v${BuildConfig.VERSION_NAME}"
        versionText.setTextColor(palette.muted)
        styleThemeButton(dayButton, theme == ProgressStore.THEME_DAY, palette)
        styleThemeButton(nightButton, theme == ProgressStore.THEME_NIGHT, palette)
        styleThemeButton(sepiaButton, theme == ProgressStore.THEME_SEPIA, palette)

        // ---- Kelompok Akun (v1.1.30) ----
        // v1.1.30.1: kartu + label ikut palet — dulu kartu statis putih
        // (card_bg) sementara teks runtime terang di tema Malam.
        accountCard.background = ThemeHelper.cardBackground(this, palette)
        donateRow.background = ThemeHelper.cardBackground(this, palette)
        sectionAccount.setTextColor(palette.ink)
        sectionContent.setTextColor(palette.ink)
        sectionApp.setTextColor(palette.ink)
        sizeLabel.setTextColor(palette.ink)
        themeLabel.setTextColor(palette.ink)
        deviceIdLabel.setTextColor(palette.muted)
        accountIdLabel.setTextColor(palette.muted)
        privacyLabel.setTextColor(palette.ink)
        donateTitle.setTextColor(palette.ink)
        donateSubtitle.setTextColor(palette.muted)
        val state = CoinStore.snapshot(this)
        val now = System.currentTimeMillis()
        deviceIdValue.text = CoinStore.deviceId(this)
        deviceIdValue.setTextColor(palette.ink)
        balanceText.text = "${state.balance} koin"
        balanceText.setTextColor(palette.ink)
        balanceUpdatedText.text =
            "terakhir diperbarui: ${CoinGate.updatedAtText(state.balanceUpdatedAtMs, now)}"
        balanceUpdatedText.setTextColor(palette.muted)
        val vipLeft = CoinGate.vipRemainingText(state.vipExpiresAtMs, now)
        if (vipLeft != null) {
            vipText.visibility = View.VISIBLE
            vipText.text = "VIP aktif · $vipLeft"
            vipText.setTextColor(palette.accent)
        } else {
            vipText.visibility = View.GONE
        }
        if (state.hasSession) {
            accountRow.visibility = View.VISIBLE
            accountIdValue.text = state.userId ?: "-"
            accountIdValue.setTextColor(palette.ink)
            usernameText.text = state.username ?: "Akun"
            usernameText.setTextColor(palette.ink)
            loginButton.visibility = View.GONE
            logoutButton.visibility = View.VISIBLE
            privacyRow.visibility = View.VISIBLE
            settingPrivacyProgrammatically = true
            privacySwitch.isChecked = state.readingPrivate
            settingPrivacyProgrammatically = false
        } else {
            accountRow.visibility = View.GONE
            loginButton.visibility = View.VISIBLE
            logoutButton.visibility = View.GONE
            privacyRow.visibility = View.GONE
        }
        styleThemeButton(loginButton, true, palette)
        styleThemeButton(redeemButton, true, palette)
        styleThemeButton(logoutButton, false, palette)
        styleThemeButton(deviceIdCopy, true, palette)
        styleThemeButton(accountIdCopy, true, palette)
        // Dukung kreator: tampil hanya bila donate_url terisi (config online).
        donateRow.visibility =
            if (RemoteConfig.loadCached(this).donateUrl.isNotBlank()) View.VISIBLE else View.GONE
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

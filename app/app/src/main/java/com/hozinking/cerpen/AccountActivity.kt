package com.hozinking.cerpen

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Layar Akun (v1.1.30): login / daftar / verifikasi OTP / tukar kode.
 *
 * Semua mutasi lewat CoinApi (server). Beli/redeem butuh online;
 * offline = tombol tetap bisa ditekan tapi gagal dengan pesan jelas
 * (tidak ada status palsu).
 */
class AccountActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REDEEM_FIRST = "redeem_first"
    }

    private lateinit var root: View
    private lateinit var balanceText: TextView
    private lateinit var balanceUpdatedText: TextView
    private lateinit var loggedInSection: LinearLayout
    private lateinit var usernameText: TextView
    private lateinit var vipText: TextView
    private lateinit var accountIdValue: TextView
    private lateinit var accountIdCopy: Button
    private lateinit var logoutButton: Button
    private lateinit var loginSection: LinearLayout
    private lateinit var loginUsername: EditText
    private lateinit var loginPassword: EditText
    private lateinit var loginButton: Button
    private lateinit var loginToRegister: TextView
    private lateinit var registerSection: LinearLayout
    private lateinit var registerUsername: EditText
    private lateinit var registerEmail: EditText
    private lateinit var registerPassword: EditText
    private lateinit var registerButton: Button
    private lateinit var registerToLogin: TextView
    private lateinit var verifySection: LinearLayout
    private lateinit var verifyEmailText: TextView
    private lateinit var verifyCode: EditText
    private lateinit var verifyButton: Button
    private lateinit var verifyResend: TextView
    private lateinit var redeemCode: EditText
    private lateinit var redeemButton: Button
    private lateinit var redeemResult: TextView
    private lateinit var statusText: TextView

    private var verifyEmail: String = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Akun"

        root = findViewById(R.id.account_root)
        balanceText = findViewById(R.id.account_balance)
        balanceUpdatedText = findViewById(R.id.account_balance_updated)
        loggedInSection = findViewById(R.id.account_logged_in)
        usernameText = findViewById(R.id.account_username)
        vipText = findViewById(R.id.account_vip)
        accountIdValue = findViewById(R.id.account_id_value)
        accountIdCopy = findViewById(R.id.account_id_copy)
        logoutButton = findViewById(R.id.account_logout)
        loginSection = findViewById(R.id.account_login_section)
        loginUsername = findViewById(R.id.login_username)
        loginPassword = findViewById(R.id.login_password)
        loginButton = findViewById(R.id.login_button)
        loginToRegister = findViewById(R.id.login_to_register)
        registerSection = findViewById(R.id.account_register_section)
        registerUsername = findViewById(R.id.register_username)
        registerEmail = findViewById(R.id.register_email)
        registerPassword = findViewById(R.id.register_password)
        registerButton = findViewById(R.id.register_button)
        registerToLogin = findViewById(R.id.register_to_login)
        verifySection = findViewById(R.id.account_verify_section)
        verifyEmailText = findViewById(R.id.verify_email_text)
        verifyCode = findViewById(R.id.verify_code)
        verifyButton = findViewById(R.id.verify_button)
        verifyResend = findViewById(R.id.verify_resend)
        redeemCode = findViewById(R.id.redeem_code)
        redeemButton = findViewById(R.id.redeem_button)
        redeemResult = findViewById(R.id.redeem_result)
        statusText = findViewById(R.id.account_status)

        loginToRegister.setOnClickListener { showSection(registerSection) }
        registerToLogin.setOnClickListener { showSection(loginSection) }
        loginButton.setOnClickListener { doLogin() }
        registerButton.setOnClickListener { doRegister() }
        verifyButton.setOnClickListener { doVerify() }
        verifyResend.setOnClickListener { doResend() }
        redeemButton.setOnClickListener { doRedeem() }
        logoutButton.setOnClickListener { doLogout() }
        accountIdCopy.setOnClickListener {
            val id = CoinStore.userId(this) ?: return@setOnClickListener
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ID Akun", id))
            Toast.makeText(this, "ID akun disalin", Toast.LENGTH_SHORT).show()
        }

        if (intent.getBooleanExtra(EXTRA_REDEEM_FIRST, false)) {
            redeemCode.requestFocus()
        }
        refresh()
        // Sinkron saldo/unlock dari server (gagal = diam, cache tetap tampil).
        CoinUi.refreshMeAsync(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showSection(section: LinearLayout) {
        loginSection.visibility = View.GONE
        registerSection.visibility = View.GONE
        verifySection.visibility = View.GONE
        section.visibility = View.VISIBLE
    }

    private fun refresh() {
        val palette = ThemeHelper.palette(ProgressStore.getTheme(this))
        root.setBackgroundColor(palette.bg)
        val state = CoinStore.snapshot(this)
        balanceText.text = "${state.balance} koin"
        balanceText.setTextColor(palette.ink)
        balanceUpdatedText.text =
            "terakhir diperbarui: ${CoinGate.updatedAtText(state.balanceUpdatedAtMs, System.currentTimeMillis())}"
        balanceUpdatedText.setTextColor(palette.muted)
        if (state.hasSession) {
            loggedInSection.visibility = View.VISIBLE
            loginSection.visibility = View.GONE
            registerSection.visibility = View.GONE
            usernameText.text = state.username ?: "Akun"
            usernameText.setTextColor(palette.ink)
            accountIdValue.text = "ID akun: ${state.userId ?: "-"}"
            accountIdValue.setTextColor(palette.muted)
            val vipLeft = CoinGate.vipRemainingText(state.vipExpiresAtMs, System.currentTimeMillis())
            if (vipLeft != null) {
                vipText.visibility = View.VISIBLE
                vipText.text = "VIP aktif · $vipLeft"
                vipText.setTextColor(palette.accent)
            } else {
                vipText.visibility = View.GONE
            }
            verifySection.visibility = View.GONE
        } else {
            loggedInSection.visibility = View.GONE
            if (verifySection.visibility != View.VISIBLE && registerSection.visibility != View.VISIBLE) {
                loginSection.visibility = View.VISIBLE
            }
        }
        styleButton(loginButton, palette)
        styleButton(registerButton, palette)
        styleButton(verifyButton, palette)
        styleButton(redeemButton, palette)
        styleButton(logoutButton, palette)
        styleButton(accountIdCopy, palette)
    }

    private fun styleButton(button: Button, palette: ThemePalette) {
        button.setBackgroundColor(palette.accent)
        button.setTextColor(palette.onAccent)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        loginButton.isEnabled = !b
        registerButton.isEnabled = !b
        verifyButton.isEnabled = !b
        redeemButton.isEnabled = !b
        logoutButton.isEnabled = !b
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun doLogin() {
        if (busy) return
        val u = loginUsername.text.toString().trim()
        val p = loginPassword.text.toString()
        if (u.isEmpty() || p.isEmpty()) {
            toast("Isi nama pengguna dan kata sandi")
            return
        }
        setBusy(true)
        statusText.text = "Masuk…"
        Thread {
            val r = CoinApi.login(this, u, p)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    r.isNetworkError -> {
                        statusText.text = ""
                        toast("Tidak ada internet — coba lagi saat online")
                    }
                    r.status == 403 && CoinGate.bannedMessage(r.json) != null -> {
                        statusText.text = ""
                        CoinUi.showBanned(this, r.json)
                    }
                    r.status == 403 && r.json?.optBoolean("needs_verification", false) == true -> {
                        // Akun belum verifikasi — lanjut ke OTP.
                        verifyEmail = r.json?.optString("email", "") ?: ""
                        statusText.text = ""
                        showVerify(verifyEmail)
                        toast("Akun belum diverifikasi — cek email kamu")
                    }
                    r.status in 200..299 -> {
                        val s = CoinApi.parseSession(r.json)
                        if (s != null) {
                            CoinStore.setSession(this, s.session, s.username, s.ownerKey)
                            statusText.text = ""
                            toast("Masuk sebagai ${s.username ?: u}")
                            CoinUi.refreshMeAsync(this) { refresh() }
                            refresh()
                        } else {
                            statusText.text = ""
                            toast("Respons server tidak dikenal")
                        }
                    }
                    else -> {
                        statusText.text = ""
                        toast(CoinApi.errorMessage(r.json, "Gagal masuk — periksa nama pengguna / kata sandi"))
                    }
                }
            }
        }.start()
    }

    private fun doRegister() {
        if (busy) return
        val u = registerUsername.text.toString().trim()
        val e = registerEmail.text.toString().trim()
        val p = registerPassword.text.toString()
        if (u.isEmpty() || e.isEmpty() || p.length < 8) {
            toast("Isi nama pengguna, email, dan kata sandi (min. 8 karakter)")
            return
        }
        setBusy(true)
        statusText.text = "Mendaftar…"
        Thread {
            val r = CoinApi.register(this, u, p, e)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    r.isNetworkError -> {
                        statusText.text = ""
                        toast("Tidak ada internet — coba lagi saat online")
                    }
                    r.status == 403 && CoinGate.bannedMessage(r.json) != null -> {
                        statusText.text = ""
                        CoinUi.showBanned(this, r.json)
                    }
                    r.status in 200..299 -> {
                        val s = CoinApi.parseSession(r.json)
                        if (s != null) {
                            // Langsung aktif (tanpa OTP) — simpan sesi.
                            CoinStore.setSession(this, s.session, s.username, s.ownerKey)
                            statusText.text = ""
                            toast("Akun dibuat — selamat datang, ${s.username ?: u}")
                            CoinUi.refreshMeAsync(this) { refresh() }
                            refresh()
                        } else {
                            // Butuh verifikasi email.
                            verifyEmail = e
                            statusText.text = ""
                            showVerify(e)
                            toast("Kode verifikasi dikirim ke $e")
                        }
                    }
                    else -> {
                        statusText.text = ""
                        toast(CoinApi.errorMessage(r.json, "Gagal mendaftar"))
                    }
                }
            }
        }.start()
    }

    private fun showVerify(email: String) {
        verifyEmail = email
        verifyEmailText.text = "Kode 6 digit dikirim ke $email"
        showSection(verifySection)
        refresh()
    }

    private fun doVerify() {
        if (busy) return
        val code = verifyCode.text.toString().trim()
        if (code.length < 6) {
            toast("Masukkan kode 6 digit")
            return
        }
        setBusy(true)
        statusText.text = "Memverifikasi…"
        Thread {
            val r = CoinApi.verify(this, verifyEmail, code)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    r.isNetworkError -> {
                        statusText.text = ""
                        toast("Tidak ada internet — coba lagi saat online")
                    }
                    r.status == 403 && CoinGate.bannedMessage(r.json) != null -> {
                        statusText.text = ""
                        CoinUi.showBanned(this, r.json)
                    }
                    r.status in 200..299 -> {
                        val s = CoinApi.parseSession(r.json)
                        if (s != null) {
                            CoinStore.setSession(this, s.session, s.username, s.ownerKey)
                            statusText.text = ""
                            toast("Email terverifikasi — selamat datang!")
                            CoinUi.refreshMeAsync(this) { refresh() }
                            refresh()
                        } else {
                            statusText.text = ""
                            toast("Verifikasi gagal — coba lagi")
                        }
                    }
                    else -> {
                        statusText.text = ""
                        toast(CoinApi.errorMessage(r.json, "Kode salah atau kedaluwarsa"))
                    }
                }
            }
        }.start()
    }

    private fun doResend() {
        if (busy || verifyEmail.isEmpty()) return
        setBusy(true)
        Thread {
            val r = CoinApi.resend(this, verifyEmail)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(
                    if (r.isNetworkError) "Tidak ada internet — coba lagi saat online"
                    else if (r.status in 200..299) "Kode baru dikirim ke $verifyEmail"
                    else CoinApi.errorMessage(r.json, "Gagal mengirim ulang kode")
                )
            }
        }.start()
    }

    private fun doRedeem() {
        if (busy) return
        val code = redeemCode.text.toString().trim()
        if (code.isEmpty()) {
            toast("Masukkan kode voucher")
            return
        }
        setBusy(true)
        redeemResult.text = "Menukar…"
        Thread {
            val r = CoinApi.redeem(this, code)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    r.isNetworkError -> {
                        redeemResult.text = ""
                        toast("Tidak ada internet — tukar kode butuh online")
                    }
                    r.status == 403 && CoinGate.bannedMessage(r.json) != null -> {
                        redeemResult.text = ""
                        CoinUi.showBanned(this, r.json)
                    }
                    r.status in 200..299 -> {
                        val j = r.json
                        val rewardType = j?.optString("reward_type", "") ?: ""
                        val msg = when (rewardType) {
                            "coins" -> {
                                val coins = j?.optInt("coins", 0) ?: 0
                                val bal = j?.optInt("balance", -1) ?: -1
                                if (bal >= 0) CoinStore.setBalance(this, bal)
                                "Dapat $coins koin!"
                            }
                            "vip" -> {
                                val days = j?.optInt("vip_days", 0) ?: 0
                                "VIP aktif $days hari!"
                            }
                            else -> {
                                val label = j?.optString("reward_label", "") ?: ""
                                val granted = j?.optInt("unlocks_granted", 0) ?: 0
                                val already = j?.optBoolean("already", false) ?: false
                                if (already) "Kode ini sudah pernah ditukar"
                                else if (label.isNotEmpty()) "Terbuka: $label ($granted episode)"
                                else "Voucher berhasil ditukar"
                            }
                        }
                        redeemResult.text = msg
                        redeemCode.setText("")
                        // Sinkron unlock/saldo/VIP dari server.
                        CoinUi.refreshMeAsync(this) { refresh() }
                        refresh()
                    }
                    else -> {
                        redeemResult.text = ""
                        toast(CoinApi.errorMessage(r.json, "Kode tidak valid atau sudah dipakai"))
                    }
                }
            }
        }.start()
    }

    private fun doLogout() {
        if (busy) return
        setBusy(true)
        Thread {
            // Best-effort: sesi stateless, yang penting sesi lokal dibuang.
            try { CoinApi.logout(this) } catch (e: Exception) { /* abaikan */ }
            runOnUiThread {
                CoinStore.clearSession(this)
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast("Keluar — kembali jadi guest")
                CoinUi.refreshMeAsync(this) { refresh() }
                refresh()
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

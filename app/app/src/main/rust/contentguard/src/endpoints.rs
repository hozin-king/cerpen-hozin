//! Endpoints — penyedia URL/endpoint server Cerpen dari Rust (Fase 2).
//!
//! Semua URL/endpoint server yang dulu literal string di Kotlin kini
//! disediakan modul ini lewat JNI (`Endpoints.kt`). Template URL disimpan
//! di `endpoints_data.rs` sebagai byte ter-XOR dengan mask tetap
//! (generate ulang: `gen_endpoints.py`) — BUKAN plaintext di `.so`.
//!
//! Yang dipindah = PENYEDIAAN URL saja. Transport HTTP tetap Kotlin
//! (`HttpURLConnection`, `CerpenApi` / `CoinApi` / `SocialApi` / ...).
//! Perilaku URL SAMA PERSIS dengan versi Kotlin (urutan query,
//! encoding argumen tetap di Kotlin, interpolasi mentah di Rust sama
//! seperti interpolasi mentah di Kotlin).
//!
//! Jujur: ini obfuskasi, BUKAN enkripsi — menghentikan `strings` / dump
//! DEX kasual. URL-nya sendiri publik (situs user); yang disembunyikan
//! adalah keberadaannya sebagai string polos di DEX / `.so`.
//!
//! Fail-closed: decode gagal (UTF-8 tak valid — seharusnya mustahil,
//! tabel tetap) -> string kosong; pemanggil Kotlin sudah menangani URL
//! kosong sebagai gagal jaringan (null), tanpa crash.

use crate::endpoints_data as data;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;

/// Buka XOR satu template: byte[i] ^ mask[i % 8]. `black_box` supaya
/// compiler tidak melipat hasil decode di compile time (lto = "fat").
fn decode(obf: &[u8]) -> String {
    let obf = std::hint::black_box(obf);
    let mask = std::hint::black_box(&data::URL_MASK);
    let plain: Vec<u8> = obf
        .iter()
        .enumerate()
        .map(|(i, b)| b ^ mask[i % 8])
        .collect();
    String::from_utf8(plain).unwrap_or_default()
}

/// Ganti placeholder {0} {1} {2} dengan argumen (sekali tiap placeholder).
fn build(template: &[u8], args: &[&str]) -> String {
    let mut s = decode(template);
    for (i, a) in args.iter().enumerate() {
        s = s.replacen(&format!("{{{}}}", i), a, 1);
    }
    s
}

// ---------- penyedia per endpoint ----------

pub fn primary_host() -> String {
    decode(data::PRIMARY_HOST)
}
pub fn fallback_host() -> String {
    decode(data::FALLBACK_HOST)
}
pub fn content_manifest_url(hash: &str) -> String {
    build(data::CONTENT_MANIFEST, &[hash])
}
pub fn content_all_url(hash: &str) -> String {
    build(data::CONTENT_ALL, &[hash])
}
pub fn remote_config_url() -> String {
    decode(data::REMOTE_CONFIG)
}
pub fn version_check_url() -> String {
    decode(data::VERSION_CHECK)
}
pub fn releases_page_url() -> String {
    decode(data::RELEASES_PAGE)
}
pub fn share_create_url() -> String {
    decode(data::SHARE_CREATE)
}
pub fn share_resolve_url(token: &str) -> String {
    build(data::SHARE_RESOLVE, &[token])
}
pub fn cms_image_url(asset_path: &str, thumb: bool) -> String {
    build(
        data::CMS_IMAGE,
        &[asset_path, if thumb { ".thumb.enc" } else { ".enc" }],
    )
}
pub fn github_manifest_url() -> String {
    decode(data::GITHUB_MANIFEST)
}
pub fn github_file_url(file: &str) -> String {
    build(data::GITHUB_FILE, &[file])
}
pub fn github_asset_url(asset_path: &str) -> String {
    build(data::GITHUB_ASSET, &[asset_path])
}
pub fn coins_me_url(device_id: &str) -> String {
    build(data::COINS_ME, &[device_id])
}
pub fn coins_purchase_url() -> String {
    decode(data::COINS_PURCHASE)
}
pub fn coins_redeem_url() -> String {
    decode(data::COINS_REDEEM)
}
pub fn coins_reading_url() -> String {
    decode(data::COINS_READING)
}
pub fn coins_leaderboard_url(period: &str) -> String {
    build(data::COINS_LEADERBOARD, &[period])
}
pub fn coins_history_url(device_id: &str) -> String {
    build(data::COINS_HISTORY, &[device_id])
}
pub fn auth_reading_privacy_url() -> String {
    decode(data::AUTH_READING_PRIVACY)
}
pub fn auth_register_url() -> String {
    decode(data::AUTH_REGISTER)
}
pub fn auth_verify_url() -> String {
    decode(data::AUTH_VERIFY)
}
pub fn auth_resend_url() -> String {
    decode(data::AUTH_RESEND)
}
pub fn auth_login_url() -> String {
    decode(data::AUTH_LOGIN)
}
pub fn auth_logout_url() -> String {
    decode(data::AUTH_LOGOUT)
}
pub fn social_view_url() -> String {
    decode(data::SOCIAL_VIEW)
}
pub fn social_vote_url() -> String {
    decode(data::SOCIAL_VOTE)
}
pub fn social_report_url() -> String {
    decode(data::SOCIAL_REPORT)
}
pub fn social_stats_url(kind: &str, ref_: &str, device_id: &str) -> String {
    build(data::SOCIAL_STATS, &[kind, ref_, device_id])
}
pub fn social_stats_batch_url() -> String {
    decode(data::SOCIAL_STATS_BATCH)
}
pub fn bookmarks_url() -> String {
    decode(data::BOOKMARKS)
}
pub fn bookmarks_delete_url(kind: &str, content_id: &str) -> String {
    build(data::BOOKMARKS_DELETE, &[kind, content_id])
}

// ---------- paket admin (cerpen-track) ----------

pub fn track_register_url() -> String {
    decode(data::TRACK_REGISTER)
}
pub fn track_heartbeat_url() -> String {
    decode(data::TRACK_HEARTBEAT)
}
pub fn track_status_url() -> String {
    decode(data::TRACK_STATUS)
}
pub fn track_session_end_url() -> String {
    decode(data::TRACK_SESSION_END)
}
pub fn track_key_rotate_url() -> String {
    decode(data::TRACK_KEY_ROTATE)
}

/// Parser Adapters (dulu `contains("/api/cerpen-img/")` +
/// `substringAfter` + `removeSuffix(".enc")` di Kotlin): URL gambar CMS
/// -> assetPath (tanpa ".enc"); `None` bila bukan URL gambar CMS.
pub fn image_asset_path(url: &str) -> Option<String> {
    let marker = decode(data::IMAGE_MARKER);
    let after = url.split_once(&marker)?.1;
    let suffix = decode(data::ENC_SUFFIX);
    Some(after.strip_suffix(&suffix).unwrap_or(after).to_string())
}

// ---------- JNI ----------

fn jstr<'local>(env: &mut JNIEnv<'local>, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn jarg<'local>(env: &mut JNIEnv<'local>, s: JString<'local>) -> String {
    env.get_string(&s).map(|j| j.into()).unwrap_or_default()
}

macro_rules! jni_noarg {
    ($rust_fn:ident, $jni_name:ident) => {
        #[no_mangle]
        pub extern "system" fn $jni_name<'local>(
            mut env: JNIEnv<'local>,
            _this: JObject<'local>,
        ) -> jstring {
            let s = $rust_fn();
            jstr(&mut env, &s)
        }
    };
}

macro_rules! jni_onearg {
    ($rust_fn:ident, $jni_name:ident) => {
        #[no_mangle]
        pub extern "system" fn $jni_name<'local>(
            mut env: JNIEnv<'local>,
            _this: JObject<'local>,
            a0: JString<'local>,
        ) -> jstring {
            let a0 = jarg(&mut env, a0);
            let s = $rust_fn(&a0);
            jstr(&mut env, &s)
        }
    };
}

macro_rules! jni_twoarg {
    ($rust_fn:ident, $jni_name:ident) => {
        #[no_mangle]
        pub extern "system" fn $jni_name<'local>(
            mut env: JNIEnv<'local>,
            _this: JObject<'local>,
            a0: JString<'local>,
            a1: JString<'local>,
        ) -> jstring {
            let a0 = jarg(&mut env, a0);
            let a1 = jarg(&mut env, a1);
            let s = $rust_fn(&a0, &a1);
            jstr(&mut env, &s)
        }
    };
}

jni_noarg!(primary_host, Java_com_hozinking_cerpen_Endpoints_primaryHostNative);
jni_noarg!(fallback_host, Java_com_hozinking_cerpen_Endpoints_fallbackHostNative);
jni_noarg!(remote_config_url, Java_com_hozinking_cerpen_Endpoints_remoteConfigUrlNative);
jni_noarg!(version_check_url, Java_com_hozinking_cerpen_Endpoints_versionCheckUrlNative);
jni_noarg!(releases_page_url, Java_com_hozinking_cerpen_Endpoints_releasesPageUrlNative);
jni_noarg!(share_create_url, Java_com_hozinking_cerpen_Endpoints_shareCreateUrlNative);
jni_noarg!(github_manifest_url, Java_com_hozinking_cerpen_Endpoints_githubManifestUrlNative);
jni_noarg!(coins_purchase_url, Java_com_hozinking_cerpen_Endpoints_coinsPurchaseUrlNative);
jni_noarg!(coins_redeem_url, Java_com_hozinking_cerpen_Endpoints_coinsRedeemUrlNative);
jni_noarg!(coins_reading_url, Java_com_hozinking_cerpen_Endpoints_coinsReadingUrlNative);
jni_noarg!(auth_reading_privacy_url, Java_com_hozinking_cerpen_Endpoints_authReadingPrivacyUrlNative);
jni_noarg!(auth_register_url, Java_com_hozinking_cerpen_Endpoints_authRegisterUrlNative);
jni_noarg!(auth_verify_url, Java_com_hozinking_cerpen_Endpoints_authVerifyUrlNative);
jni_noarg!(auth_resend_url, Java_com_hozinking_cerpen_Endpoints_authResendUrlNative);
jni_noarg!(auth_login_url, Java_com_hozinking_cerpen_Endpoints_authLoginUrlNative);
jni_noarg!(auth_logout_url, Java_com_hozinking_cerpen_Endpoints_authLogoutUrlNative);
jni_noarg!(social_view_url, Java_com_hozinking_cerpen_Endpoints_socialViewUrlNative);
jni_noarg!(social_vote_url, Java_com_hozinking_cerpen_Endpoints_socialVoteUrlNative);
jni_noarg!(social_report_url, Java_com_hozinking_cerpen_Endpoints_socialReportUrlNative);
jni_noarg!(social_stats_batch_url, Java_com_hozinking_cerpen_Endpoints_socialStatsBatchUrlNative);
jni_noarg!(bookmarks_url, Java_com_hozinking_cerpen_Endpoints_bookmarksUrlNative);
jni_noarg!(track_register_url, Java_com_hozinking_cerpen_Endpoints_trackRegisterUrlNative);
jni_noarg!(track_heartbeat_url, Java_com_hozinking_cerpen_Endpoints_trackHeartbeatUrlNative);
jni_noarg!(track_status_url, Java_com_hozinking_cerpen_Endpoints_trackStatusUrlNative);
jni_noarg!(track_session_end_url, Java_com_hozinking_cerpen_Endpoints_trackSessionEndUrlNative);
jni_noarg!(track_key_rotate_url, Java_com_hozinking_cerpen_Endpoints_trackKeyRotateUrlNative);

jni_onearg!(content_manifest_url, Java_com_hozinking_cerpen_Endpoints_contentManifestUrlNative);
jni_onearg!(content_all_url, Java_com_hozinking_cerpen_Endpoints_contentAllUrlNative);
jni_onearg!(share_resolve_url, Java_com_hozinking_cerpen_Endpoints_shareResolveUrlNative);
jni_onearg!(github_file_url, Java_com_hozinking_cerpen_Endpoints_githubFileUrlNative);
jni_onearg!(github_asset_url, Java_com_hozinking_cerpen_Endpoints_githubAssetUrlNative);
jni_onearg!(coins_me_url, Java_com_hozinking_cerpen_Endpoints_coinsMeUrlNative);
jni_onearg!(coins_leaderboard_url, Java_com_hozinking_cerpen_Endpoints_coinsLeaderboardUrlNative);
jni_onearg!(coins_history_url, Java_com_hozinking_cerpen_Endpoints_coinsHistoryUrlNative);

jni_twoarg!(bookmarks_delete_url, Java_com_hozinking_cerpen_Endpoints_bookmarksDeleteUrlNative);

#[no_mangle]
pub extern "system" fn Java_com_hozinking_cerpen_Endpoints_cmsImageUrlNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    asset: JString<'local>,
    thumb: jboolean,
) -> jstring {
    let asset = jarg(&mut env, asset);
    let s = cms_image_url(&asset, thumb != 0);
    jstr(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_hozinking_cerpen_Endpoints_socialStatsUrlNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    kind: JString<'local>,
    ref_: JString<'local>,
    device_id: JString<'local>,
) -> jstring {
    let kind = jarg(&mut env, kind);
    let ref_ = jarg(&mut env, ref_);
    let device_id = jarg(&mut env, device_id);
    let s = social_stats_url(&kind, &ref_, &device_id);
    jstr(&mut env, &s)
}

#[no_mangle]
pub extern "system" fn Java_com_hozinking_cerpen_Endpoints_imageAssetPathNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
) -> jstring {
    let url = jarg(&mut env, url);
    match image_asset_path(&url) {
        Some(a) => jstr(&mut env, &a),
        None => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Round-trip decode dengan vektor PALSU (bukan URL asli): XOR-kan
    /// string contoh dengan mask asli, decode harus mengembalikannya.
    #[test]
    fn decode_round_trip_vektor_palsu() {
        let plain = "https://contoh.test/x?y=1";
        let obf: Vec<u8> = plain
            .as_bytes()
            .iter()
            .enumerate()
            .map(|(i, b)| b ^ data::URL_MASK[i % 8])
            .collect();
        assert_eq!(decode(&obf), plain);
    }

    #[test]
    fn build_ganti_placeholder_berurutan() {
        let plain = "a{0}b{1}c{0}";
        let obf: Vec<u8> = plain
            .as_bytes()
            .iter()
            .enumerate()
            .map(|(i, b)| b ^ data::URL_MASK[i % 8])
            .collect();
        assert_eq!(build(&obf, &["X", "Y"]), "aXbYc{0}");
    }

    /// Semua penyedia tanpa argumen: tidak kosong, https, tanpa sisa
    /// placeholder. Nilai persis TIDAK di-assert di sini (itu dicek
    /// terpisah di /tmp terhadap sumber Kotlin lama, tanpa menulis
    /// URL ke laporan).
    #[test]
    fn semua_url_tanpa_argumen_berbentuk_url() {
        let urls = [
            primary_host(),
            fallback_host(),
            remote_config_url(),
            version_check_url(),
            releases_page_url(),
            share_create_url(),
            github_manifest_url(),
            coins_purchase_url(),
            coins_redeem_url(),
            coins_reading_url(),
            auth_reading_privacy_url(),
            auth_register_url(),
            auth_verify_url(),
            auth_resend_url(),
            auth_login_url(),
            auth_logout_url(),
            social_view_url(),
            social_vote_url(),
            social_report_url(),
            social_stats_batch_url(),
            bookmarks_url(),
        ];
        assert_eq!(urls.len(), 21);
        for u in &urls {
            assert!(!u.is_empty(), "URL kosong");
            assert!(u.starts_with("https://"), "bukan https: {u}");
            assert!(!u.contains("{0}"), "placeholder tersisa: {u}");
        }
        // Host utama != fallback; semua URL berbeda satu sama lain.
        let mut uniq = urls.to_vec();
        uniq.sort();
        uniq.dedup();
        assert_eq!(uniq.len(), urls.len(), "ada URL duplikat");
    }

    #[test]
    fn url_berparameter_mengandung_argumen() {
        assert!(content_manifest_url("abc123").contains("abc123"));
        assert!(content_all_url("abc123").contains("abc123"));
        assert!(share_resolve_url("tok9").contains("tok9"));
        assert!(github_file_url("cerpen.json.enc").contains("cerpen.json.enc"));
        assert!(github_asset_url("covers/cover_01.jpg").contains("covers/cover_01.jpg"));
        assert!(coins_me_url("dev-1").contains("dev-1"));
        assert!(coins_leaderboard_url("weekly").contains("weekly"));
        assert!(coins_history_url("dev-1").contains("dev-1"));
        assert!(social_stats_url("cerpen", "c1", "dev-1").contains("cerpen"));
        assert!(bookmarks_delete_url("cerpen", "c1").contains("c1"));
        // cmsImageUrl: thumb menambah ".thumb" sebelum ".enc".
        let full = cms_image_url("covers/cover_01.jpg", false);
        let thumb = cms_image_url("covers/cover_01.jpg", true);
        assert!(full.ends_with(".enc") && !full.contains(".thumb"));
        assert!(thumb.contains(".thumb.enc"));
        assert!(full.contains("covers/cover_01.jpg"));
    }

    #[test]
    fn image_asset_path_meniru_logika_kotlin() {
        // Dibangun dari marker hasil decode (tanpa menulis URL asli).
        let marker = decode(data::IMAGE_MARKER);
        let good = format!("{}covers/cover_01.jpg.enc", marker);
        assert_eq!(image_asset_path(&good).as_deref(), Some("covers/cover_01.jpg"));
        let no_enc = format!("{}covers/cover_01.jpg", marker);
        assert_eq!(image_asset_path(&no_enc).as_deref(), Some("covers/cover_01.jpg"));
        assert_eq!(image_asset_path("https://example.com/x.jpg"), None);
        assert_eq!(image_asset_path("news/img/x.jpg"), None);
    }
}

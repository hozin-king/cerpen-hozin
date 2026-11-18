//! build.rs — membangkitkan konstanta kunci ter-mask dari env build-time.
//!
//! Gradle menghitung kunci ter-XOR + mask (app/build.gradle, TIDAK berubah
//! di PoC ini) lalu task `buildRustContentGuard` meneruskannya sebagai env
//! `CONTENT_KEY_MASKED` / `CONTENT_KEY_MASK` (daftar hex dipisah koma,
//! format yang sama dengan define CMake lama).
//!
//! Yang ditulis ke OUT_DIR hanya byte TER-MASK + mask — TIDAK PERNAH kunci
//! mentah. Pesan error hanya menyebut NAMA env, bukan nilainya.
//! Build GAGAL eksplisit bila env tidak ada / format salah (fail-closed,
//! setara `message(FATAL_ERROR ...)` di CMakeLists.txt lama).

use std::env;
use std::fs;
use std::path::PathBuf;

fn parse_hex_list(name: &str, expected_len: usize) -> Vec<u8> {
    let raw = env::var(name).unwrap_or_else(|_| {
        panic!("{name} tidak di-set. Build harus lewat Gradle (task buildRustContentGuard).")
    });
    let bytes: Vec<u8> = raw
        .split(',')
        .map(|tok| {
            let tok = tok.trim();
            let hex = tok.strip_prefix("0x").unwrap_or(tok);
            u8::from_str_radix(hex, 16)
                .unwrap_or_else(|_| panic!("{name}: token hex tidak valid (panjang token salah atau bukan hex)."))
        })
        .collect();
    assert_eq!(
        bytes.len(),
        expected_len,
        "{name}: panjang harus {expected_len} byte."
    );
    bytes
}

fn main() {
    let masked = parse_hex_list("CONTENT_KEY_MASKED", 32);
    let mask = parse_hex_list("CONTENT_KEY_MASK", 8);
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR tidak di-set cargo."));
    let mut out = String::new();
    out.push_str("// Dibangkitkan build.rs dari env Gradle — JANGAN edit manual.\n");
    out.push_str(&format!("pub const MASKED_KEY: [u8; 32] = {masked:?};\n"));
    out.push_str(&format!("pub const MASK: [u8; 8] = {mask:?};\n"));
    fs::write(out_dir.join("masked_key.rs"), out).expect("gagal menulis masked_key.rs");
    println!("cargo:rerun-if-env-changed=CONTENT_KEY_MASKED");
    println!("cargo:rerun-if-env-changed=CONTENT_KEY_MASK");
}

//! contentguard — penjaga kunci konten Cerpen Hozin, port Rust dari
//! `contentguard.cpp` (C++). Mekanisme SAMA persis:
//!
//! * Kunci AES-256 (32 byte) di-bake saat build dalam bentuk ter-XOR
//!   dengan mask tetap 8 byte (diteruskan Gradle sebagai env build-time
//!   `CONTENT_KEY_MASKED` / `CONTENT_KEY_MASK`, lihat `build.rs`).
//! * Fungsi ini hanya membuka XOR itu dan mengembalikan 32 byte kunci
//!   lewat JNI (`ContentGuard.fetchKey()`).
//! * Tanda tangan JNI SAMA dengan versi C++ — `ContentGuard.kt` TIDAK
//!   berubah.
//!
//! Jujur: ini menghentikan penyalinan kasual, BUKAN reverser yang
//! bertekad (mask + data ter-XOR sama-sama ada di `.so`).
//! Lihat `CONTENT_PROTECTION.md`.
//!
//! Dua perbedaan kecil vs C++ (disengaja, tidak mengubah perilaku):
//! * `black_box` pada input XOR — mencegah compiler melipat
//!   `MASKED_KEY ^ MASK` di compile time menjadi kunci mentah di `.so`
//!   (build memakai `lto = "fat"`).
//! * `wipe` memakai volatile write — supaya compiler tidak membuang
//!   pembersihan stack seperti yang bisa terjadi pada loop biasa.

include!(concat!(env!("OUT_DIR"), "/masked_key.rs"));

mod endpoints;
mod endpoints_data;

use jni::objects::JObject;
use jni::sys::jbyteArray;
use jni::JNIEnv;

/// Buka XOR: kunci[i] = masked[i] ^ mask[i % 8]. Fungsi murni —
/// diuji unit test dengan vektor uji (BUKAN kunci asli).
fn unmask_key(masked: &[u8; 32], mask: &[u8; 8]) -> [i8; 32] {
    // black_box: compiler tidak boleh menganggap nilai ini diketahui
    // di compile time (kalau tidak, hasil XOR bisa dilipat menjadi
    // kunci mentah di .rodata).
    let masked = std::hint::black_box(masked);
    let mask = std::hint::black_box(mask);
    let mut key = [0i8; 32];
    for i in 0..32 {
        key[i] = (masked[i] ^ mask[i % 8]) as i8;
    }
    key
}

/// Nol-kan buffer di stack. Volatile write supaya compiler tidak
/// membuang wipe ini sebagai "dead store".
fn wipe(buf: &mut [i8]) {
    for b in buf.iter_mut() {
        unsafe { std::ptr::write_volatile(b, 0) };
    }
    std::hint::black_box(buf);
}

/// JNI: `Java_com_hozinking_cerpen_ContentGuard_fetchKey`.
/// Mengembalikan `jbyteArray` 32 byte, atau null bila alokasi gagal
/// (sama seperti versi C++).
#[no_mangle]
pub extern "system" fn Java_com_hozinking_cerpen_ContentGuard_fetchKey<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jbyteArray {
    let mut key = unmask_key(&MASKED_KEY, &MASK);
    match env.new_byte_array(32) {
        Ok(arr) => {
            let _ = env.set_byte_array_region(&arr, 0, &key);
            wipe(&mut key);
            arr.into_raw()
        }
        Err(_) => {
            wipe(&mut key);
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::unmask_key;

    /// Vektor uji PALSU (bukan kunci asli): masked = key ^ mask.
    /// Mask di sini bentuknya sama (8 byte), nilainya dibuat-buat.
    fn fake_pair() -> ([u8; 32], [u8; 8], [i8; 32]) {
        let mask: [u8; 8] = [0x3C, 0xA7, 0x19, 0xE2, 0x6B, 0xD4, 0x08, 0x5F];
        let key: [u8; 32] = [
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD,
            0xEE, 0xFF, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0xA0, 0xB0, 0xC0,
            0xD0, 0xE0, 0xF0, 0x01,
        ];
        let mut masked = [0u8; 32];
        for i in 0..32 {
            masked[i] = key[i] ^ mask[i % 8];
        }
        let expected: [i8; 32] = key.map(|b| b as i8);
        (masked, mask, expected)
    }

    #[test]
    fn unmask_mengembalikan_kunci_dari_vektor_uji() {
        let (masked, mask, expected) = fake_pair();
        assert_eq!(unmask_key(&masked, &mask), expected);
    }

    #[test]
    fn unmask_mask_berulang_setiap_8_byte() {
        // Byte 0 dan 8 memakai mask[0]; byte 7 dan 15 memakai mask[7].
        let (masked, mask, expected) = fake_pair();
        assert_eq!(masked[0] ^ mask[0], expected[0] as u8);
        assert_eq!(masked[8] ^ mask[0], expected[8] as u8);
        assert_eq!(masked[7] ^ mask[7], expected[7] as u8);
        assert_eq!(masked[15] ^ mask[7], expected[15] as u8);
    }

    #[test]
    fn unmask_kunci_semua_nol_dan_semua_satu() {
        let mask: [u8; 8] = [0xFF; 8];
        let masked_zero = [0xFFu8; 32]; // 0x00 ^ 0xFF
        assert_eq!(unmask_key(&masked_zero, &mask), [0i8; 32]);
        let masked_one = [0x00u8; 32]; // 0xFF ^ 0xFF
        assert_eq!(unmask_key(&masked_one, &mask), [-1i8; 32]);
    }

    #[test]
    fn konstanta_bangkitan_berukuran_benar() {
        // Nilai berasal dari env build-time (di `cargo test` lokal diisi
        // nilai uji); yang dicek di sini hanya bentuknya.
        assert_eq!(super::MASKED_KEY.len(), 32);
        assert_eq!(super::MASK.len(), 8);
    }
}

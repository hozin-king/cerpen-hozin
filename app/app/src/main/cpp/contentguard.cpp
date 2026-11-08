// contentguard — kunci konten Cerpen Hozin, di-bake saat build.
//
// Kunci AES-256 (32 byte) TIDAK ada sebagai string polos di file ini maupun
// di .so: Gradle meneruskan versi yang sudah di-XOR dengan mask tetap
// (CONTENT_KEY_MASKED + CONTENT_KEY_MASK, definisi compile dari CMake).
// Fungsi ini hanya membuka XOR itu dan mengembalikan 32 byte kunci.
// Jujur: ini menghentikan penyalinan kasual, BUKAN reverser yang bertekad
// (mask + data ter-XOR sama-sama ada di .so). Lihat CONTENT_PROTECTION.md.
#include <jni.h>

#include <cstddef>
#include <cstdint>

#ifndef CONTENT_KEY_MASKED
#error "CONTENT_KEY_MASKED tidak didefinisikan (cek app/build.gradle)"
#endif
#ifndef CONTENT_KEY_MASK
#error "CONTENT_KEY_MASK tidak didefinisikan (cek app/build.gradle)"
#endif

namespace {
constexpr uint8_t kMaskedKey[] = {CONTENT_KEY_MASKED};
constexpr uint8_t kMask[] = {CONTENT_KEY_MASK};
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hozinking_cerpen_ContentGuard_fetchKey(JNIEnv* env, jobject /*thiz*/) {
    constexpr size_t kLen = sizeof(kMaskedKey);
    constexpr size_t kMaskLen = sizeof(kMask);
    uint8_t key[kLen];
    for (size_t i = 0; i < kLen; ++i) {
        key[i] = static_cast<uint8_t>(kMaskedKey[i] ^ kMask[i % kMaskLen]);
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(kLen));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(kLen),
                            reinterpret_cast<const jbyte*>(key));
    for (size_t i = 0; i < kLen; ++i) key[i] = 0;  // bersihkan stack
    return out;
}

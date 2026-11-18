#!/usr/bin/env bash
# Build libcontentguard.so (Rust) untuk arm64-v8a + jalankan unit test.
#
# Dipanggil task Gradle `buildRustContentGuard` (env sudah di-set Gradle):
#   CONTENT_KEY_MASKED / CONTENT_KEY_MASK — hex dari app/build.gradle
#   CARGO_TARGET_DIR — di dalam build/ Gradle (tidak mengotori repo)
#   CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER / _AR — clang NDK API 26
#
# Fail-closed: `set -euo pipefail`; .so HARUS terbentuk, kalau tidak
# build gagal (tidak pernah ada APK tanpa lib contentguard).
set -euo pipefail
cd "$(dirname "$0")"
# Path absolut (jangan andalkan hitungan `..`): crate di
# src/main/rust/contentguard/, jniLibs di src/main/jniLibs/.
CRATE_DIR="$(pwd)"
MAIN_DIR="$(cd "$CRATE_DIR/../.." && pwd)"

cargo test --lib --quiet

cargo build --release --target aarch64-linux-android

SO="${CARGO_TARGET_DIR:-target}/aarch64-linux-android/release/libcontentguard.so"
if [ ! -f "$SO" ]; then
  echo "libcontentguard.so tidak terbentuk di $SO" >&2
  exit 1
fi
mkdir -p "$MAIN_DIR/jniLibs/arm64-v8a"
cp "$SO" "$MAIN_DIR/jniLibs/arm64-v8a/libcontentguard.so"
echo "libcontentguard.so (Rust) siap: $(stat -c%s "$MAIN_DIR/jniLibs/arm64-v8a/libcontentguard.so") byte"

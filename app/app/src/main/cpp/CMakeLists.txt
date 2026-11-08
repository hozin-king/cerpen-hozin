# Modul native `contentguard` — penjaga kunci konten Cerpen Hozin.
#
# Kunci AES-256 TIDAK ditulis di source ini. Saat build, Gradle menghitung
# versi kunci yang sudah di-XOR dengan mask tetap, lalu meneruskannya sebagai
# definisi compile CONTENT_KEY_MASKED + CONTENT_KEY_MASK (lihat
# app/build.gradle). Kode C++ di bawah hanya membuka XOR itu di runtime.
# Lihat CONTENT_PROTECTION.md untuk alur lengkap + batasan keamanan.
cmake_minimum_required(VERSION 3.22.1)
project(contentguard)

if(NOT DEFINED CONTENT_KEY_MASKED OR NOT DEFINED CONTENT_KEY_MASK)
  message(FATAL_ERROR "CONTENT_KEY_MASKED / CONTENT_KEY_MASK tidak diteruskan oleh Gradle. Set CERPEN_CONTENT_KEY (env / properti Gradle / file ~/workspace/cerpen-content.key).")
endif()

add_library(contentguard SHARED contentguard.cpp)

target_compile_definitions(contentguard PRIVATE
  "CONTENT_KEY_MASKED=${CONTENT_KEY_MASKED}"
  "CONTENT_KEY_MASK=${CONTENT_KEY_MASK}")

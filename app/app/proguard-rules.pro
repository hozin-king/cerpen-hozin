# ProGuard/R8 rules untuk Cerpen Hozin (debug + release memakai
# minifyEnabled=true; release juga shrinkResources=true).
#
# JNI — contentguard.cpp memanggil nama JNI statis
# Java_com_hozinking_cerpen_ContentGuard_fetchKey, jadi nama kelas
# ContentGuard + nama method fetchKey TIDAK boleh di-obfuscate.
# getKey() dipakai DataManager, jadi dipertahankan R8 sebagai kode yang
# terjangkau dari Activity.
-keep class com.hozinking.cerpen.ContentGuard {
    native <methods>;
}
# Backstop generik: nama method native apa pun dipertahankan
# (satu-satunya method native saat ini adalah ContentGuard.fetchKey).
-keepclasseswithmembernames class * {
    native <methods>;
}
# TIDAK ada keep untuk ContentCrypto / DataManager / Models: aplikasi
# tidak memakai refleksi (dicek 10 Nov 2026 — tanpa Class.forName /
# getDeclaredMethod / Gson / Moshi / kotlinx.serialization), semuanya
# dijangkau dari Activity lewat pemanggilan biasa, jadi R8
# mempertahankannya sendiri. Bila suatu hari ada refleksi, tambah keep
# sesempit mungkin untuk class itu saja, dengan bukti.

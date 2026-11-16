# ProGuard/R8 rules untuk Cerpen Hozin (debug + release memakai
# minifyEnabled=true agar APK kecil; target ~5 MB).
#
# JANGAN hapus keep di bawah: nama kelas + method ini dipakai JNI
# (ContentGuard.fetchKey -> Java_com_hozinking_cerpen_ContentGuard_fetchKey)
# dan jalur dekripsi konten (ContentCrypto + DataManager). Bila R8
# me-rename salah satunya, dekripsi gagal dan aplikasi menolak konten.
-keep class com.hozinking.cerpen.ContentGuard { *; }
-keep class com.hozinking.cerpen.ContentCrypto { *; }
-keep class com.hozinking.cerpen.DataManager { *; }
-keepclasseswithmembernames class * { native <methods>; }

# v1.1.38: Lottie 6.4.0 membawa okio 1.17.6 yang mereferensikan
# javax.annotation.Nullable (anotasi JSR-305, tidak ada di classpath) —
# R8 gagal "Missing class" tanpa ini. Anotasi saja, aman diabaikan.
-dontwarn javax.annotation.Nullable

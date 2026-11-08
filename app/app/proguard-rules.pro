# ProGuard/R8 rules untuk Cerpen Hozin (debug memakai minifyEnabled=true
# agar APK kecil; release tetap minifyEnabled=false).
#
# JANGAN hapus keep di bawah: nama kelas + method ini dipakai JNI
# (ContentGuard.fetchKey -> Java_com_hozinking_cerpen_ContentGuard_fetchKey)
# dan jalur dekripsi konten (ContentCrypto + DataManager). Bila R8
# me-rename salah satunya, dekripsi gagal dan aplikasi menolak konten.
-keep class com.hozinking.cerpen.ContentGuard { *; }
-keep class com.hozinking.cerpen.ContentCrypto { *; }
-keep class com.hozinking.cerpen.DataManager { *; }
-keepclasseswithmembernames class * { native <methods>; }

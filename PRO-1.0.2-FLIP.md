# Perbaikan Media Wahid TV Camera Pro 1.0.1

Permintaan ini menargetkan **Pro 1.0.1** (`tv.mediawahid.camerapro`). Source pada
main commit `aea71ac` di repositori ini adalah v5.0.4 (`tv.mediawahid.camera`),
dengan UI dan arsitektur berbeda. Source Kotlin Pro tidak ditemukan pada branch
yang diperiksa. Pekerjaan perbaikan Pro yang sudah dibuat pada sesi sebelumnya
dilanjutkan dalam `pro/`, menggunakan APK referensi 1.0.1 dan delta Java terarah.

Build dari `pro/`:

```bash
cd pro
./gradlew --no-daemon :flip:lintDebug :flip:testDebugUnitTest assembleFlip
python3 tools/verify_apk.py
```

File final: `pro/artifacts/Media-Wahid-TV-Camera-Pro-1.0.2-FLIP.apk`.
Workflow `pro-flip.yml` mengunggah APK dan laporan. Source aplikasi v5.0.4 pada
root tetap tersedia. Tidak ada repository baru yang dibuat.

Lihat [analisis APK](pro/docs/APK-ANALYSIS.md),
[hasil/prosedur tes](pro/docs/TESTING.md), dan [build/signing](pro/README.md).
Kunci signing asli dan logo yang hanya tersimpan pada ponsel tidak tersedia.

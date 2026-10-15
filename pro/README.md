# Media Wahid TV Camera Pro 1.0.2 FLIP

Perbaikan terarah terhadap APK Pro 1.0.1 dalam repositori ini. Package tetap
`tv.mediawahid.camerapro`, versionName `1.0.2`, versionCode `3`.

Source Kotlin asli tidak tersedia. Proyek Gradle ini mengompilasi delta Java,
merekonstruksi bytecode aplikasi dengan APKTool, kemudian mempertahankan semua
resource terkompilasi dan library asli. Ini bukan pemulihan identik source Kotlin
asli. Modul `flip` adalah payload kompilasi; **jangan instal `flip-debug.apk`**.

## Build

Prasyarat: JDK 21, Python 3.11+, Android SDK platform 36 dan build-tools 36.0.0.
Gunakan checkout yang sudah ada; setiap task cloud sudah terisolasi dan tidak
memerlukan Git worktree baru.

```bash
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
./gradlew --no-daemon :flip:lintDebug :flip:testDebugUnitTest assembleFlip
python3 tools/verify_apk.py
```

Hasil: `artifacts/Media-Wahid-TV-Camera-Pro-1.0.2-FLIP.apk`, SHA256 dan provenance.
APKTool dan APK referensi diverifikasi terhadap checksum terpin sebelum patch.
Gradle wrapper memverifikasi checksum distribusi Gradle.

Workflow [Pro 1.0.2 FLIP APK](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/workflows/pro-flip.yml) menjalankan build,
lint delta Java, 10 unit test, pemeriksaan struktur/resource, upload artifact, lalu
tujuh cold launch pada emulator Android 11. Lint tidak mengaudit ulang source
aplikasi asli yang tidak tersedia. Run CI yang belum dijalankan tidak dianggap lulus.

## Perbaikan

- `startRecording()` menggunakan `asPersistentRecording()` sebelum `start()`.
- Hanya tombol Flip mendapat predicate baru; lock template, mode dan orientasi
  selama recording tetap mengikuti implementasi asli.
- Saat recording/paused, kamera tujuan dan FHD/30 FPS diperiksa sebelum unbind.
  Preview, VideoCapture, Recorder, OverlayEffect, output MediaStore, audio dan
  state waktu dipertahankan; tidak ada stop/start untuk flip.
- Transisi ditolak selama STARTING/STOPPING, foto/import logo, atau switch lain.
  Kamera OPEN dan preview STREAMING diperiksa; timeout/error memicu upaya kembali
  ke kamera sebelumnya tanpa memanggil stop recording.
- Observer dibersihkan saat Finalize/destroy. Setelah STOP alur bind asli
  dipulihkan agar kamera dan kontrol dapat digunakan kembali.
- Log `MWTV.PersistentFlip` dan `MWTV.Recording` memuat identitas recording,
  Start/Finalize, durasi, byte dan status audio tanpa mengubah tampilan.

## Logo dan signing

**APK referensi tidak menyertakan artwork logo Masjid/Media Wahid TV di assets.**
WatermarkStore asli membaca logo impor galeri dari penyimpanan internal aplikasi;
jika tidak ada, badge `REPLACE LOGO` ditampilkan. Alur impor, dua template,
geometry, opacity, rendering foto/video dan ikon asli dipertahankan. Artwork
yang hanya tersimpan di ponsel tidak dapat dipulihkan dari APK.

Kunci signing 1.0.1 tidak tersedia. Build menggunakan identitas uji yang dibuat
di luar repositori, dengan password acak yang tidak disimpan dalam Git.
APK ini **tidak dapat menimpa instalasi 1.0.1 dengan kunci berbeda**. Jangan
menghapus aplikasi lama sebelum memiliki salinan logo/pengaturan yang diperlukan.
Package tidak diganti. Kunci uji CI bersifat sementara; run berbeda dapat memiliki
sertifikat berbeda.

Untuk signing resmi sediakan path melalui `WAHID_KEYSTORE`,
`WAHID_KEYSTORE_PASSWORD_FILE` dan `WAHID_KEY_ALIAS` di lingkungan aman.
Jangan memasukkan key atau password ke repositori.

## Pengujian perangkat

```bash
python3 tools/smoke_device.py --adb /path/to/adb
```

Skrip ini menguji instalasi dan tujuh peluncuran ulang, bukan hasil kamera.
Prosedur regresi Samsung dan status bukti ada di [docs/TESTING.md](docs/TESTING.md).
Tanpa uji media yang lulus, APK belum dinyatakan memenuhi seluruh definition of done.

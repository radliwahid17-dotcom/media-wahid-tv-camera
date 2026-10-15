# Media Wahid TV Camera Pro 1.0.3 Permanent Logos

Perbaikan terarah terhadap APK Pro 1.0.1 dalam repositori ini. Package tetap
`tv.mediawahid.camerapro`, versionName `1.0.3`, versionCode `4`.

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

Hasil: `artifacts/Media-Wahid-TV-Camera-Pro-1.0.3-PERMANENT-LOGOS.apk`, SHA256 dan provenance.
APKTool dan APK referensi diverifikasi terhadap checksum terpin sebelum patch.
Gradle wrapper memverifikasi checksum distribusi Gradle.

Workflow [Pro 1.0.3 Permanent Logos APK](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/workflows/pro-flip.yml) menjalankan build,
lint delta Java, 10 unit test, pemeriksaan struktur/resource, upload artifact, lalu
tujuh cold launch, 37 assertion adapter state, dan 32 kasus renderer logo pada
emulator Android 11. Lint tidak mengaudit ulang source
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

Dua artwork asli dipulihkan dari arsip base64 `app/src/main/logo-source`:
logo Masjid Raya Vila Inti Persada dan Media Wahid TV. JPEG sumber dikonversi
menjadi PNG 303×174 tanpa menggambar ulang atau mengubah ukuran; latar putih
asli dipertahankan. Asal dan SHA256 ada di [assets/artwork.json](assets/artwork.json).
Salinan JPEG Masjid pada drawable proyek lama rusak; arsip base64 final yang
utuh digunakan, bukan gambar rusak tersebut.

Aset permanen berada di `assets/watermarks/logo_masjid.png` dan
`assets/watermarks/logo_media_wahid_tv.png`. WatermarkStore selalu membaca aset
bawaan, termasuk setelah upgrade dengan file impor lama. Badge placeholder
dihapus; tombol impor galeri di Settings dihapus dan jalurnya dinonaktifkan.
Template A tetap Masjid kiri atas + TV kanan atas; B hanya TV kanan atas.
Ukuran default 25% lebar frame dan margin 3% tetap memakai geometry Pro asli.
Pipeline OverlayEffect asli tetap menarget preview, foto, dan video (mask 7).
Perbaikan flip dari 1.0.2 tetap disertakan; tidak ada perubahan Recorder atau UI
kamera utama dalam revisi logo ini.

Kunci signing 1.0.1 tidak tersedia. Build menggunakan identitas uji yang dibuat
di luar repositori, dengan password acak yang tidak disimpan dalam Git.
APK ini **tidak dapat menimpa instalasi 1.0.1 dengan kunci berbeda**. Cadangkan media/pengaturan sebelum mengganti instalasi dengan sertifikat berbeda.
Package tidak diganti. Kunci uji CI bersifat sementara; run berbeda dapat memiliki
sertifikat berbeda.

Untuk signing resmi sediakan path melalui `WAHID_KEYSTORE`,
`WAHID_KEYSTORE_PASSWORD_FILE` dan `WAHID_KEY_ALIAS` di lingkungan aman.
Jangan memasukkan key atau password ke repositori.

## Pengujian perangkat

```bash
python3 tools/smoke_device.py --adb /path/to/adb
python3 tools/device_state_test.py /path/to/adb
python3 tools/logo_device_test.py --adb /path/to/adb
```

Skrip ini menguji instalasi dan tujuh peluncuran ulang, bukan hasil kamera.
Prosedur regresi Samsung dan status bukti ada di [docs/TESTING.md](docs/TESTING.md).
Tanpa uji media yang lulus, APK belum dinyatakan memenuhi seluruh definition of done.

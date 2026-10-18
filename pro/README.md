# Media Wahid TV Camera Pro 1.0.6 — Original Quality Recovery

Perbaikan terarah terhadap APK Pro 1.0.1 dalam repositori ini. Package tetap
`tv.mediawahid.camerapro`, versionName `1.0.6`, versionCode `7`.

Target utama: pulihkan pengaturan capture dan encoder dari APK Pro 1.0.1 yang
telah dikonfirmasi pengguna menghasilkan foto dan video bagus (depan/belakang).
Baca [perubahan pemulihan kualitas 1.0.6](docs/RESTORE-QUALITY-1.0.6.md).
Versi 1.0.5 menguji bitrate 24 Mbps dan template video PREVIEW; eksperimen
tersebut kini dicabut. Video tetap memakai kompositor seamless flip, sehingga
kualitas pixel belum dapat diklaim identik dengan APK referensi tanpa tes Samsung.
Riwayat: [1.0.5](docs/QUALITY-1.0.5.md), [1.0.4](docs/SEAMLESS-FLIP.md).

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
./gradlew --no-daemon :flip:lintDebug :flip:testDebugUnitTest assembleFlip validationClasspath
python3 tools/verify_apk.py
```

Hasil: `artifacts/Media-Wahid-TV-Camera-Pro-1.0.6-ORIGINAL-QUALITY.apk`, SHA256 dan provenance.
APKTool dan APK referensi diverifikasi terhadap checksum terpin sebelum patch.
Gradle wrapper memverifikasi checksum distribusi Gradle.

Workflow [Pro Quality and Flip APK](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/workflows/pro-flip.yml) menjalankan build,
lint delta Java, 18 unit test, pemeriksaan struktur/resource, upload artifact, lalu
tujuh cold launch, 37 assertion adapter state, dan 32 kasus renderer logo pada
emulator Android 11, 15 assertion detail shader Full HD pada empat rotasi,
kemudian memeriksa empat foto offline dari kamera depan/belakang
dan video HD dari service asli dengan 20 flip dalam tiga menit. Lint tidak mengaudit ulang source
aplikasi asli yang tidak tersedia. Run CI yang belum dijalankan tidak dianggap lulus.

## Perbaikan

- `startRecording()` menggunakan `asPersistentRecording()` sebelum `start()`.
- Hanya tombol Flip mendapat predicate baru; lock template, mode dan orientasi
  selama recording tetap mengikuti implementasi asli.
- Saat recording/paused, kamera tujuan, kualitas aktif dan FPS diperiksa sebelum unbind;
  query kemampuan dilakukan di worker thread. Produksi tetap FHD SDR/30 FPS,
  dengan preferensi 24 Mbps dan template kamera PREVIEW seperti mode foto.
  Preview, VideoCapture, Recorder, OverlayEffect, output MediaStore, audio dan
  state waktu dipertahankan; tidak ada stop/start untuk flip.
- `StableVideoOutput` mempertahankan satu SurfaceRequest encoder; surface input
  kamera diganti terpisah. Renderer EGL mempertahankan clock/timestamp, sementara
  opsi surface-repeat MediaCodec mengulang buffer terakhir tanpa upload GPU ganda.
  Buffer yang ditahan diperbarui setiap 100 ms untuk codec dengan batas jumlah repeat.
  Watermark video memakai aset/settings/geometry/Canvas drawing Pro asli pada tekstur
  yang dikomposisi setelah normalisasi kamera, sehingga posisinya tetap pada encoder.
  Frame terakhir ditahan selama sensor tidak tersedia, lalu blend 120 ms dilakukan setelah
  frame kamera baru tiba. Ini tidak menghasilkan frame sensor baru selama jeda hardware.
- Transisi ditolak selama STARTING/STOPPING, foto/import logo, atau switch lain.
  Kamera OPEN dan frame video dari kamera tujuan diperiksa; timeout/error memicu upaya kembali
  ke kamera sebelumnya tanpa memanggil stop recording.
- Observer dibersihkan saat Finalize/destroy. Setelah STOP alur bind asli
  dipulihkan agar kamera dan kontrol dapat digunakan kembali.
- Log `MWTV.PersistentFlip`, `MWTV.FrameBridge` dan `MWTV.Recording` memuat identitas recording,
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
Mode PHOTO mempertahankan OverlayEffect asli (mask 7). Dalam mode VIDEO, effect
asli menarget preview (mask 1); watermark video memakai gambar dan drawing/geometry
asli pada kompositor encoder setelah normalisasi kamera. Logo tidak diduplikasi.
Geometri, resource dan UI kamera utama tidak diganti dalam perbaikan handoff.
Recorder dan muxer/audio asli digunakan melalui bridge frame, bukan diganti dengan
perekaman segmen atau penggabungan file.

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
python3 tools/quality_device_test.py --adb /path/to/adb
python3 -m pip install Pillow==11.3.0
python3 tools/photo_device_test.py --adb /path/to/adb --allow-resolution-fallback
python3 tools/video_device_test.py --adb /path/to/adb
```

Smoke test menguji instalasi dan tujuh peluncuran ulang. Renderer test memakai
frame sintetis; photo test memeriksa pixel artwork pada JPEG hasil kamera.
`--allow-resolution-fallback` menerima resolusi kamera emulator untuk pengujian
logo, tetapi tetap mencatat assertion FHD yang gagal. Ia tidak membuktikan kualitas Samsung.
Video test menggunakan fixture HD dengan preview VGA untuk emulator yang tidak mengiklankan FHD;
service, recording, watermark, audio, flip dan MediaStore berasal dari APK final.
Audio tetap dipilih dari profil perangkat oleh Recorder asli.
Fixture tidak mengubah APK atau resolusi produksi. Ia memeriksa PTS track video/audio,
playback, pixel watermark, Recording/Recorder/VideoCapture yang sama, serta pause/resume.
Observer UI/display dilepas hanya dalam fixture video HD agar tidak memicu bind FHD;
landscape di fixture memvalidasi orientasi file, sedangkan rotasi UI otomatis perlu Samsung.
Audio emulator hening tidak membuktikan sinkronisasi ucapan pada Samsung.
Prosedur regresi Samsung dan status bukti ada di [docs/TESTING.md](docs/TESTING.md).
Tanpa uji media yang lulus, APK belum dinyatakan memenuhi seluruh definition of done.

Profil gambar dan bukti terbaru: [docs/QUALITY-1.0.5.md](docs/QUALITY-1.0.5.md).
Arsitektur flip dan riwayat: [docs/SEAMLESS-FLIP.md](docs/SEAMLESS-FLIP.md).
Riwayat revisi logo permanen: [docs/PERMANENT-LOGOS.md](docs/PERMANENT-LOGOS.md).

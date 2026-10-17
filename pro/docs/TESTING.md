# Bukti dan prosedur pengujian

Revisi kualitas/flip terbaru: lihat [QUALITY-1.0.5.md](QUALITY-1.0.5.md).
Riwayat perbaikan flip 1.0.4: lihat [SEAMLESS-FLIP.md](SEAMLESS-FLIP.md).
Status native timing belum seluruhnya lulus; hasil lama di halaman ini tidak
membuktikan kelancaran 1.0.4 atau pengujian Samsung.
Hasil di bawah adalah riwayat build lama, bukan bukti pengujian APK terbaru.

Revisi logo permanen 1.0.3: lihat [PERMANENT-LOGOS.md](PERMANENT-LOGOS.md).
Bukti 1.0.2 di bawah adalah riwayat dan tidak membuktikan logo permanen.

## Hasil cloud yang dilakukan

- Gradle 8.13 / AGP 8.13.0 / JDK 21: kompilasi delta dan rebuild APK lulus.
- Android lint delta: tidak ada issue.
- JUnit: 10 test, 0 failure, 0 error, 0 skipped: fase recording/paused/idle,
  penolakan STARTING/STOPPING, readiness, import/foto, tap ganda,
  stale callback, cleanup Finalize, dan 10 transisi gate berurutan.
- apksigner dan zipalign 16 KB: lulus.
- Package/version/launcher, DEX delta dan preservasi 480 entri: lulus.
- Emulator Android 11 x86_64: instalasi dan tujuh cold launch lulus, tanpa
  FATAL EXCEPTION aplikasi. System UI emulator sempat ANR saat emulasi CPU;
  hasil ini hanya memvalidasi peluncuran aplikasi, bukan kestabilan seluruh sistem.
- Uji media/flip hasil video dan perangkat Samsung: belum diklaim lulus.
- APK release (`debuggable=false`): instalasi dan tujuh cold launch lulus.
- Adapter state dijalankan terhadap class asli APK pada Android melalui dalvikvm:
  37 assertion lulus, termasuk preservasi seluruh component yang tidak diubah,
  timer, byte, audio warning, lock dan predicate flip RECORDING/PAUSED/STARTING/STOPPING.
  Ini memvalidasi adapter biner, bukan perpindahan kamera fisik.
- Emulator menyediakan kamera depan/belakang tetapi profil camcorder hanya
  sampai 720p. Guard FHD SDR asli menolak mulai video dengan pesan yang terlihat.
  Karena itu flip recording/audio/output video belum dapat diuji di emulator ini;
  resolusi aplikasi tidak diturunkan untuk membuat tes tampak lulus.

Unit test gate tidak menggunakan kamera dan tidak membuktikan video kontinu.
CI memuat build/lint/test/struktur dan tujuh cold launch; hasil run aktual harus
dibaca sebelum menyatakan CI berhasil.

## Bukti CI

[Run 38752321823](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/38752321823)
pada commit `3abe30d` selesai dengan status **Success**. Job build lulus
kompilasi/lint/10 unit test/struktur APK dan mengunggah APK. Job install-launch
lulus tujuh cold launch Android 11 dan 37 assertion adapter state dari APK final.

[Unduh artifact APK](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/38752321823/artifacts/11981902784).
Artifact berupa arsip yang berisi APK, checksum dan provenance; login GitHub
mungkin diperlukan. Kunci uji CI berbeda dari kunci uji cloud lokal.

## Foto emulator

Empat foto dari APK dengan kode aplikasi pada commit `bb2439d` tersimpan dan
dapat didekode. Perubahan setelah commit tersebut hanya menyentuh callback
recording, tanpa mengubah alur foto; sampel ini tidak diklaim sebagai pengulangan
uji foto pada setiap APK berikutnya. Idle flip belakang → depan berhasil.

| Kamera | Template | JPEG | Capture/penyimpanan | Assertion Full HD |
|---|---|---|---|---|
| Belakang | Dua logo | 720×1280 | PASS | FAIL |
| Belakang | Satu logo | 720×1280 | PASS | FAIL |
| Depan | Satu logo | 1080×1920 | PASS | PASS |
| Depan | Dua logo | 1080×1920 | PASS | PASS |

Pemeriksaan visual empat file menunjukkan dua badge di kiri/kanan atas untuk
template 1 dan hanya badge kanan atas untuk template 2. Teks tidak terbalik;
posisi dan ukuran relatif konsisten. Artwork asli impor galeri tidak tersedia.
Uji foto keseluruhan mengembalikan exit 1 karena dua assertion Full HD gagal;
ini tidak diklaim lulus atau bukti kualitas Samsung. Bukti:
[hasil JSON](cloud-photo-results.json) dan [empat JPEG](photo-evidence/).

Build/lint/unit test dan pemeriksaan class state tidak memvalidasi hasil video,
kontinuitas audio, pause/resume recording, orientasi landscape, exposure Samsung,
performa termal/memory 90 menit, ataupun rollback akibat kegagalan hardware.

## Matriks regresi wajib pada Samsung

Catat model, Android, orientasi, template, lama video, storage dan suhu. Simpan
logcat dengan tag MWTV.PersistentFlip, MWTV.Recording dan CameraX.

| Kasus | Langkah dan hasil yang diperiksa |
|---|---|
| A Normal | Foto template A/B, video START/STOP, media tersimpan dan dapat dibuka |
| B Flip | Belakang → depan → belakang berulang; satu Start/Finalize/output URI; timer tidak reset |
| C Watermark | Dua logo A, hanya kanan atas B; periksa file foto dan frame video sebelum/sesudah flip; tanpa mirror/pergeseran/duplikat |
| D Audio | Ucapkan nomor sebelum/sesudah flip; dengarkan audio kontinu dan sinkron |
| E Stabilitas | Pause/resume, flip paused, tap cepat, STOP saat switching, restart, rekam lagi; 7–10 video pendek |
| F Panjang | Lewati 29 detik, kemudian 90 menit fisik jika kondisi memungkinkan; periksa panas, memory, storage, playback akhir |
| G Install/launch | Instal APK final, buka, izin ditolak/diberikan, tanpa force close |
| Kontrol | Zoom, exposure/brightness, torch, fullscreen, portrait/landscape, idle flip, galeri dan tombol asli |
| Recovery | Target tidak tersedia/tidak FHD; tolak sebelum unbind atau rollback dengan recording/output sama |

Pisahkan PASS, FAIL, tidak tersedia dan belum diuji. Audio emulator hening tidak
memvalidasi mikrofon fisik. Build/lint tidak membuktikan ketahanan Samsung,
watermark, exposure, rotasi atau durasi 90 menit.

# Revisi logo permanen — Pro 1.0.3 (basis Pro 1.0.1)

## Penyebab dan perubahan

APK referensi Pro 1.0.1 tidak membawa artwork logo. WatermarkStore mengutamakan
file impor di penyimpanan internal, lalu aset jika tersedia; tanpa keduanya ia
menggambar badge `REPLACE LOGO`. Karena itu instalasi baru tidak mendapat logo asli.

- `assets/watermarks/logo_masjid.png`, `logo_media_wahid_tv.png`: dua artwork
  bawaan, dipulihkan dari arsip sumber logo di repositori yang sama.
- `assets/artwork.json`: provenance, ukuran, hash sumber JPEG dan PNG.
- `tools/patch_baseline.py`: utamakan aset permanen, hapus generator badge,
  nonaktifkan impor, hapus kedua tombol galeri pada Settings. Pengaturan ukuran,
  opacity dan brightness tetap ada. VersionName 1.0.3 / versionCode 4 agar lebih
  tinggi daripada build flip 1.0.2 / code 3 sebelumnya.
- `tools/rebuild.py`: sertakan kedua PNG dalam APK akhir; tidak cukup hanya
  memasukkannya ke payload kompilasi. Seluruh resource terkompilasi dan library
  asli tetap dipertahankan.
- `tools/verify_apk.py`: periksa dua aset/hashes/PNG CRC, identitas dan tidak adanya
  teks placeholder/tombol impor. Semua 480 entri baseline selain manifest dan
  classes3.dex tetap identik byte per byte.
- `tools/logo_device_test.py`, `tools/android-tests/`: instrumentation terpisah
  yang memakai class asli pada APK final, Android AssetManager/Bitmap/Canvas dan
  callback asli OverlayEffect. Stub Frame hanya dipakai saat kompilasi, tidak
  masuk APK uji.
- Workflow `pro-flip.yml`: build/lint/unit/struktur, APK artifact, tujuh cold launch,
  adapter state, dan 32 kombinasi render template/resolusi/rotasi/front-mirror.

## Batas artwork dan pengujian

Sumber JPEG asli 303×174 berlatar putih. Konversi PNG tidak menambah transparansi
atau detail yang tidak ada pada sumber. Tidak ada redesign atau logo buatan.
Tidak ada gambar referensi baru terlampir pada revisi ini; artwork memakai arsip
logo yang sudah ada dalam repositori. Kedua gambar dapat ditinjau langsung di
folder assets/watermarks.

Native renderer test menggunakan frame sintetis: ia menguji pemuatan aset,
prioritas terhadap file impor lama yang rusak, kedua template, 4 rotasi,
2 resolusi, input depan/belakang, serta cache tanpa watermark hilang/duplikat.
Ini tidak membuktikan hasil encoder video, audio, flip saat recording atau
ketahanan 90 menit pada Samsung. Hasil yang benar-benar dijalankan dilaporkan
terpisah; bukti 1.0.2 di TESTING.md merupakan riwayat, bukan validasi logo baru.

Kunci signing asli tidak tersedia. APK final non-debuggable menggunakan sertifikat
uji, package tetap tv.mediawahid.camerapro. Ia mungkin tidak dapat menimpa
instalasi lama/CI lain yang berbeda sertifikat. Key/password tidak disimpan Git.

## Bukti lokal revisi logo

- Build/lint/10 unit test yang dijalankan ulang, signature dan zipalign: PASS.
- Struktur APK: package/versi benar, kedua PNG/hashes/CRC benar, 480 entri asli
  tetap identik, teks badge placeholder dan tombol impor tidak ada.
- Class state asli APK pada Android: 37 assertion PASS.
- Callback renderer asli pada Android: 32 kasus, 145 assertion PASS. Dua template,
  640×480 dan 1920×1080, rotasi 0/90/180/270, input front/rear, cache dan file
  impor lama rusak. Android Canvas menunjukkan selisih interpolasi saat diputar
  90/270; checker menoleransi perubahan kanal ≤8/255 dan mensyaratkan pixel
  dengan selisih lebih besar kurang dari 0,3% frame. Posisi/template/rotasi tetap diuji.
- APK lokal berhasil dibuka dan satu foto belakang template A telah diperiksa
  secara visual serta pixel: kedua artwork tertanam pada JPEG, bukan hanya UI.
  Resolusi 720×1280 merupakan fallback kamera belakang emulator yang sudah ada.
- Percobaan tujuh cold launch lokal tidak lulus karena launch timeout pada CPU
  emulation. Foto pertama sempat terhalang dialog ANR **System UI**, bukan
  FATAL EXCEPTION aplikasi. Layar emulator kemudian diubah ke 720×1280 / 280 dpi
  untuk mengurangi beban; output kamera tetap mengikuti konfigurasi Pro asli.
- Uji foto awal salah memilih file MediaStore `.pending` sebelum nama final
  tersedia. Skrip diperbaiki agar hanya membaca JPEG final yang bukan file tersembunyi.

[Hasil lokal](permanent-logo-local-validation.json),
[32 kasus renderer](permanent-logo-native.json),
[preview asli emulator](permanent-logo-preview.png),
[contoh JPEG kamera](permanent-logo-example.jpg).

Dua kasus depan lokal tidak dituntaskan setelah suite ekuivalen empat foto
lulus pada CI KVM. Dua foto belakang lokal di atas diverifikasi pada file JPEG
yang sebenarnya; empat kasus CI dilaporkan terpisah di bawah.

## Status perbaikan flip sebelumnya

Revisi ini tetap membawa perbaikan 1.0.2: Pro 1.0.1 mengunci tombol dan operasi
switch saat konfigurasi terkunci, sedangkan PendingRecording belum dibuat
persistent. Patch `CameraRecordingService` memakai `asPersistentRecording()`;
helper `PersistentFlip` mengganti binding kamera dengan Preview, Recorder,
VideoCapture dan OverlayEffect yang sama, serta melakukan gate/rollback tanpa
memanggil stop recording untuk flip. Predicate tombol MainActivity diubah
khusus Flip; lock template dan kontrol lain tetap mengikuti Pro.

Kode Java helper flip tidak diubah pada revisi logo. Native state/gate test
memvalidasi preservasi timer/bytes/lock; ia tidak membuktikan satu file video,
mikrofon atau perpindahan kamera saat recording. Emulator ini tidak menyediakan
profil FHD camcorder yang diminta baseline, sehingga guard asli menolak mulai
video. Pengujian hasil encoder dan flip recording tetap membutuhkan perangkat
Samsung yang mendukung konfigurasi tersebut; resolusi aplikasi tidak diturunkan.

## CI final — PASS

[Run 38822833059](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/38822833059)
pada commit `8b6585a` selesai **Success**, durasi 6m21s. Job build 2m41s;
job install-launch 3m32s. Hasil berdasarkan status run/job aktual dan skrip
berurutan yang berhenti jika suatu assertion gagal:

- Build APK, lint delta, 10 unit test, signature/zipalign dan struktur: PASS.
- Instalasi dan tujuh cold launch Android 11 tanpa FATAL EXCEPTION: PASS.
- Adapter state asli: 37 assertion PASS.
- Renderer asli: 32 kasus / 145 assertion PASS.
- Empat JPEG kamera offline: rear A, rear B, front B, front A; decode dan
  pemeriksaan pixel artwork/template PASS. Wi-Fi/data dinonaktifkan sebelum
  uji foto. Idle flip rear → front juga dilalui. Empat screenshot preview dan
  JPEG aktual disertakan pada artifact bukti runtime.

Suite foto menggunakan `--allow-resolution-fallback`: PASS di sini berarti
JPEG berhasil disimpan/didekode dan logo sesuai template. Ini tidak berarti
semua kamera mencapai FHD. Rincian resolusi dan assertion FHD yang terpisah ada
pada `photo-device.json` di artifact. Lokal rear tetap 720×1280 seperti baseline.
Pengujian portrait/landscape di atas adalah renderer sintetis, bukan seluruh
urutan orientasi kamera fisik. Video/audio/flip recording dan 90 menit belum diuji.

[Unduh APK final](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/38822833059/artifacts/12012933592)
— instal `Media-Wahid-TV-Camera-Pro-1.0.3-PERMANENT-LOGOS.apk` dari arsip.
`logo-tests.apk` adalah harness validasi, bukan aplikasi kamera untuk dipakai.
Checksum/provenance ada dalam arsip; sertifikat CI berbeda dari build lokal.
Login GitHub mungkin diperlukan untuk mengunduh artifact.

[Bukti runtime dan empat foto](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/38822833059/artifacts/12013928766),
[laporan build/lint/unit](https://github.com/radliwahid17-dotcom/media-wahid-tv-camera/actions/runs/38822833059/artifacts/12013637945).
[Contoh lokal template B](permanent-logo-template-b.jpg) menunjukkan hanya TV kanan atas.

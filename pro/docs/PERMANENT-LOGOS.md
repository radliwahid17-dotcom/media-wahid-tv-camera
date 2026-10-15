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

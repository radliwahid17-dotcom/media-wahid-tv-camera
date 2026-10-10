# MEDIA WAHID TV STUDIO v6.0 — PREVIEW

**Editor baru terpisah dari aplikasi Camera v5.** Aplikasi ini tidak merekam video: rekam memakai kamera Samsung, lalu impor hasilnya dari Galeri atau menu Share.

## Alur penggunaan
1. Rekam menggunakan aplikasi Samsung Camera, atau ambil foto.
2. Buka MEDIA WAHID TV STUDIO dan pilih foto/video, atau gunakan Share dari Galeri.
3. Pilih template logo: **Masjid + Media Wahid TV** atau **Media Wahid TV only**.
4. Pilih efek: **Default** (warna asli), **Crystal Clear**, **Bright Vision**, **True Color**, **Cinematic Pro**, atau **Classic Mono**.
5. Periksa pratinjau foto/frame pertama, lalu tekan **SIMPAN HASIL + WATERMARK**.
6. File keluaran terpisah tersimpan di Pictures/MEDIA WAHID TV atau Movies/MEDIA WAHID TV.

Aset logo asli dipertahankan dari repo camera. Jangan menggantinya dengan placeholder.

## Implementasi
- Android native Kotlin, minimal Android 10 (API 29).
- Media3 Transformer 1.11.1: filter OpenGL dan watermark di semua frame video.
- Photo: Bitmap/ColorMatrix + logo kemudian disimpan JPEG berkualitas 96.
- Foto panjang sisi maksimal 6000 px untuk membatasi penggunaan memori; foto yang lebih besar akan dikecilkan.
- Video tidak mempunyai batas durasi yang ditentukan aplikasi. Perangkat, codec, baterai, suhu, penyimpanan, dan waktu proses tetap membatasi kemampuan nyata.
- Video diproses sementara di cache aplikasi dan kemudian disalin ke Galeri. Ruang kosong harus mencukupi kedua file.
- Untuk saat ini **wajib biarkan aplikasi terbuka selama ekspor video**; belum ada foreground service untuk render background.
- Suara video ditangani oleh Media3 Transformer; sinkronisasi audio dan kualitas perlu diuji pada perangkat sesungguhnya.
- Default berarti tanpa filter warna tambahan; ekspor dengan watermark tetap membutuhkan encoding ulang dan bukan salinan bit-identik.
- Video preview adalah **satu frame**, bukan video playback filter real-time.
- Instal berdampingan dengan Camera v5: applicationId `tv.mediawahid.studio`.

## Uji sebelum dianggap final
- Foto portrait dan landscape; dua template dan enam efek.
- Video pendek 1–3 menit, 30 menit, 90 menit, 120 menit; semua template dan orientasi.
- Ukuran, kejernihan, posisi logo, audio sync, frame drop, suhu dan kebutuhan free space.
- Batal ekspor, file sumber tidak berubah, file hasil benar muncul di Galeri.
- Coba aplikasi Samsung Camera yang sama persis dengan milik pengguna.

**Status: kode preview sudah dibuat. Build GitHub Actions dapat membuat debug APK. Belum sertifikasi uji HP Samsung 90–120 menit.**

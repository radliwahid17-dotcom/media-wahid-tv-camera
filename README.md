# MEDIA WAHID TV Camera v5.0.0 FINAL

Versi final mengganti arsitektur lama "buka Samsung Camera → pilih video → render watermark" dengan CameraX live watermark.

## Target perangkat utama
- Samsung Galaxy A16 5G (SM-A166P family)
- Target video: FHD 1920×1080
- Tidak ada batas durasi buatan aplikasi
- Target operasional: rekaman 90 menit atau lebih selama storage, baterai, suhu perangkat, dan Android tetap memungkinkan

## Cara kerja
1. Pilih template sebelum merekam.
2. Tekan REC.
3. Watermark diproses langsung pada frame kamera saat perekaman.
4. Video ditulis langsung ke MediaStore / Galeri.
5. Tekan STOP; file hanya perlu difinalisasi, tidak dirender ulang berjam-jam.

## Template
### MASJID + MEDIA WAHID TV
- Masjid di kiri atas
- MEDIA WAHID TV di kanan atas

### MEDIA WAHID TV
- MEDIA WAHID TV di kanan atas

Template dikunci selama rekaman agar watermark tidak berubah di tengah file.

## Rekaman panjang
- CameraX Recorder FHD dengan fallback otomatis bila perangkat tidak mendukung profil FHD.
- Persistent recording dipakai agar rebind / switch kamera tidak otomatis memutus sesi.
- Tidak menggunakan EXTRA_DURATION_LIMIT.
- Tidak menggunakan external Samsung capture intent.
- Tidak ada video picker.
- Tidak ada post-render Media3 untuk video.
- Hasil video ditulis langsung ke Movies/MEDIA WAHID TV.

## Foto
Foto tetap diproses fail-closed: hasil baru masuk Galeri setelah watermark foto berhasil dibuat dan diverifikasi.

## Safety / fail-closed
Jika engine overlay mengalami unrecoverable error, aplikasi menghentikan rekaman dan menonaktifkan capture agar tidak menghasilkan video tanpa watermark.

## Final CI gate
GitHub Actions menjalankan:
- validasi asset logo,
- static architecture checks,
- Android lint,
- unit test task,
- 7 clean APK builds berturut-turut,
- integrity test APK,
- verifikasi kedua logo ada di APK.

Artifact:
**MEDIA-WAHID-TV-Camera-v5.0.0-FINAL**

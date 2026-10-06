# MEDIA WAHID TV Camera v5.0.0 — Final Live Watermark

Versi final mengganti alur lama "buka Samsung Camera → pilih video → render ulang" dengan CameraX native di dalam aplikasi.

## Tujuan utama
- Rekaman panjang tanpa batas durasi buatan aplikasi.
- Target utama FHD (1920×1080) dengan fallback aman bila perangkat tidak mendukung. Bitrate ditargetkan 8 Mbps untuk endurance dan ukuran file yang lebih terprediksi.
- Watermark ditanam langsung saat frame direkam, jadi setelah tombol STOP tidak ada proses render video panjang.
- Video langsung disimpan ke Galeri → Movies → MEDIA WAHID TV.
- Foto langsung disimpan ke Galeri → Pictures → MEDIA WAHID TV.
- Template dipilih sebelum foto/rekam dan dikunci selama rekaman.
- Kamera depan/belakang dapat diganti saat recording menggunakan persistent recording.
- Pinch-to-zoom dan tap-to-focus.
- Rekaman tidak boleh keluar lewat tombol Back sebelum dihentikan.
- Jika storage hampir habis, aplikasi menghentikan rekaman secara aman.
- File hasil rekaman yang finalize dengan error dibersihkan agar tidak meninggalkan file rusak.

## Template
### MASJID + MEDIA WAHID TV
- Logo Masjid kiri atas.
- Logo MEDIA WAHID TV kanan atas.

### MEDIA WAHID TV
- Logo MEDIA WAHID TV kanan atas.

## Long recording
Aplikasi tidak menetapkan batas 30 detik, 50 menit, 90 menit, atau batas durasi lain. Durasi nyata dibatasi oleh storage, kondisi termal perangkat, baterai, dan kemampuan encoder perangkat.

Untuk target operasional 90 menit, aplikasi mensyaratkan minimal 8 GB ruang kosong sebelum mulai dan akan menghentikan recording secara aman jika ruang kosong turun di bawah 1 GB. Baterai dan kondisi termal perangkat tetap perlu dijaga.

## Build target
- Android minSdk 29
- CameraX 1.6.2 stable
- FHD preferred
- Direct MediaStore output
- Live OverlayEffect watermark

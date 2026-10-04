# MEDIA WAHID TV Camera v3

Aplikasi Android khusus MEDIA WAHID TV dengan fokus utama pada hasil yang aman untuk dipakai di lapangan.

## Prinsip v3

- Rekam memakai kamera bawaan Samsung agar fitur kamera tetap normal.
- Video panjang mengikuti kemampuan Samsung Camera, bukan timer buatan aplikasi.
- Kamera depan/belakang dan fitur kamera tetap dikelola aplikasi kamera bawaan.
- Setelah video selesai, pilih rekaman terakhir.
- Aplikasi menanam logo MEDIA WAHID TV dengan Media3 Transformer.
- Hasil tidak disimpan ke Galeri sampai watermark diverifikasi pada frame hasil.
- Foto juga diverifikasi setelah watermark ditanam.
- File asli dari Samsung Camera tidak dihapus.
- Jika proses watermark gagal, aplikasi gagal secara aman dan tidak mengklaim sukses.

## Lokasi hasil

- Video: Galeri → Movies → MEDIA WAHID TV
- Foto: Galeri → Pictures → MEDIA WAHID TV

## Build gate

GitHub Actions menolak build jika:
- source logo rusak,
- logo di dalam APK tidak bisa di-decode,
- masih ada referensi/resource Masjid lama,
- compile atau Android lint gagal.

Artifact final: **MEDIA-WAHID-TV-APK-v3**

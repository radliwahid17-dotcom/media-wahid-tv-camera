# MEDIA WAHID TV Camera v4.0.4 — Hardened Dual Template

Satu aplikasi Android dengan dua template watermark yang bisa dipilih sebelum foto atau video.

## Template

### Template A — Masjid + MEDIA WAHID TV
- Logo Masjid: kiri atas
- Logo MEDIA WAHID TV: kanan atas
- Kedua logo wajib lolos verifikasi sebelum hasil dinyatakan sukses

### Template B — MEDIA WAHID TV
- Logo MEDIA WAHID TV: kanan atas
- Logo wajib lolos verifikasi sebelum hasil dinyatakan sukses

Pilihan template tersimpan otomatis dan template yang aktif dikunci saat tombol foto/video ditekan.

## Kamera
Aplikasi membuka Samsung Camera asli agar fungsi kamera vendor tetap tersedia. Setelah video selesai, pilih rekaman yang baru dibuat untuk dirender dengan template yang dipilih.

## Fail-closed watermark
Foto/video baru disimpan ke folder MEDIA WAHID TV setelah watermark template terverifikasi pada hasil render.

## Lokasi hasil
- Video: Galeri → Movies → MEDIA WAHID TV
- Foto: Galeri → Pictures → MEDIA WAHID TV

Nama file membedakan template:
- `MEDIA_WAHID_TV_DUAL_...`
- `MEDIA_WAHID_TV_MEDIA_...`

## Build gate
GitHub Actions memeriksa:
- kedua asset logo dapat di-decode,
- compile + Android lint,
- kedua logo tetap valid setelah masuk APK,
- engine memuat kedua mode template.

Artifact: **MEDIA-WAHID-TV-APK-v4-DUAL-TEMPLATE**


## v4.0.4 hardening
- Template yang dipilih dikunci **sinkron** sebelum Samsung Camera dibuka.
- Jika lock template gagal/hilang, capture dihentikan (fail-closed) supaya tidak diam-diam jatuh ke template lain.
- Mode **Masjid + MEDIA WAHID TV** pada video dibuat sebagai **satu bitmap overlay komposit**: Masjid kiri + MEDIA WAHID TV kanan.
- Path foto pending juga disimpan sinkron agar tahan process recreation.
